package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stop a running TM task.
 * <p>
 * API: {@code PUT /api/Task/stop/{id}}
 *
 * <pre>
 * - id: stop_sync_task
 *   action: tm_stop_task
 *   inputs:
 *     task_id: "#{ #steps['create_sync_task'].outputs['task_id'] }"
 * </pre>
 *
 * Outputs: {@code task_id}, {@code status}
 */
public class TmStopTaskAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmStopTaskAction.class);

    private TmClient client;

    public TmStopTaskAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_stop_task";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmTaskParameter p = config.getInputParameterConfig(TmTaskParameter.class);

        if (p.getTaskId() == null || p.getTaskId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: task_id");
        }

        String taskId = p.getTaskId();
        log.info("[TM] Stopping task: {}", taskId);

        // PUT /api/Task/stop/{id}
        JsonNode resp = client.put("/api/Task/stop/" + taskId, null, null);

        // Confirm stopped
        JsonNode taskInfo = client.get("/api/Task/" + taskId);
        String status = text(taskInfo, "flowStatus");
        log.info("[TM] Task {} stopped, current status={}", taskId, status);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", taskId);
        outputs.put("status", status);
        return success(outputs);
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
