package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Create a TM Inspect (data verification) task tied to a sync task (flow).
 * <p>
 * TM Inspect API 契约 (经 curl 实证):
 * <pre>
 *   - flowId 必须存在 (= 同步任务 ID)
 *   - agentTags 必须存在: ["private"] — 但传了会导致 TM 按 tag 自动分配 agentId！
 *     最佳实践: 只传 agentId, 不传 agentTags — TM 会从 agentId 自动填 agentTags
 *   - agentType = "private" (不是 "Collect"!)
 *   - platformInfo.agentType = "private"
 *   - tasks[].source/target 必须有 inspectDataSource=true, connectionId, nodeId, table 等完整字段
 * </pre>
 *
 * <pre>
 * - id: create_inspect_task
 *   action: tm_create_inspect
 *   inputs:
 *     name: "gp_inspect_1"
 *     task_id: "#{ #steps['create_sync_task'].outputs['task_id'] }"
 *     agent_id: "ca96cd0f-e7cc-41dd-a950-e10f89312b40"
 *     source_connection_id: "#{ #steps['create_src_datasource'].outputs['connection_id'] }"
 *     target_connection_id: "#{ #steps['create_tgt_datasource'].outputs['connection_id'] }"
 *     tables: ["users", "orders"]
 *     inspect_mode: "all"
 * </pre>
 *
 * Outputs: {@code inspect_id}, {@code name}, {@code task_id}
 */
public class TmCreateInspectAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmCreateInspectAction.class);

    private TmClient client;

    public TmCreateInspectAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_create_inspect";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmInspectParameter p = config.getInputParameterConfig(TmInspectParameter.class);

        if (p.getName() == null || p.getName().isBlank()) {
            throw new IllegalArgumentException("Required input missing: name");
        }
        if (p.getTaskId() == null || p.getTaskId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: task_id (= flowId)");
        }

        Map<String, Object> body;

        if (p.getRawInspectConfig() != null && !p.getRawInspectConfig().isEmpty()) {
            body = new LinkedHashMap<>(p.getRawInspectConfig());
            body.put("name", p.getName());
        } else {
            // 自动构建完整 Inspect body — 核心修复：从 sync task DAG 提取 node 信息
            body = buildInspectBody(p, ctx);
        }

        log.info("[TM] Creating Inspect task '{}' for flowId={}, agentId={}",
                p.getName(), p.getTaskId(), p.getAgentId());
        log.debug("[TM] Inspect create body: {}", body);

        JsonNode resp = client.post("/api/Inspects", body);

        String inspectId = text(resp, "id");
        log.info("[TM] Inspect created: id={}, agentId={}", inspectId, text(resp, "agentId"));

        // Auto-cleanup
        if (!config.isKeepAlive() && inspectId != null) {
            ctx.registerCleanup("tm", () -> {
                try {
                    log.info("[TM] Auto-deleting Inspect {}", inspectId);
                    client.delete("/api/Inspects/" + inspectId);
                } catch (Exception e) {
                    log.warn("[TM] Failed to auto-delete Inspect {}: {}", inspectId, e.getMessage());
                }
            });
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("inspect_id", inspectId);
        outputs.put("name", p.getName());
        outputs.put("task_id", p.getTaskId());
        return success(outputs);
    }

    /**
     * 构建完整的 InspectDto.
     * <p>
     * 核心修复: 从 TM 同步任务 DAG 中读取真实的 source/target node ID、connection ID、table 名
     * 来构建 Inspect.tasks[].source/target DTO. 之前用 connectionId/tableName 作为占位会导致
     * TM 报 "inspectDataSource can not be null".
     */
    private Map<String, Object> buildInspectBody(TmInspectParameter p, ScenarioContext ctx) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", p.getName());
        body.put("flowId", p.getTaskId());  // TM 内部字段叫 flowId!

        // Agent: 只传 agentId, 不传 agentTags — TM 会从 agentId 自动填 agentTags
        // 这样能确保 agentId 不被 agentTags 覆盖
        if (p.getAgentId() != null && !p.getAgentId().isBlank()) {
            body.put("agentId", p.getAgentId());
        }
        body.put("agentType", p.getAgentType() != null ? p.getAgentType() : "private");

        body.put("mode", p.getMode() != null ? p.getMode() : "manual");
        body.put("inspectMethod", p.getInspectMethod() != null ? p.getInspectMethod() : "row_count");
        body.put("inspectDifferenceMode", p.getInspectDifferenceMode() != null ? p.getInspectDifferenceMode() : "All");

        // Platform info
        Map<String, Object> platformInfo = new LinkedHashMap<>();
        platformInfo.put("agentType", p.getAgentType() != null ? p.getAgentType() : "private");
        body.put("platformInfo", platformInfo);

        // 关键步骤: 从 sync task DAG 构建 inspect tasks
        List<Map<String, Object>> inspectTasks = buildInspectTasksFromSyncTask(p);
        body.put("tasks", inspectTasks);

        return body;
    }

    /**
     * 从同步任务 DAG 构建 Inspect.tasks 列表.
     * <p>
     * Inspect.tasks[].source/target 需要完整的 DTO:
     * - connectionId, nodeId, table, nodeName, databaseType, sortColumn, inspectDataSource=true
     */
    private List<Map<String, Object>> buildInspectTasksFromSyncTask(TmInspectParameter p) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();

        // 1. GET 完整的 sync task DTO (从 TM)
        JsonNode taskDto = client.get("/api/Task/" + p.getTaskId());
        JsonNode dagNode = taskDto.get("dag");
        if (dagNode == null || !dagNode.has("nodes")) {
            throw new IllegalStateException("Sync task " + p.getTaskId() + " has no DAG nodes — cannot build Inspect");
        }
        JsonNode nodes = dagNode.get("nodes");
        if (!nodes.isArray() || nodes.size() < 2) {
            throw new IllegalStateException("Sync task DAG needs at least 2 nodes (source+target), got " + nodes.size());
        }

        // 2. DAG 顺序: nodes[0]=source, nodes[1]=target
        JsonNode srcNode = nodes.get(0);
        JsonNode tgtNode = nodes.get(1);

        String srcConnId = text(srcNode, "connectionId");
        String tgtConnId = text(tgtNode, "connectionId");
        String srcNodeId = text(srcNode, "id");
        String tgtNodeId = text(tgtNode, "id");
        String srcTable = text(srcNode, "tableName");
        String tgtTable = text(tgtNode, "tableName");
        String srcDbType = text(srcNode, "databaseType");
        String tgtDbType = text(tgtNode, "databaseType");
        // 从 attrs 获取 connectionName
        String srcConnName = text(srcNode.path("attrs"), "connectionName");
        String tgtConnName = text(tgtNode.path("attrs"), "connectionName");

        log.info("[TM] Building Inspect task: src(node={},conn={},table={}) → tgt(node={},conn={},table={})",
                srcNodeId, srcConnId, srcTable, tgtNodeId, tgtConnId, tgtTable);

        // 3. 如果 YAML 指定了 source/target_connection_id, 优先用 YAML 的 (应该和 DAG 一致)
        if (p.getSourceConnectionId() != null && !p.getSourceConnectionId().isBlank()) {
            srcConnId = p.getSourceConnectionId();
        }
        if (p.getTargetConnectionId() != null && !p.getTargetConnectionId().isBlank()) {
            tgtConnId = p.getTargetConnectionId();
        }

        // 4. 如果 YAML 指定了具体 tables, 为每个 table 创建一个 inspect task
        List<String> tables = p.getTables();
        if (tables != null && !tables.isEmpty()) {
            for (String table : tables) {
                Map<String, Object> inspectTask = buildInspectTaskDTO(
                        p.getTaskId(), srcNodeId, tgtNodeId,
                        srcConnId, tgtConnId, srcConnName, tgtConnName,
                        table, table,
                        srcDbType, tgtDbType, sortColumn(p));
                result.add(inspectTask);
            }
        } else {
            // 自动用 DAG 里的 sourceTable/targetTable
            Map<String, Object> inspectTask = buildInspectTaskDTO(
                    p.getTaskId(), srcNodeId, tgtNodeId,
                    srcConnId, tgtConnId, srcConnName, tgtConnName,
                    srcTable, tgtTable,
                    srcDbType, tgtDbType, sortColumn(p));
            result.add(inspectTask);
        }

        return result;
    }

    /** 构建单个 InspectTask DTO. */
    private Map<String, Object> buildInspectTaskDTO(
            String flowTaskId,
            String srcNodeId, String tgtNodeId,
            String srcConnId, String tgtConnId, String srcConnName, String tgtConnName,
            String srcTable, String tgtTable,
            String srcDbType, String tgtDbType,
            String sortColumn) {

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("connectionId", srcConnId);
        if (srcConnName != null) source.put("connectionName", srcConnName);
        source.put("nodeId", srcNodeId);
        source.put("nodeName", srcTable);
        source.put("table", srcTable);
        if (srcDbType != null) source.put("databaseType", srcDbType);
        source.put("sortColumn", sortColumn);
        source.put("inspectDataSource", true);
        source.put("fields", new ArrayList<>());
        source.put("isFilter", false);
        source.put("conditions", new ArrayList<>());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("connectionId", tgtConnId);
        if (tgtConnName != null) target.put("connectionName", tgtConnName);
        target.put("nodeId", tgtNodeId);
        target.put("nodeName", tgtTable);
        target.put("table", tgtTable);
        if (tgtDbType != null) target.put("databaseType", tgtDbType);
        target.put("sortColumn", sortColumn);
        target.put("inspectDataSource", true);
        target.put("fields", new ArrayList<>());
        target.put("isFilter", false);
        target.put("conditions", new ArrayList<>());

        Map<String, Object> inspectTask = new LinkedHashMap<>();
        inspectTask.put("flowTaskId", flowTaskId);
        inspectTask.put("source", source);
        inspectTask.put("target", target);
        inspectTask.put("inspectType", "all");
        inspectTask.put("fullMatch", true);
        inspectTask.put("script", "");
        inspectTask.put("showAdvancedVerification", false);

        return inspectTask;
    }

    /** 校验比对列：默认 _id（MongoDB），关系库需在 YAML 里用 sort_column 指定真实主键。 */
    private static String sortColumn(TmInspectParameter p) {
        String col = p.getSortColumn();
        return (col == null || col.isBlank()) ? "_id" : col;
    }

    private static String text(JsonNode n, String key) {
        return n != null && n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
