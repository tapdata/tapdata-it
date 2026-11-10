package io.tapdata.it.l3.core.step;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.core.console.ConsoleUI;
import io.tapdata.it.l3.plugin.step.BaseStep;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Echo step — prints a message and optionally stores it as step output.
 * Inherits async timeout from BaseStep.
 */
@Component
public class EchoAction extends BaseStep {

    @Override
    public String getActionName() {
        return "echo";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        Map<String, Object> inputs = config.getInputs();
        String message = inputs.getOrDefault("message", "").toString();
        ConsoleUI.event("echo", message);

        // Build outputs
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("echoed", message);
        // Also expose each input as named output for downstream reference
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            outputs.put(entry.getKey(), entry.getValue());
        }

        return success(outputs);
    }
}
