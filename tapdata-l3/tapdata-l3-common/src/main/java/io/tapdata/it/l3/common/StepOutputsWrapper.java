package io.tapdata.it.l3.common;

import java.util.Map;

/**
 * Wrapper that exposes {@code .outputs['key']} navigation for SpEL expressions.
 * <p>
 * The raw stepOutputs map is {@code Map<String, Map<String, Object>>} where values are the
 * outputs maps themselves. But DSL writers use the syntax:
 * <pre>
 * #steps['create_src_db'].outputs['src_db_uri']
 * </pre>
 * So each stepId's value needs to be wrapped in an object that has an {@code outputs} property.
 * This wrapper does exactly that.
 */
public class StepOutputsWrapper {

    private final Map<String, Object> outputs;

    public StepOutputsWrapper(Map<String, Object> outputs) {
        this.outputs = outputs;
    }

    /** Property accessor used by SpEL — makes {@code .outputs['key']} resolve. */
    public Map<String, Object> getOutputs() {
        return outputs;
    }
}
