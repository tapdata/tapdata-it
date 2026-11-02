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
 * API: {@code POST /api/Inspects/execute?where={"id":"..."}}
 *
 * <pre>
 * - id: execute_inspect
 *   action: tm_execute_inspect
 *   timeout_seconds: 600
 *   inputs:
 *     inspect_id: "#{ #steps['create_inspect_task'].outputs['inspect_id'] }"
 * </pre>
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

        // Step 1: 执行校验
        Map<String, Object> where = Map.of("id", inspectId);
        // POST /api/Inspects/execute?where=...
        // where 是 JSON 字符串参数
        String whereJson = "{\"id\":\"" + inspectId + "\"}";
        Map<String, Object> queryParams = Map.of("where", whereJson);
        JsonNode resp = client.post("/api/Inspects/execute", queryParams, null);

        // Step 2: 等待完成
        // 轮询 inspect 状态
        log.info("[TM] Inspect {} started, waiting for completion...", inspectId);
        int pollInterval = 5000;
        long deadline = System.currentTimeMillis() + 5 * 60_000L; // 5min 默认
        String lastStatus = null;

        while (System.currentTimeMillis() < deadline) {
            JsonNode inspectInfo = client.get("/api/Inspects/find/" + inspectId);
            String currentStatus = text(inspectInfo, "status");
            if (currentStatus == null) currentStatus = text(inspectInfo, "inspectStatus");

            if (!currentStatus.equals(lastStatus)) {
                log.info("[TM] Inspect {} status: {}", inspectId, currentStatus);
                lastStatus = currentStatus;
            }

            // 完成状态
            if (isTerminalStatus(currentStatus)) {
                break;
            }

            Thread.sleep(pollInterval);
        }

        // Step 3: 获取最终结果
        JsonNode finalInfo = client.get("/api/Inspects/find/" + inspectId);
        String finalStatus = text(finalInfo, "status");
        if (finalStatus == null) finalStatus = text(finalInfo, "inspectStatus");

        // 解析校验结果 — 查找 total / mismatch 统计
        int totalTables = 0;
        int mismatchedTables = 0;
        long totalRows = 0;
        long mismatchedRows = 0;

        // 从 inspectResult 或者直接从 result 字段获取
        JsonNode result = finalInfo.has("inspectResult") ? finalInfo.get("inspectResult") : finalInfo;

        if (result.has("totalTableCount")) {
            totalTables = result.get("totalTableCount").asInt();
        }
        if (result.has("mismatchedTableCount")) {
            mismatchedTables = result.get("mismatchedTableCount").asInt();
        }
        if (result.has("totalRows")) {
            totalRows = result.get("totalRows").asLong();
        }
        if (result.has("mismatchedRows")) {
            mismatchedRows = result.get("mismatchedRows").asLong();
        }

        log.info("[TM] Inspect {} completed: status={}, totalTables={}, mismatchedTables={}, totalRows={}, mismatchedRows={}",
                inspectId, finalStatus, totalTables, mismatchedTables, totalRows, mismatchedRows);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("inspect_id", inspectId);
        outputs.put("status", finalStatus);
        outputs.put("total_tables", totalTables);
        outputs.put("mismatched_tables", mismatchedTables);
        outputs.put("total_rows", totalRows);
        outputs.put("mismatched_rows", mismatchedRows);
        outputs.put("success", mismatchedTables == 0 && mismatchedRows == 0);
        return success(outputs);
    }

    private boolean isTerminalStatus(String status) {
        if (status == null) return false;
        String s = status.toLowerCase();
        return s.contains("complete") || s.contains("success") || s.contains("fail")
                || s.contains("error") || s.contains("done") || s.contains("cancel");
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
