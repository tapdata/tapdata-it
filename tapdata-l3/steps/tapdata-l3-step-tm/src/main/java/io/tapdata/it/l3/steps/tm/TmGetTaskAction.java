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
        TmGetTaskParameter p = config.getInputParameterConfig(TmGetTaskParameter.class);
        if (p.getTaskId() == null || p.getTaskId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: task_id");
        }
        log.info("[TM] Fetching Task {}", p.getTaskId());
        JsonNode task = client.get("/api/Task/" + p.getTaskId());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", text(task, "id"));
        outputs.put("name", text(task, "name"));
        outputs.put("type", text(task, "type"));
        outputs.put("status", text(task, "status"));
        outputs.put("current_step", text(task, "currentStep"));
        outputs.put("source_connection_id", text(task, "sourceConnectionId"));
        outputs.put("target_connection_id", text(task, "targetConnectionId"));
        // 当前运行该任务的 engine (worker process_id) + 任务心跳时间戳 — HA 故障转移场景使用
        outputs.put("agent_id", text(task, "agentId"));
        outputs.put("ping_time", task.has("pingTime") && !task.get("pingTime").isNull()
                ? task.get("pingTime").asLong() : null);

        log.info("[TM] Task {}: name={}, status={}, type={}, agentId={}", p.getTaskId(),
                outputs.get("name"), outputs.get("status"), outputs.get("type"), outputs.get("agent_id"));
        return success(outputs);
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
