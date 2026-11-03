package io.tapdata.it.l3.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A {@code parallel:} block — all steps inside run concurrently.
 * <p>
 * Hard rule enforced by DSL parser: no step inside this block may reference
 * outputs of another step ALSO inside this same block (cross-step independence).
 * Dependencies between steps must flow through sequential execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParallelBlock {

    /** All steps inside this parallel block — executed concurrently. */
    private List<StepConfig> steps;
}
