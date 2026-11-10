package io.tapdata.it.l3.core.result;

import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Result of executing an entire scenario. Records final status,
 * overall timing, the step that caused failure (if any), and the full
 * per-step result list for reporting.
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
    /** All executed step results, in execution order (parallel blocks appended after completion). */
    private final List<StepResult> steps;

    public static ScenarioResult success(ScenarioConfig scenario, long durationMs,
                                         Instant startTime, Instant endTime) {
        return success(scenario, durationMs, startTime, endTime, List.of());
    }

    public static ScenarioResult success(ScenarioConfig scenario, long durationMs,
                                         Instant startTime, Instant endTime,
                                         List<StepResult> steps) {
        return ScenarioResult.builder()
                .scenario(scenario)
                .success(true)
                .failedStep(null)
                .durationMs(durationMs)
                .startTime(startTime)
                .endTime(endTime)
                .steps(steps)
                .build();
    }

    public static ScenarioResult failed(ScenarioConfig scenario, StepResult failedStep,
                                        long durationMs, Instant startTime, Instant endTime) {
        return failed(scenario, failedStep, durationMs, startTime, endTime, List.of());
    }

    public static ScenarioResult failed(ScenarioConfig scenario, StepResult failedStep,
                                        long durationMs, Instant startTime, Instant endTime,
                                        List<StepResult> steps) {
        return ScenarioResult.builder()
                .scenario(scenario)
                .success(false)
                .failedStep(failedStep)
                .durationMs(durationMs)
                .startTime(startTime)
                .endTime(endTime)
                .steps(steps)
                .build();
    }
}
