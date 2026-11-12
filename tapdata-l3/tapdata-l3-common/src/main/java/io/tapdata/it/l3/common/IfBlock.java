package io.tapdata.it.l3.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DSL {@code if:} block — executes exactly one of two branch block lists.
 * <pre>
 * - if:
 *     condition: "#{ #variables['enable_cdc'] }"
 *     then:
 *       - id: ...
 *         action: ...
 *     else:
 *       - id: ...
 *         action: ...
 * </pre>
 * {@code condition} is evaluated at runtime (SpEL {@code #{ ... }} supported);
 * a plain {@code true}/{@code false} literal is also accepted. {@code else} is optional.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IfBlock {

    /** Condition — SpEL expression string, boolean, or any value coerced to boolean. */
    private Object condition;

    /** Blocks executed when the condition is truthy. */
    private List<Object> thenBlocks;

    /** Optional blocks executed when the condition is falsy. */
    private List<Object> elseBlocks;
}
