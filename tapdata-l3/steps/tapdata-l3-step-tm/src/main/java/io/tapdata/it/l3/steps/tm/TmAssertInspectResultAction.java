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

        // Parse result
        JsonNode result = inspectInfo.has("inspectResult") ? inspectInfo.get("inspectResult") : inspectInfo;

        long mismatchedRows = 0;
        long totalRows = 0;
        int mismatchedTables = 0;
        int totalTables = 0;

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
}
