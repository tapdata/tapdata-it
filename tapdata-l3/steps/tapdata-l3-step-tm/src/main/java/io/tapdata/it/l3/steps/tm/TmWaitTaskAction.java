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
            "complete", "completed", "stopped", "error", "terminated", "canceled"
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

    /** 建议超时 = wait_timeout_seconds + 60s 余量（task 轮询等待的内部上限）。 */
    @Override
    public int getRecommendedTimeoutSeconds(StepConfig config) {
        Object raw = config.getInputs() == null ? null : config.getInputs().get("wait_timeout_seconds");
        int waitTimeout = raw instanceof Number n ? n.intValue() : 300;
        return waitTimeout + 60;
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
        String lastMilestoneSummary = "";
        boolean reachedTarget = false;
        int attempts = 0;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                JsonNode taskInfo = client.get("/api/Task/" + taskId);
                // TM 任务状态字段优先级: flowStatus → status → syncStatus
                String currentStatus = text(taskInfo, "flowStatus");
                if (currentStatus == null) currentStatus = text(taskInfo, "status");
                if (currentStatus == null) currentStatus = text(taskInfo, "syncStatus");

                // Milestone 进度摘要 — 每次轮询都打印
                String milestoneSummary = buildMilestoneSummary(taskInfo);
                boolean statusChanged = !java.util.Objects.equals(currentStatus, lastStatus);
                boolean milestoneChanged = !milestoneSummary.equals(lastMilestoneSummary);

                if (statusChanged || milestoneChanged) {
                    log.info("[TM] Task {} status: {} → {} | milestones: [{}]",
                            taskId, lastStatus, currentStatus, milestoneSummary);
                    lastStatus = currentStatus;
                    lastMilestoneSummary = milestoneSummary;
                }

                if (targetStatus.equalsIgnoreCase(currentStatus)) {
                    reachedTarget = true;
                    log.info("[TM] Task {} reached target status '{}' after {} polls | milestones: [{}]",
                            taskId, currentStatus, attempts, milestoneSummary);
                    break;
                }

                // Status field may be "running" for migrate tasks with CDC already active —
                // fall back to milestone-based detection: if SNAPSHOT+CDC both FINISH → treat as full sync done.
                // Also handles status=null (API may omit the field in some TM versions).
                boolean statusAbsent = currentStatus == null || currentStatus.isBlank();
                boolean statusRunning = "running".equalsIgnoreCase(currentStatus);
                if ((statusAbsent || statusRunning) && ("complete".equalsIgnoreCase(targetStatus) || "completed".equalsIgnoreCase(targetStatus))) {
                    if (isMilestoneSnapshotCdcFinished(taskInfo)) {
                        reachedTarget = true;
                        log.info("[TM] Task {}: milestone SNAPSHOT✓+CDC✓ (status={}) → treating as full sync complete (after {} polls)",
                                taskId, currentStatus, attempts);
                        break;
                    }
                }

                // If no status AND no milestone data yet, just keep polling silently

                // 如果到达终态且不是目标状态，提前退出
                String currentLower = currentStatus != null ? currentStatus.toLowerCase() : "";
                if (TERMINAL_STATUSES.contains(currentLower)) {
                    log.warn("[TM] Task {} reached terminal status '{}' which is NOT the target '{}'",
                            taskId, currentStatus, targetStatus);
                    // Debug dump — 帮助快速定位
                    log.error(client.dumpTask(taskId));
                    break;
                }

                // 慢速轮询时每 N 次也打印一次 milestone 进度（即便没变化）
                if (attempts % 10 == 0) {
                    log.debug("[TM] Task {} (poll {}) status={} | milestones: [{}]",
                            taskId, attempts, currentStatus, milestoneSummary);
                }
            } catch (Exception e) {
                log.warn("[TM] Failed to poll task {} (attempt {}): {}", taskId, attempts, e.getMessage());
            }

            Thread.sleep(pollInterval);
        }

        if (!reachedTarget) {
            // 超时或提前退出 — 做最终检查 (同时检查 flowStatus 和 status 两个字段)
            JsonNode finalInfo = client.get("/api/Task/" + taskId);
            String finalFlowStatus = text(finalInfo, "flowStatus");
            String finalStatusField = text(finalInfo, "status");
            String finalStatus = finalFlowStatus != null ? finalFlowStatus : finalStatusField;

            if (finalStatus != null && targetStatus.equalsIgnoreCase(finalStatus)) {
                reachedTarget = true;
            }

            if (!reachedTarget && finalStatus != null) {
                String finalLower = finalStatus.toLowerCase();
                if (TERMINAL_STATUSES.contains(finalLower)) {
                    log.error(client.dumpTask(taskId));
                    throw new IllegalStateException("Task " + taskId + " reached terminal status '" + finalStatus
                            + "' which is NOT the target '" + targetStatus + "'");
                }
            }
        }

        // Build outputs — needed before early-return failure path
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

    /**
     * Fallback: check if milestone SNAPSHOT and CDC are both FINISH.
     * Used when TM API returns no top-level status field (observed in this env).
     */
    private static boolean isMilestoneSnapshotCdcFinished(JsonNode taskInfo) {
        JsonNode milestone = taskInfo.path("attrs").path("milestone");
        if (!milestone.isObject() || milestone.size() == 0) return false;
        JsonNode snap = milestone.path("SNAPSHOT");
        JsonNode cdc = milestone.path("CDC");
        String snapStatus = snap.has("status") ? snap.get("status").asText() : null;
        String cdcStatus = cdc.has("status") ? cdc.get("status").asText() : null;
        // Either CDC is FINISH, or CDC is absent (pure snapshot task)
        boolean cdcOk = cdcStatus == null || "FINISH".equalsIgnoreCase(cdcStatus);
        return "FINISH".equalsIgnoreCase(snapStatus) && cdcOk;
    }

    /**
     * 从 Task JSON 中提取 milestone 进度的紧凑摘要。
     * <p>
     * TM 任务的 milestone 通常包括以下阶段（按顺序）：
     * TASK → DEDUCTION → TABLE_INIT → DATA_NODE_INIT → SNAPSHOT → CDC
     * <p>
     * 输出格式示例: "TASK✓ DEDUCTION✓ TABLE_INIT⏳ DATA_NODE_INIT⏳ SNAPSHOT⏳ CDC⏳"
     */
    private String buildMilestoneSummary(JsonNode taskInfo) {
        StringBuilder sb = new StringBuilder();
        JsonNode milestone = taskInfo.path("attrs").path("milestone");
        if (!milestone.isObject() || milestone.size() == 0) {
            // 尝试从顶层 statuses 字段获取
            JsonNode statuses = taskInfo.path("statuses");
            if (statuses.isObject()) {
                milestone = statuses;
            } else {
                return "(no milestone data)";
            }
        }

        // 标准 milestone 顺序
        java.util.List<String> order = java.util.List.of(
                "TASK", "DEDUCTION", "TABLE_INIT", "DATA_NODE_INIT", "SNAPSHOT", "CDC"
        );

        for (String stage : order) {
            JsonNode node = milestone.get(stage);
            if (node == null) continue;
            String status = text(node, "status");
            if (status == null) continue;
            String mark = switch (status.toUpperCase()) {
                case "FINISH" -> "✓";
                case "RUNNING", "EXECUTING" -> "▶";
                case "WAITING", "PENDING" -> "⏳";
                case "ERROR", "FAILED" -> "✗";
                case "SKIP" -> "⊘";
                default -> status;
            };
            if (sb.length() > 0) sb.append(' ');
            sb.append(stage).append(mark);
        }

        // 额外非标准 milestone 也追加（按字母序）
        milestone.fields().forEachRemaining(entry -> {
            if (order.contains(entry.getKey())) return;
            String status = text(entry.getValue(), "status");
            if (status == null) return;
            if (sb.length() > 0) sb.append(' ');
            sb.append(entry.getKey()).append('=').append(status);
        });

        return sb.length() > 0 ? sb.toString() : "(empty)";
    }
}
