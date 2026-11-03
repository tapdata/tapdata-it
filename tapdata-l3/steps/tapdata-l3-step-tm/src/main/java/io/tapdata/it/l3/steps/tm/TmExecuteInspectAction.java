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
 * Execute an existing TM Inspect task and wait for completion.
 * <p>
 * TM Inspect 状态机 (经 curl 实证):
 * <pre>
 *   create → scheduling → running → done | error
 * </pre>
 *
 * API: {@code POST /api/Inspects/execute?where={"id":"..."}}
 * Body 需要包含 {@code id} 和 {@code status: "scheduling"} 来触发调度.
 * 为了防止 TM 再次改 agentId, execute body 也带上 agentId.
 *
 * Outputs: {@code inspect_id}, {@code status}, {@code mismatched_tables}, {@code total_tables}
 */
public class TmExecuteInspectAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmExecuteInspectAction.class);

    private TmClient client;

    public TmExecuteInspectAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_execute_inspect";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmInspectParameter p = config.getInputParameterConfig(TmInspectParameter.class);

        String inspectId = p.getInspectId();
        if (inspectId == null || inspectId.isBlank()) {
            throw new IllegalArgumentException("Required input missing: inspect_id");
        }

        log.info("[TM] Executing Inspect task: {}", inspectId);

        // Step 1: 先获取当前 inspect 详情, 拿到 agentId (防止 execute 后 TM 改 agentId)
        JsonNode inspectBefore = client.get("/api/Inspects/find/" + inspectId);
        String originalAgentId = text(inspectBefore, "agentId");
        log.info("[TM] Inspect before execute: agentId={}", originalAgentId);

        // Step 2: 执行校验 — include agentId 防止 TM 重分配
        String whereJson = "{\"id\":\"" + inspectId + "\"}";
        Map<String, Object> queryParams = Map.of("where", whereJson);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", inspectId);
        body.put("status", "scheduling");
        if (originalAgentId != null && !originalAgentId.isBlank()) {
            body.put("agentId", originalAgentId);
        }
        JsonNode resp = client.post("/api/Inspects/execute", queryParams, body);
        log.debug("[TM] Inspect execute response: {}", resp);

        // Step 3: 轮询等待完成
        // TM 状态机: scheduling → running → done | error
        log.info("[TM] Inspect {} started, waiting for completion...", inspectId);
        int pollInterval = 5000;
        int waitTimeout = p.getWaitTimeoutSeconds() > 0 ? p.getWaitTimeoutSeconds() : 300;
        long deadline = System.currentTimeMillis() + waitTimeout * 1000L;
        String lastStatus = null;
        String lastAgentId = null;

        while (System.currentTimeMillis() < deadline) {
            JsonNode inspectInfo = client.get("/api/Inspects/find/" + inspectId);
            String currentStatus = text(inspectInfo, "status");
            String currentAgentId = text(inspectInfo, "agentId");

            if (!currentStatus.equals(lastStatus) ||
                    (currentAgentId != null && !currentAgentId.equals(lastAgentId))) {
                log.info("[TM] Inspect {} status: {} (agentId={})", inspectId, currentStatus, currentAgentId);
                lastStatus = currentStatus;
                lastAgentId = currentAgentId;
            }

            if (isTerminalStatus(currentStatus)) {
                break;
            }

            Thread.sleep(pollInterval);
        }

        // Step 4: 获取最终结果 + debug dump if error
        JsonNode finalInfo = client.get("/api/Inspects/find/" + inspectId);
        String finalStatus = text(finalInfo, "status");
        String errorMsg = text(finalInfo, "errorMsg");
        String result = text(finalInfo, "result");

        log.info("[TM] Inspect {} final: status={}, result={}, errorMsg={}", inspectId, finalStatus, result, errorMsg);

        if ("error".equalsIgnoreCase(finalStatus)) {
            // Debug dump — 帮助排查
            log.error("[TM] === INSPECT FAILED DEBUG DUMP ===");
            log.error("[TM] Full inspect detail: {}", finalInfo);
            String flowId = text(finalInfo, "flowId");
            if (flowId != null) {
                try {
                    JsonNode taskInfo = client.get("/api/Task/" + flowId);
                    log.error("[TM] Related task detail: {}", taskInfo);
                } catch (Exception ignored) {}
            }
            throw new IllegalStateException("Inspect " + inspectId + " failed: status=" + finalStatus
                    + ", errorMsg=" + errorMsg + ", fullDetail=" + finalInfo);
        }

        // Step 5: 解析校验结果 — 从 tasks[].result 或顶层 result 字段
        int totalTables = 0;
        int mismatchedTables = 0;
        long totalRows = 0;
        long mismatchedRows = 0;

        // 方式 A: 从 tasks[] 数组解析 (TM 真实结构)
        JsonNode tasks = finalInfo.get("tasks");
        if (tasks != null && tasks.isArray()) {
            for (JsonNode t : tasks) {
                totalTables++;
                String taskResult = text(t, "result");
                if ("failed".equalsIgnoreCase(taskResult)) {
                    mismatchedTables++;
                }
                // 每个 task 可能有自己的 row 统计
                if (t.has("mismatchedRows")) mismatchedRows += t.get("mismatchedRows").asLong();
                if (t.has("totalRows")) totalRows += t.get("totalRows").asLong();
            }
        }

        // 方式 B: 从顶层 result 字段
        if (mismatchedTables == 0 && "failed".equalsIgnoreCase(result)) {
            mismatchedTables = totalTables;
        }

        // 方式 C: 从 result JSON (如果 result 是一个对象字符串)
        if (result != null && result.startsWith("{")) {
            try {
                JsonNode resultJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
                if (resultJson.has("totalTableCount")) totalTables = resultJson.get("totalTableCount").asInt();
                if (resultJson.has("mismatchedTableCount")) mismatchedTables = resultJson.get("mismatchedTableCount").asInt();
                if (resultJson.has("totalRows")) totalRows = resultJson.get("totalRows").asLong();
                if (resultJson.has("mismatchedRows")) mismatchedRows = resultJson.get("mismatchedRows").asLong();
            } catch (Exception ignored) {}
        }

        boolean success = mismatchedTables == 0 && mismatchedRows == 0
                && !"failed".equalsIgnoreCase(result);

        log.info("[TM] Inspect {} completed: status={}, result={}, totalTables={}, mismatchedTables={}, totalRows={}, mismatchedRows={}, success={}",
                inspectId, finalStatus, result, totalTables, mismatchedTables, totalRows, mismatchedRows, success);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("inspect_id", inspectId);
        outputs.put("status", finalStatus);
        outputs.put("result", result);
        outputs.put("total_tables", totalTables);
        outputs.put("mismatched_tables", mismatchedTables);
        outputs.put("total_rows", totalRows);
        outputs.put("mismatched_rows", mismatchedRows);
        outputs.put("success", success);
        return success(outputs);
    }

    private boolean isTerminalStatus(String status) {
        if (status == null) return false;
        String s = status.toLowerCase();
        return s.contains("done") || s.contains("complete") || s.contains("success")
                || s.contains("fail") || s.contains("error") || s.contains("cancel");
    }

    private static String text(JsonNode n, String key) {
        return n != null && n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
