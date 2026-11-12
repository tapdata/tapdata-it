package io.tapdata.it.l3.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DSL {@code switch:} block — executes the branch whose case key matches the evaluated expression.
 * <pre>
 * - switch:
 *     expression: "#{ #variables['db_type'] }"
 *     cases:
 *       mysql:
 *         - id: ...
 *           action: ...
 *       postgresql:
 *         - id: ...
 *           action: ...
 *     default:
 *       - id: ...
 *         action: ...
 * </pre>
 * Matching uses the string form of the evaluated value against case keys.
 * {@code default} is optional; when no case matches and no default exists, nothing runs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwitchBlock {

    /** Expression evaluated at runtime to select a case. */
    private Object expression;

    /** Case key → blocks. Keys are compared with {@code String.valueOf(value)}. */
    private Map<String, List<Object>> cases;

    /** Optional fallback blocks when no case matches. */
    private List<Object> defaultBlocks;
}
