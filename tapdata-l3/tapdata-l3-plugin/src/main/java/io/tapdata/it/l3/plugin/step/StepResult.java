package io.tapdata.it.l3.plugin.step;

import io.tapdata.it.l3.common.StepConfig;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Result of executing a single step.
 * <p>
 * Carries success/failure status, timing, optional outputs (for BaseStep auto-registration),
 * and the exception that caused failure.
 * <p>
 * Lives in {@code tapdata-l3-plugin} so external step libraries (which only depend on plugin)
 * can return typed results from their {@link BaseStep#doExecute} implementation.
 */
@Getter
@Builder
public class StepResult {

    /** May be null when constructed by BaseStep convenience methods. */
    private final StepConfig stepConfig;
    private final boolean success;
    private final Throwable error;
    private final long durationMs;
    /** Optional outputs map — BaseStep writes these to ScenarioContext on success. */
    private final Map<String, Object> outputs;

    // Factory used by StepRunner (captures timing + stepConfig)
    public static StepResult success(StepConfig stepConfig, long durationMs) {
        return success(stepConfig, durationMs, Map.of());
    }

    public static StepResult success(StepConfig stepConfig, long durationMs,
                                     Map<String, Object> outputs) {
        return StepResult.builder()
                .stepConfig(stepConfig)
                .success(true)
                .durationMs(durationMs)
                .outputs(outputs)
                .build();
    }

    public static StepResult failure(StepConfig stepConfig, Throwable error, long durationMs) {
        return failure(stepConfig, error, durationMs, Map.of());
    }

    public static StepResult failure(StepConfig stepConfig, Throwable error, long durationMs,
                                     Map<String, Object> outputs) {
        return StepResult.builder()
                .stepConfig(stepConfig)
                .success(false)
                .error(error)
                .durationMs(durationMs)
                .outputs(outputs)
                .build();
    }
}
