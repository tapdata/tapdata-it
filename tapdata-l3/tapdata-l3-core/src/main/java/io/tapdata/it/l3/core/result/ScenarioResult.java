package io.tapdata.it.l3.core.result;

import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Result of executing an entire scenario. Records final status,
 * overall timing, and the step that caused failure (if any).
 */
@Getter
@Builder
public class ScenarioResult {

    private final ScenarioConfig scenario;
    private final boolean success;
    private final StepResult failedStep;
    private final long durationMs;
    private final Instant startTime;
    private final Instant endTime;

    public static ScenarioResult success(ScenarioConfig scenario, long durationMs,
                                         Instant startTime, Instant endTime) {
        return ScenarioResult.builder()
                .scenario(scenario)
                .success(true)
                .failedStep(null)
                .durationMs(durationMs)
                .startTime(startTime)
                .endTime(endTime)
                .build();
    }

    public static ScenarioResult failed(ScenarioConfig scenario, StepResult failedStep,
                                        long durationMs, Instant startTime, Instant endTime) {
        return ScenarioResult.builder()
                .scenario(scenario)
                .success(false)
                .failedStep(failedStep)
                .durationMs(durationMs)
                .startTime(startTime)
                .endTime(endTime)
                .build();
    }
}
