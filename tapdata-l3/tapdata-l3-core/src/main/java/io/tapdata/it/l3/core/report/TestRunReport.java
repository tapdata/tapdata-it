package io.tapdata.it.l3.core.report;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated result of one L3 CLI run — the single source for the JSON,
 * JUnit XML and HTML report generators (design doc §8.2).
 */
@Getter
@Builder
public class TestRunReport {

    private final String runId;
    private final String suiteName;
    private final Instant startTime;
    private final Instant endTime;
    private final long durationMs;
    private final List<ScenarioReport> scenarios;
    private final MetricsSummary metricsSummary;

    public int total() {
        return scenarios.size();
    }

    public int passed() {
        return (int) scenarios.stream().filter(ScenarioReport::isSuccess).count();
    }

    public int failed() {
        return total() - passed();
    }

    public double passRate() {
        return total() == 0 ? 0.0 : passed() * 100.0 / total();
    }
}
