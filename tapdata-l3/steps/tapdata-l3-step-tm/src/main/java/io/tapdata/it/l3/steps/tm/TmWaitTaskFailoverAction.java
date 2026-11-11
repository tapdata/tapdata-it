package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wait until a TM task is rescheduled away from its previous engine (HA failover).
 * <p>
 * 故障转移依赖任务心跳超时: 运行任务的 engine 停止后, 任务 pingTime 不再更新,
 * TM 在心跳超时 (Settings 中 jobHeartTimeout × 2) 后把任务重新调度到其他存活 engine。
 * 因此 {@code wait_timeout_seconds} 必须大于任务心跳超时时间。
 *
 * <pre>
 * - id: wait_failover
 *   action: tm_wait_task_failover
 *   inputs:
 *     task_id: "#{ #steps['create_task'].outputs['task_id'] }"
 *     previous_agent_id: "#{ #steps['locate_engine'].outputs['agent_id'] }"
 *     wait_timeout_seconds: 300
 *     poll_interval_ms: 5000
 *   outputs: [new_agent_id, new_hostname, elapsed_seconds]
 * </pre>
 *
 * Outputs: {@code task_id}, {@code previous_agent_id}, {@code new_agent_id},
 *          {@code new_hostname}, {@code elapsed_seconds}, {@code attempts}
 */
public class TmWaitTaskFailoverAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmWaitTaskFailoverAction.class);

    private TmClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public TmWaitTaskFailoverAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_wait_task_failover";
    }

    /** 建议超时 = wait_timeout_seconds + 120s 余量（杀 pod + 重调度 + 任务重跑的内部上限更长）。 */
    @Override
    public int getRecommendedTimeoutSeconds(StepConfig config) {
        Object raw = config.getInputs() == null ? null : config.getInputs().get("wait_timeout_seconds");
        int waitTimeout = raw instanceof Number n ? n.intValue() : 300;
        return waitTimeout + 120;
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmWaitTaskFailoverParameter p = config.getInputParameterConfig(TmWaitTaskFailoverParameter.class);
        if (p.getTaskId() == null || p.getTaskId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: task_id");
        }
        if (p.getPreviousAgentId() == null || p.getPreviousAgentId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: previous_agent_id");
        }

        int waitTimeout = p.getWaitTimeoutSeconds() > 0 ? p.getWaitTimeoutSeconds() : 300;
        int pollInterval = p.getPollIntervalMs() > 0 ? p.getPollIntervalMs() : 5000;

        log.info("[TM] Waiting for task {} to fail over from agent {} (timeout={}s, poll={}ms)",
                p.getTaskId(), p.getPreviousAgentId(), waitTimeout, pollInterval);

        long start = System.currentTimeMillis();
        long deadline = start + waitTimeout * 1000L;
        int attempts = 0;
        String newAgentId = null;
        String lastLog = null;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                JsonNode task = client.get("/api/Task/" + p.getTaskId());
                String agentId = text(task, "agentId");
                String status = text(task, "status");
                Long pingTime = task.has("pingTime") && !task.get("pingTime").isNull()
                        ? task.get("pingTime").asLong() : null;
                long staleSec = pingTime != null ? (System.currentTimeMillis() - pingTime) / 1000 : -1;

                String summary = String.format("agentId=%s status=%s pingStale=%ds",
                        agentId, status, staleSec);
                if (!summary.equals(lastLog)) {
                    log.info("[TM] Task {} failover poll #{}: {}", p.getTaskId(), attempts, summary);
                    lastLog = summary;
                }

                if (agentId != null && !agentId.isBlank() && !agentId.equals(p.getPreviousAgentId())) {
                    newAgentId = agentId;
                    break;
                }
            } catch (Exception e) {
                log.warn("[TM] Failed to poll task {} (attempt {}): {}", p.getTaskId(), attempts, e.getMessage());
            }
            Thread.sleep(pollInterval);
        }

        if (newAgentId == null) {
            try {
                log.error(client.dumpTask(p.getTaskId()));
            } catch (Exception ignored) {}
            throw new IllegalStateException("Task " + p.getTaskId() + " was NOT rescheduled off agent "
                    + p.getPreviousAgentId() + " within " + waitTimeout
                    + "s — check jobHeartTimeout setting and surviving engine status");
        }

        long elapsedSec = (System.currentTimeMillis() - start) / 1000;

        // 解析新 engine 的 hostname (K8s 下即 pod 名)，找不到不视为失败
        String newHostname = null;
        try {
            String filter = URLEncoder.encode("{\"where\":{\"process_id\":\"" + newAgentId + "\"}}",
                    StandardCharsets.UTF_8);
            JsonNode workers = client.get("/api/Workers?filter=" + filter);
            JsonNode arr = workers != null && workers.has("items") ? workers.get("items") : workers;
            if (arr != null && arr.isArray() && arr.size() > 0) {
                newHostname = text(arr.get(0), "hostname");
            }
        } catch (Exception e) {
            log.warn("[TM] Failed to resolve hostname for new agent {}: {}", newAgentId, e.getMessage());
        }

        log.info("[TM] Task {} failed over: {} → {} (hostname={}) after {}s / {} polls",
                p.getTaskId(), p.getPreviousAgentId(), newAgentId, newHostname, elapsedSec, attempts);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", p.getTaskId());
        outputs.put("previous_agent_id", p.getPreviousAgentId());
        outputs.put("new_agent_id", newAgentId);
        outputs.put("new_hostname", newHostname);
        outputs.put("elapsed_seconds", elapsedSec);
        outputs.put("attempts", attempts);
        return success(outputs);
    }

    private static String text(JsonNode n, String key) {
        return n != null && n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
