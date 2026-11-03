package io.tapdata.it.l3.core.step;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.BaseStep;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Sleep step — pauses for N seconds. Used to validate timeout behavior.
 * Inherits async timeout from BaseStep — declare timeout_seconds < seconds to test orTimeout.
 */
@Component
public class SleepAction extends BaseStep {

    @Override
    public String getActionName() {
        return "sleep";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        Map<String, Object> inputs = config.getInputs();
        Object secondsRaw = inputs.get("seconds");
        int seconds = secondsRaw instanceof Number n ? n.intValue() : 1;
        Thread.sleep(seconds * 1000L);
        return success();
    }
}
