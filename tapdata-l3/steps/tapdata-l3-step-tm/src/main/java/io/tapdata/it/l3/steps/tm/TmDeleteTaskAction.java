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
 * Delete a TM task.
 * <p>
 * API: {@code DELETE /api/Task/{id}}
 *
 * <pre>
 * - id: delete_sync_task
 *   action: tm_delete_task
 *   inputs:
 *     task_id: "#{ #steps['create_sync_task'].outputs['task_id'] }"
 * </pre>
 *
 * Outputs: {@code task_id}, {@code deleted}
 */
public class TmDeleteTaskAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmDeleteTaskAction.class);

    private TmClient client;

    public TmDeleteTaskAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_delete_task";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmTaskParameter p = config.getInputParameterConfig(TmTaskParameter.class);

        if (p.getTaskId() == null || p.getTaskId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: task_id");
        }

        String taskId = p.getTaskId();
        log.info("[TM] Deleting task: {}", taskId);

        // DELETE /api/Task/{id}
        try {
            client.delete("/api/Task/" + taskId);
            log.info("[TM] Task {} deleted successfully", taskId);
        } catch (Exception e) {
            // 任务可能已经不存在了，这里不抛出
            log.warn("[TM] Task {} delete returned error (may already be deleted): {}", taskId, e.getMessage());
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", taskId);
        outputs.put("deleted", true);
        return success(outputs);
    }
}
