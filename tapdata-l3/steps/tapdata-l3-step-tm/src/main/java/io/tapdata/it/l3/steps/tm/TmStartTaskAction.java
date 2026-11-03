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
 * Start a TM task (execute the sync flow).
 * <p>
 * API: {@code PUT /api/Task/start/{id}}
 *
 * <pre>
 * - id: start_sync_task
 *   action: tm_start_task
 *   inputs:
 *     task_id: "#{ #steps['create_sync_task'].outputs['task_id'] }"
 * </pre>
 *
 * Outputs: {@code task_id}, {@code status}
 */
public class TmStartTaskAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmStartTaskAction.class);

    private TmClient client;

    public TmStartTaskAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_start_task";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmTaskParameter p = config.getInputParameterConfig(TmTaskParameter.class);

        if (p.getTaskId() == null || p.getTaskId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: task_id");
        }

        String taskId = p.getTaskId();
        log.info("[TM] Starting task: {}", taskId);

        // PUT /api/Task/start/{id} — no body needed
        JsonNode resp = client.put("/api/Task/start/" + taskId, null, null);

        // Verify it's now running — TM 用的是 "status" 不是 "flowStatus"
        JsonNode taskInfo = client.get("/api/Task/" + taskId);
        String status = text(taskInfo, "status");
        if (status == null) status = text(taskInfo, "flowStatus");
        log.info("[TM] Task {} started, current status={}", taskId, status);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", taskId);
        if (status != null) outputs.put("status", status);
        return success(outputs);
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
