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
 * Get the current status of a TM Task.
 *
 * <pre>
 * - id: check_task
 *   action: tm_get_task
 *   inputs:
 *     task_id: "#{ #steps['create_task'].outputs['task_id'] }"
 * </pre>
 *
 * Outputs: {@code task_id}, {@code name}, {@code type}, {@code status}, {@code current_step}, {@code progress}
 */
public class TmGetTaskAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmGetTaskAction.class);

    private TmClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public TmGetTaskAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_get_task";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        Map<String, Object> inputs = config.getInputs();
        String taskId = required(inputs, "task_id");
        log.info("[TM] Fetching Task {}", taskId);
        JsonNode task = client.get("/api/Task/" + taskId);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", text(task, "id"));
        outputs.put("name", text(task, "name"));
        outputs.put("type", text(task, "type"));
        outputs.put("status", text(task, "status"));
        outputs.put("current_step", text(task, "currentStep"));
        outputs.put("source_connection_id", text(task, "sourceConnectionId"));
        outputs.put("target_connection_id", text(task, "targetConnectionId"));

        log.info("[TM] Task {}: name={}, status={}, type={}", taskId,
                outputs.get("name"), outputs.get("status"), outputs.get("type"));
        return success(outputs);
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }

    private static String required(Map<String, Object> inputs, String key) {
        Object v = inputs.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Required input missing: " + key);
        }
        return v.toString();
    }
}
