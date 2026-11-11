package io.tapdata.it.l3.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DSL {@code while:} block — re-evaluates {@code condition} before each iteration and
 * executes the nested blocks while it is truthy.
 * <pre>
 * - while:
 *     condition: "#{ #variables['retry'] &lt; 3 }"
 *     index_var: i            # optional
 *     max_iterations: 100     # optional safety guard, default 1000
 *     steps:
 *       - id: "poll_${i}"
 *         action: echo
 *         inputs:
 *           retry: "#{ #variables['i'] }"
 * </pre>
 * Semantics:
 * <ul>
 *   <li>{@code condition} is evaluated at the start of every iteration, so it can reference
 *       variables or step outputs updated by previous iterations.</li>
 *   <li>{@code index_var} (optional) is injected into {@link ScenarioContext} with the
 *       zero-based iteration index and supports {@code ${index_var}} template substitution
 *       in nested step ids / inputs / outputs / hooks.</li>
 *   <li>{@code max_iterations} prevents infinite loops — exceeding it fails the scenario
 *       instead of silently stopping.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhileBlock {

    /** Default safety guard when {@code max_iterations} is omitted. */
    public static final int DEFAULT_MAX_ITERATIONS = 1000;

    /** Loop condition — SpEL expression string or any value coerced to boolean. */
    private Object condition;

    /** Optional zero-based iteration index variable name. */
    private String indexVar;

    /** Hard iteration limit; exceeding it fails the scenario. */
    private int maxIterations;

    /** Nested blocks executed once per iteration while the condition holds. */
    private List<Object> blocks;
}
