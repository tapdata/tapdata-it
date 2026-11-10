package io.tapdata.it.l3.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable structured report (Grafana / CI statistics) — design doc §8.2.
 */
public final class JsonReportGenerator {

    public static final String FILE_NAME = "test-report.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonReportGenerator() {
    }

    public static Path write(TestRunReport report, Path outputDir) throws IOException {
        Path file = outputDir.resolve(FILE_NAME);
        MAPPER.writeValue(file.toFile(), toMap(report));
        return file;
    }

    public static String toJson(TestRunReport report) throws IOException {
        return MAPPER.writeValueAsString(toMap(report));
    }

    private static Map<String, Object> toMap(TestRunReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("case_id", report.getRunId());
        root.put("suiteName", report.getSuiteName());
        root.put("startTime", report.getStartTime().toString());
        root.put("endTime", report.getEndTime().toString());
        root.put("durationMs", report.getDurationMs());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", report.total());
        summary.put("passed", report.passed());
        summary.put("failed", report.failed());
        summary.put("skipped", 0);
        summary.put("passRate", round(report.passRate()));
        root.put("summary", summary);

        MetricsSummary metrics = report.getMetricsSummary();
        Map<String, Object> metricsSummary = new LinkedHashMap<>();
        metricsSummary.put("avgReplicationLagMs", round(metrics.getAvgReplicationLagMs()));
        metricsSummary.put("maxReplicationLagMs", round(metrics.getMaxReplicationLagMs()));
        metricsSummary.put("peakThroughputRps", round(metrics.getPeakThroughputRps()));
        metricsSummary.put("avgDbForgeProvisionTimeMs", round(metrics.getAvgDbForgeProvisionTimeMs()));
        root.put("metricsSummary", metricsSummary);

        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (ScenarioReport scenario : report.getScenarios()) {
            Map<String, Object> scenarioMap = new LinkedHashMap<>();
            scenarioMap.put("id", scenario.getCaseId());
            scenarioMap.put("name", scenario.getName());
            scenarioMap.put("group", scenario.getGroup());
            scenarioMap.put("status", scenario.status());
            scenarioMap.put("durationMs", scenario.getDurationMs());

            List<Map<String, Object>> steps = new ArrayList<>();
            for (StepReport step : scenario.getSteps()) {
                Map<String, Object> stepMap = new LinkedHashMap<>();
                stepMap.put("id", step.getId());
                stepMap.put("action", step.getAction());
                stepMap.put("status", step.isSuccess() ? "PASSED" : "FAILED");
                stepMap.put("durationMs", step.getDurationMs());
                if (!step.getMetrics().isEmpty()) {
                    stepMap.put("metrics", step.getMetrics());
                }
                if (!step.isSuccess() && step.getErrorMessage() != null) {
                    stepMap.put("error", step.getErrorMessage());
                }
                steps.add(stepMap);
            }
            scenarioMap.put("steps", steps);
            scenarios.add(scenarioMap);
        }
        root.put("scenarios", scenarios);
        return root;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
