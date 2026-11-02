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
 * Poll a TM task until it reaches the target status or times out.
 * <p>
 * API: {@code GET /api/Task/{id}}
 *
 * <pre>
 * # 等待全量同步完成（任务状态变为 completed）
 * - id: wait_full_sync
 *   action: tm_wait_task
 *   timeout_seconds: 600
 *   inputs:
 *     task_id: "#{ #steps['create_sync_task'].outputs['task_id'] }"
 *     target_status: "completed"
 *     poll_interval_ms: 5000
 *     wait_timeout_seconds: 300
 *
 * # 或者等待任务进入 running 状态后再继续
 * - id: wait_task_running
 *   action: tm_wait_task
 *   inputs:
 *     task_id: "#{ #steps['create_sync_task'].outputs['task_id'] }"
 *     target_status: "running"
 *     wait_timeout_seconds: 60
 * </pre>
 *
 * Outputs: {@code task_id}, {@code final_status}, {@code reached_target}
 */
public class TmWaitTaskAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmWaitTaskAction.class);

    /** 终态 — 到达这些状态就停止等待 (无论 target_status 是什么). */
    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of(
            "completed", "stopped", "error", "terminated", "canceled"
    );

    private TmClient client;

    public TmWaitTaskAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_wait_task";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmTaskParameter p = config.getInputParameterConfig(TmTaskParameter.class);

        if (p.getTaskId() == null || p.getTaskId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: task_id");
        }

        String taskId = p.getTaskId();
        String targetStatus = p.getTargetStatus() != null ? p.getTargetStatus().toLowerCase() : "completed";
        int pollInterval = p.getPollIntervalMs() > 0 ? p.getPollIntervalMs() : 3000;
        int waitTimeout = p.getWaitTimeoutSeconds() > 0 ? p.getWaitTimeoutSeconds() : 300;

        log.info("[TM] Waiting for task {} to reach status '{}' (timeout={}s, poll={}ms)",
                taskId, targetStatus, waitTimeout, pollInterval);

        long deadline = System.currentTimeMillis() + waitTimeout * 1000L;
        String lastStatus = null;
        boolean reachedTarget = false;
        int attempts = 0;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                JsonNode taskInfo = client.get("/api/Task/" + taskId);
                String currentStatus = text(taskInfo, "flowStatus");
                if (currentStatus == null) currentStatus = text(taskInfo, "status");

                if (!currentStatus.equals(lastStatus)) {
                    log.info("[TM] Task {} status: {} → {}", taskId, lastStatus, currentStatus);
                    lastStatus = currentStatus;
                }

                if (targetStatus.equalsIgnoreCase(currentStatus)) {
                    reachedTarget = true;
                    log.info("[TM] Task {} reached target status '{}' after {} polls",
                            taskId, currentStatus, attempts);
                    break;
                }

                // 如果到达终态且不是目标状态，提前退出
                if (TERMINAL_STATUSES.contains(currentStatus != null ? currentStatus.toLowerCase() : "")) {
                    log.warn("[TM] Task {} reached terminal status '{}' which is NOT the target '{}'",
                            taskId, currentStatus, targetStatus);
                    break;
                }
            } catch (Exception e) {
                log.warn("[TM] Failed to poll task {} (attempt {}): {}", taskId, attempts, e.getMessage());
            }

            Thread.sleep(pollInterval);
        }

        if (!reachedTarget && lastStatus != null) {
            // 超时或提前退出 — 检查一下最终状态
            JsonNode finalInfo = client.get("/api/Task/" + taskId);
            lastStatus = text(finalInfo, "flowStatus");
            if (targetStatus.equalsIgnoreCase(lastStatus)) {
                reachedTarget = true;
            }
        }

        if (!reachedTarget) {
            log.warn("[TM] Task {} did NOT reach target '{}' within {}s — last status: {}",
                    taskId, targetStatus, waitTimeout, lastStatus);
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", taskId);
        outputs.put("final_status", lastStatus);
        outputs.put("reached_target", reachedTarget);
        outputs.put("attempts", attempts);
        return success(outputs);
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
