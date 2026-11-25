package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.exception.AssertionFailedException;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assert that the most recent Inspect result meets expectations.
 * <p>
 * Used AFTER {@code tm_execute_inspect} — polls the inspect result and throws
 * {@link AssertionFailedException} if mismatches exceed the threshold.
 *
 * <pre>
 * # 严格模式 — 必须完全一致
 * - id: assert_inspect_result
 *   action: tm_assert_inspect_result
 *   inputs:
 *     inspect_id: "#{ #steps['create_inspect_task'].outputs['inspect_id'] }"
 *
 * # 宽松模式 — 允许最多 100 行不一致
 * - id: assert_inspect_soft
 *   action: tm_assert_inspect_result
 *   inputs:
 *     inspect_id: "#{ #steps['create_inspect_task'].outputs['inspect_id'] }"
 *     max_mismatch_rows: 100
 * </pre>
 *
 * Outputs: {@code inspect_id}, {@code assert_passed}, {@code mismatched_rows}
 */
public class TmAssertInspectResultAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmAssertInspectResultAction.class);

    private TmClient client;

    public TmAssertInspectResultAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_assert_inspect_result";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmInspectParameter p = config.getInputParameterConfig(TmInspectParameter.class);

        String inspectId = p.getInspectId();
        if (inspectId == null || inspectId.isBlank()) {
            throw new IllegalArgumentException("Required input missing: inspect_id");
        }

        boolean allowMismatch = p.isAllowMismatch();
        long maxMismatchRows = p.getMaxMismatchRows() != null ? p.getMaxMismatchRows() : 0L;

        log.info("[TM] Asserting Inspect result: inspect_id={}, allowMismatch={}, maxMismatchRows={}",
                inspectId, allowMismatch, maxMismatchRows);

        JsonNode inspectInfo = client.get("/api/Inspects/find/" + inspectId);

        // 校验任务必须已经跑完 —— 否则"无差异"只是查无结果，不能当成数据一致（假通过）。
        String inspectStatus = text(inspectInfo, "status");
        if (!isFinished(inspectStatus)) {
            throw new AssertionFailedException("Inspect " + inspectId
                    + " has not finished yet (status=" + inspectStatus + "); cannot assert consistency");
        }

        // 结果落点有两种：
        // 1) InspectDto.inspectResult — TM 真实结构，明细在 stats[]（每表一条，result=passed/failed）
        //    汇总差异行数在 differenceNumber / difference_number
        // 2) 平铺在 Inspect 文档上 — 旧结构，字段名 mismatchedRows / mismatchedTableCount
        JsonNode result = inspectInfo.has("inspectResult") && inspectInfo.get("inspectResult").isObject()
                ? inspectInfo.get("inspectResult") : inspectInfo;

        long mismatchedRows = 0;
        long totalRows = 0;
        int mismatchedTables = 0;
        int totalTables = 0;

        // --- TM 真实结构：stats[] ---
        JsonNode stats = result.get("stats");
        if (stats != null && stats.isArray()) {
            for (JsonNode s : stats) {
                totalTables++;
                if ("failed".equalsIgnoreCase(text(s, "result"))) mismatchedTables++;
                mismatchedRows += longOf(s, "rowFailed", "row_failed");
                totalRows += longOf(s, "sourceTotal", "source_total");
            }
        }

        // --- 汇总差异行数：differenceNumber / difference_number ---
        long differenceNumber = longOf(result, "differenceNumber", "difference_number");
        if (differenceNumber == 0) differenceNumber = longOf(inspectInfo, "differenceNumber", "difference_number");
        if (differenceNumber > mismatchedRows) mismatchedRows = differenceNumber;

        // --- 旧结构兼容 ---
        if (result.has("mismatchedRows")) mismatchedRows = result.get("mismatchedRows").asLong();
        if (result.has("totalRows")) totalRows = result.get("totalRows").asLong();
        if (result.has("mismatchedTableCount")) mismatchedTables = result.get("mismatchedTableCount").asInt();
        if (result.has("totalTableCount")) totalTables = result.get("totalTableCount").asInt();

        log.info("[TM] Inspect result summary: mismatchedTables={}/{}, mismatchedRows={}/{}",
                mismatchedTables, totalTables, mismatchedRows, totalRows);

        boolean assertPassed = true;
        String failureReason = null;

        if (!allowMismatch) {
            // 严格模式 — 0 不一致
            if (mismatchedTables > 0 || mismatchedRows > 0) {
                assertPassed = false;
                failureReason = String.format(
                        "Inspect failed: %d/%d tables mismatched, %d/%d rows mismatched (allowMismatch=false)",
                        mismatchedTables, totalTables, mismatchedRows, totalRows);
            }
        } else {
            // 宽松模式 — 按 maxMismatchRows 判断
            if (mismatchedRows > maxMismatchRows) {
                assertPassed = false;
                failureReason = String.format(
                        "Inspect failed: mismatchedRows=%d exceeds maxMismatchRows=%d",
                        mismatchedRows, maxMismatchRows);
            }
        }

        if (!assertPassed) {
            log.error("[TM] {}", failureReason);
            throw new AssertionFailedException(failureReason);
        }

        log.info("[TM] Inspect assertion PASSED — data is consistent");

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("inspect_id", inspectId);
        outputs.put("assert_passed", assertPassed);
        outputs.put("total_rows", totalRows);
        outputs.put("mismatched_rows", mismatchedRows);
        outputs.put("total_tables", totalTables);
        outputs.put("mismatched_tables", mismatchedTables);
        return success(outputs);
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }

    /** 校验任务是否已到终态（done / complete / success / failed / error / cancel）。 */
    private static boolean isFinished(String status) {
        if (status == null) return false;
        String s = status.toLowerCase();
        return s.contains("done") || s.contains("complete") || s.contains("success")
                || s.contains("fail") || s.contains("error") || s.contains("cancel");
    }

    /** 按候选字段名依次取值（兼容 camelCase / snake_case），缺省 0。 */
    private static long longOf(JsonNode n, String... keys) {
        for (String key : keys) {
            if (n.has(key) && !n.get(key).isNull()) return n.get(key).asLong();
        }
        return 0L;
    }
}
