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
 * Locate which engine (worker) a TM task is currently running on.
 * <p>
 * 查询链路: {@code GET /api/Task/{id}} → {@code agentId} (worker process_id)
 * → {@code GET /api/Workers?filter={"where":{"process_id":"..."}}} → worker {@code hostname}。
 * 在 K8s 部署下，worker 的 hostname 就是 engine Pod 名，可直接用于 kubectl delete pod 制造故障。
 *
 * <pre>
 * - id: locate_engine
 *   action: tm_get_task_engine
 *   inputs:
 *     task_id: "#{ #steps['create_task'].outputs['task_id'] }"
 *   outputs: [agent_id, hostname, worker_status]
 * </pre>
 *
 * Outputs: {@code task_id}, {@code agent_id}, {@code hostname}, {@code worker_status}, {@code worker_ping_time}
 */
public class TmGetTaskEngineAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmGetTaskEngineAction.class);

    private TmClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public TmGetTaskEngineAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_get_task_engine";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmGetTaskParameter p = config.getInputParameterConfig(TmGetTaskParameter.class);
        if (p.getTaskId() == null || p.getTaskId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: task_id");
        }

        JsonNode task = client.get("/api/Task/" + p.getTaskId());
        String agentId = text(task, "agentId");
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException("Task " + p.getTaskId() + " has no agentId — not scheduled to any engine yet?");
        }

        JsonNode worker = findWorkerByProcessId(agentId);
        String hostname = worker != null ? text(worker, "hostname") : null;
        String workerStatus = worker != null ? text(worker, "status") : null;
        Long workerPing = worker != null && worker.has("ping_time") && !worker.get("ping_time").isNull()
                ? worker.get("ping_time").asLong() : null;

        if (hostname == null || hostname.isBlank()) {
            throw new IllegalStateException("Worker with process_id=" + agentId
                    + " not found (or has no hostname) via /api/Workers");
        }

        log.info("[TM] Task {} runs on engine agentId={}, hostname(pod)={}, workerStatus={}",
                p.getTaskId(), agentId, hostname, workerStatus);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", p.getTaskId());
        outputs.put("agent_id", agentId);
        outputs.put("hostname", hostname);
        outputs.put("worker_status", workerStatus);
        outputs.put("worker_ping_time", workerPing);
        return success(outputs);
    }

    /** 按 process_id 精确查询 worker；找不到时回退全量列表客户端匹配；仍找不到返回 null。 */
    private JsonNode findWorkerByProcessId(String processId) throws Exception {
        String filter = URLEncoder.encode("{\"where\":{\"process_id\":\"" + processId + "\"}}",
                StandardCharsets.UTF_8);
        JsonNode data = client.get("/api/Workers?filter=" + filter);
        JsonNode arr = data != null && data.has("items") ? data.get("items") : data;
        if (arr != null && arr.isArray()) {
            for (JsonNode w : arr) {
                if (processId.equals(text(w, "process_id"))) return w;
            }
            if (arr.size() > 0) return arr.get(0); // filter 生效但字段名可能不同 — 兜底取首个
        }
        // 兜底：不带 filter 全量拉取，客户端按 process_id/processId 匹配 + 诊断日志
        JsonNode all = client.get("/api/Workers");
        JsonNode allArr = all != null && all.has("items") ? all.get("items") : all;
        if (allArr != null && allArr.isArray()) {
            StringBuilder known = new StringBuilder();
            for (JsonNode w : allArr) {
                String pid = text(w, "process_id") != null ? text(w, "process_id") : text(w, "processId");
                known.append(pid).append('(').append(text(w, "hostname")).append(") ");
                if (processId.equals(pid)) return w;
            }
            log.warn("[TM] /api/Workers has {} worker(s) but none matches process_id={}: known = {}",
                    allArr.size(), processId, known);
        }
        return null;
    }

    private static String text(JsonNode n, String key) {
        return n != null && n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
