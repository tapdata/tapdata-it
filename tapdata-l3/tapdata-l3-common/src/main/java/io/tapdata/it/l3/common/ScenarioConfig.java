package io.tapdata.it.l3.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * In-memory representation of a parsed DSL YAML scenario.
 * <p>
 * {@code blocks} is the core execution sequence — each element can be:
 * <ul>
 *   <li>a {@link StepConfig} — single sequential step</li>
 *   <li>a {@link ParallelBlock} — multiple steps run concurrently</li>
 * </ul>
 * Foreach (C2) and other control structures are added later as dedicated classes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioConfig {

    /** Wiki case id (e.g. "L3-F-01") — must be unique across scenarios. */
    private String caseId;

    /** Display name — shown in CLI output and reports. */
    private String scenario;

    /** Group: functional / destructive / stability — determines env isolation level. */
    private String group;

    /** Optional description. */
    private String description;

    /** Scenario-scoped environment variables — injected into System Properties + ScenarioContext.
     *  Used for cross-step shared config like TM_BASE_URL, DB_TYPE, etc.
     *  In ActionHandler impls: System.getProperty("TM_BASE_URL") or SpEL #{ #env['TM_BASE_URL'] } */
    private Map<String, String> env;

    /** User-declared variables — evaluated at scenario start, available in SpEL via #{ #variables['key'] }. */
    private Map<String, Object> variables;

    /** Execution blocks — each is either StepConfig or ParallelBlock (C2 adds ForeachBlock). */
    private List<Object> blocks;
}
