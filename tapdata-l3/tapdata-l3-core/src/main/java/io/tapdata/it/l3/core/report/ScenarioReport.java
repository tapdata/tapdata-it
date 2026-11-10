package io.tapdata.it.l3.core.report;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/** Scenario-level report entry. */
@Getter
@Builder
public class ScenarioReport {

    private final String caseId;
    private final String name;
    private final String group;
    private final boolean success;
    private final long durationMs;
    private final Instant startTime;
    private final Instant endTime;
    private final List<StepReport> steps;

    public String status() {
        return success ? "PASSED" : "FAILED";
    }
}
