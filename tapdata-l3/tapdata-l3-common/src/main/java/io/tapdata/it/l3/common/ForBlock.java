package io.tapdata.it.l3.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DSL {@code for:} block — iterates a collection, executing the nested blocks once per item.
 * <pre>
 * - for:
 *     var: sink_idx
 *     index_var: i            # optional
 *     items: [1, 2, 3]        # literal list or SpEL expression
 *     steps:
 *       - id: "create_sink_${sink_idx}"
 *         action: dbforge_create_database
 *         inputs:
 *           suffix: "${sink_idx}"
 * </pre>
 * Inside each iteration:
 * <ul>
 *   <li>{@code var} / {@code index_var} are injected into {@link ScenarioContext} variables,
 *       readable via {@code #{ #variables['sink_idx'] }}</li>
 *   <li>{@code ${sink_idx}} / {@code ${i}} placeholders are substituted in nested step ids,
 *       inputs, outputs and hooks (so generated step ids stay unique per iteration)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForBlock {

    /** Loop variable name injected into ScenarioContext variables. */
    private String var;

    /** Optional zero-based index variable name. */
    private String indexVar;

    /** Iteration source — a literal List or a SpEL expression evaluating to a collection. */
    private Object items;

    /** Nested blocks executed once per item. */
    private List<Object> blocks;
}
