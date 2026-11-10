package io.tapdata.it.l3.core.report;

import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.plugin.step.StepResult;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts raw {@link ScenarioResult}s into the report model consumed by the
 * JSON / JUnit XML / HTML generators.
 * <p>
 * Step metrics are extracted from the outputs registered by each step. The
 * framework does not hard-code a metric provider: any step that emits one of the
 * recognised keys (for example {@code cdc_lag_ms} from a TM metrics step, or
 * {@code provision_time_ms} from dbforge) automatically flows into the report.
 */
public final class ReportBuilder {

    public static final String DEFAULT_SUITE_NAME = "TapData L3 Integration Test Suite";

    /** Output keys recognised as report metrics. */
    private static final Set<String> METRIC_KEYS = Set.of(
            "cdc_lag_ms", "replication_lag_ms",
            "throughput_rps", "rps",
            "provision_time_ms",
            "elapsed_ms",
            "row_count", "total_rows", "affected_rows", "total_operations");

    private static final Set<String> LAG_KEYS = Set.of("cdc_lag_ms", "replication_lag_ms");
    private static final Set<String> THROUGHPUT_KEYS = Set.of("throughput_rps", "rps");

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    private ReportBuilder() {
    }

    public static TestRunReport build(List<ScenarioResult> results) {
        return build(results, DEFAULT_SUITE_NAME);
    }

    public static TestRunReport build(List<ScenarioResult> results, String suiteName) {
        List<ScenarioResult> safeResults = results == null ? List.of() : results;

        Instant start = safeResults.stream()
                .map(ScenarioResult::getStartTime)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(Instant.now());
        Instant end = safeResults.stream()
                .map(ScenarioResult::getEndTime)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(start);

        List<ScenarioReport> scenarios = new ArrayList<>(safeResults.size());
        for (ScenarioResult result : safeResults) {
            scenarios.add(toScenarioReport(result));
        }

        return TestRunReport.builder()
                .runId("L3-RUN-" + RUN_ID_FORMAT.format(start))
                .suiteName(suiteName == null || suiteName.isBlank() ? DEFAULT_SUITE_NAME : suiteName)
                .startTime(start)
                .endTime(end)
                .durationMs(Math.max(0, Duration.between(start, end).toMillis()))
                .scenarios(scenarios)
                .metricsSummary(summarize(scenarios))
                .build();
    }

    private static ScenarioReport toScenarioReport(ScenarioResult result) {
        ScenarioConfig scenario = result.getScenario();
        List<StepReport> steps = new ArrayList<>();
        if (result.getSteps() != null) {
            for (StepResult stepResult : result.getSteps()) {
                steps.add(toStepReport(stepResult));
            }
        }

        return ScenarioReport.builder()
                .caseId(scenario != null ? scenario.getCaseId() : null)
                .name(scenario != null ? scenario.getScenario() : null)
                .group(normalizeGroup(scenario != null ? scenario.getGroup() : null))
                .success(result.isSuccess())
                .durationMs(result.getDurationMs())
                .startTime(result.getStartTime())
                .endTime(result.getEndTime())
                .steps(steps)
                .build();
    }

    private static StepReport toStepReport(StepResult result) {
        StepConfig config = result.getStepConfig();
        Throwable error = result.getError();
        return StepReport.builder()
                .id(config != null && config.getId() != null ? config.getId() : "(unknown)")
                .action(config != null && config.getAction() != null ? config.getAction() : "(unknown)")
                .success(result.isSuccess())
                .durationMs(result.getDurationMs())
                .metrics(extractMetrics(result.getOutputs()))
                .errorMessage(error == null ? null : errorMessage(error))
                .stackTrace(error == null ? null : stackTrace(error))
                .build();
    }

    static Map<String, Object> extractMetrics(Map<String, Object> outputs) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (outputs == null || outputs.isEmpty()) {
            return metrics;
        }
        for (String key : METRIC_KEYS) {
            Object value = outputs.get(key);
            if (value instanceof Number number) {
                metrics.put(key, number);
            }
        }
        return metrics;
    }

    static MetricsSummary summarize(List<ScenarioReport> scenarios) {
        List<Double> lags = new ArrayList<>();
        List<Double> throughputs = new ArrayList<>();
        List<Double> provisionTimes = new ArrayList<>();

        for (ScenarioReport scenario : scenarios) {
            for (StepReport step : scenario.getSteps()) {
                for (Map.Entry<String, Object> entry : step.getMetrics().entrySet()) {
                    if (!(entry.getValue() instanceof Number number)) {
                        continue;
                    }
                    double value = number.doubleValue();
                    if (LAG_KEYS.contains(entry.getKey())) {
                        lags.add(value);
                    } else if (THROUGHPUT_KEYS.contains(entry.getKey())) {
                        throughputs.add(value);
                    } else if ("provision_time_ms".equals(entry.getKey())
                            && step.getAction() != null && step.getAction().startsWith("dbforge")) {
                        provisionTimes.add(value);
                    }
                }
            }
        }

        return MetricsSummary.builder()
                .avgReplicationLagMs(average(lags))
                .maxReplicationLagMs(lags.stream().mapToDouble(Double::doubleValue).max().orElse(0.0))
                .peakThroughputRps(throughputs.stream().mapToDouble(Double::doubleValue).max().orElse(0.0))
                .avgDbForgeProvisionTimeMs(average(provisionTimes))
                .build();
    }

    private static double average(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static String normalizeGroup(String group) {
        return group == null || group.isBlank() ? "ungrouped" : group.trim().toLowerCase(Locale.ROOT);
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
