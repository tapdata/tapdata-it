package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Create a data sync task in TM.
 * <p>
 * 支持三种创建模式 (按优先级从高到低):
 * <ol>
 *   <li>{@code raw_task_config} — 直接传完整 TaskDto Map</li>
 *   <li>{@code dag_nodes} — 传自定义 DAG 节点列表</li>
 *   <li>简单字段 — source/target connection + tables, 自动构建简单直连 DAG</li>
 * </ol>
 *
 * <pre>
 * # 简单模式 — 自动构建直连任务
 * - id: create_sync_task
 *   action: tm_create_task
 *   inputs:
 *     name: "gp_mysql_to_mysql"
 *     sync_type: "sync"
 *     source_connection_id: "#{ #steps['create_src_conn'].outputs['connection_id'] }"
 *     target_connection_id: "#{ #steps['create_tgt_conn'].outputs['connection_id'] }"
 *     source_tables: ["users", "orders"]
 *
 * # 自定义 DAG 模式
 * - id: create_custom_task
 *   action: tm_create_task
 *   inputs:
 *     name: "custom_dag_task"
 *     sync_type: "sync"
 *     dag_nodes:
 *       - id: src_node
 *         type: "source"
 *         connection_id: "#{ #steps['create_src_conn'].outputs['connection_id'] }"
 *         tables: ["users"]
 *       - id: tgt_node
 *         type: "target"
 *         connection_id: "#{ #steps['create_tgt_conn'].outputs['connection_id'] }"
 *       - id: edge1
 *         type: "edge"
 *         source: "src_node"
 *         target: "tgt_node"
 * </pre>
 *
 * Outputs: {@code task_id}, {@code name}, {@code sync_type}
 */
public class TmCreateTaskAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmCreateTaskAction.class);

    private TmClient client;

    public TmCreateTaskAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_create_task";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmTaskParameter p = config.getInputParameterConfig(TmTaskParameter.class);

        if (p.getName() == null || p.getName().isBlank()) {
            throw new IllegalArgumentException("Required input missing: name");
        }

        Map<String, Object> body;

        if (p.getRawTaskConfig() != null && !p.getRawTaskConfig().isEmpty()) {
            // 模式 1: 完全自定义 TaskDto
            log.info("[TM] Creating task with raw_task_config: {}", p.getName());
            body = new LinkedHashMap<>(p.getRawTaskConfig());
            body.put("name", p.getName());
        } else if (p.getDagNodes() != null && !p.getDagNodes().isEmpty()) {
            // 模式 2: 自定义 DAG 节点
            log.info("[TM] Creating task with custom DAG: {}", p.getName());
            body = buildBodyWithCustomDag(p);
        } else {
            // 模式 3: 简单直连模式
            log.info("[TM] Creating simple source→target task: {}", p.getName());
            body = buildSimpleBody(p);
        }

        // DEBUG: 打印发送的 JSON body
        try {
            log.info("[TM] Task create body: {}", new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(body));
        } catch (Exception ignored) {}

        JsonNode resp = client.post("/api/Task", body);

        String taskId = text(resp, "id");
        String taskStatus = text(resp, "status");
        log.info("[TM] Task created: id={}, name={}, status={}", taskId, p.getName(), taskStatus);

        // 如果创建后还是 edit 状态，需要 confirm 才能 start
        if (taskId != null && "edit".equalsIgnoreCase(taskStatus)) {
            try {
                // confirm 必须带完整字段，否则 TaskState.getState(null) 返回 null → 状态机找不到 transition
                JsonNode fullTask = client.get("/api/Task/" + taskId);
                Map<String, Object> confirmBody = new LinkedHashMap<>();
                confirmBody.put("id", taskId);
                confirmBody.put("name", p.getName());

                // 关键：手动指定 agent 相关字段
                if (p.getAgentId() != null && !p.getAgentId().isBlank()) {
                    confirmBody.put("accessNodeType", "MANUALLY_SPECIFIED_BY_THE_USER");
                    confirmBody.put("accessNodeProcessId", p.getAgentId());
                }

                // 带上完整的 DAG（POST 后 TM 可能已经做了部分转换）
                JsonNode dagNode = fullTask.get("dag");
                if (dagNode != null) confirmBody.put("dag", dagNode);

                // 带上 status 字段
                JsonNode statusNode = fullTask.get("status");
                if (statusNode != null) confirmBody.put("status", statusNode.asText());

                client.patch("/api/Task/confirm/" + taskId + "?confirm=true", confirmBody);
                log.info("[TM] Task confirmed: id={}, accessNodeType={}, accessNodeProcessId={}",
                        taskId,
                        confirmBody.get("accessNodeType"),
                        confirmBody.get("accessNodeProcessId"));

                // === 关键：调用 re-schemas 让 TM 去数据源加载表结构 ===
                // 否则 TM 的 DEDUCTION 阶段会失败（engine 拿不到 schema 信息）
                try {
                    List<String> nodeIds = new ArrayList<>();
                    JsonNode dagForRefresh = client.get("/api/Task/" + taskId).get("dag");
                    if (dagForRefresh != null && dagForRefresh.has("nodes")) {
                        for (JsonNode n : dagForRefresh.get("nodes")) {
                            if (n.has("id")) nodeIds.add(n.get("id").asText());
                        }
                    }
                    if (!nodeIds.isEmpty()) {
                        String nodeIdsParam = String.join(",", nodeIds);
                        log.info("[TM] Calling re-schemas for nodes: {}", nodeIdsParam);
                        Map<String, Object> query = Map.of("nodeIds", nodeIdsParam);
                        client.put("/api/Task/" + taskId + "/re-schemas", query, null);
                        log.info("[TM] re-schemas OK for task {}", taskId);
                    }
                } catch (Exception e) {
                    log.warn("[TM] re-schemas failed for task {}: {}", taskId, e.getMessage());
                }
            } catch (Exception e) {
                log.warn("[TM] Auto-confirm failed for task {}: {}", taskId, e.getMessage());
            }
        }

        // Auto-cleanup
        if (!config.isKeepAlive() && taskId != null) {
            ctx.registerCleanup("tm", () -> {
                try {
                    log.info("[TM] Auto-stopping & deleting task {}", taskId);
                    try {
                        client.put("/api/Task/stop/" + taskId, null, null);
                    } catch (Exception ignored) {}
                    client.delete("/api/Task/" + taskId);
                } catch (Exception e) {
                    log.warn("[TM] Failed to auto-delete task {}: {}", taskId, e.getMessage());
                }
            });
        }

        // 获取完整 task DTO 提取 DAG node IDs（供 Inspect 构建使用）
        String sourceNodeId = null, targetNodeId = null;
        String sourceConnectionId = null, targetConnectionId = null;
        String sourceTableName = null, targetTableName = null;
        try {
            JsonNode fullTask = client.get("/api/Task/" + taskId);
            JsonNode dagNode = fullTask.get("dag");
            if (dagNode != null && dagNode.has("nodes")) {
                JsonNode nodes = dagNode.get("nodes");
                if (nodes.isArray() && nodes.size() >= 2) {
                    // 第 1 个是 source, 第 2 个是 target（DAG 顺序）
                    sourceNodeId = nodes.get(0).has("id") ? nodes.get(0).get("id").asText() : null;
                    targetNodeId = nodes.get(1).has("id") ? nodes.get(1).get("id").asText() : null;
                    sourceConnectionId = nodes.get(0).has("connectionId") ? nodes.get(0).get("connectionId").asText() : null;
                    targetConnectionId = nodes.get(1).has("connectionId") ? nodes.get(1).get("connectionId").asText() : null;
                    sourceTableName = nodes.get(0).has("tableName") ? nodes.get(0).get("tableName").asText() : null;
                    targetTableName = nodes.get(1).has("tableName") ? nodes.get(1).get("tableName").asText() : null;
                    log.info("[TM] Task DAG: source_node={} table={}, target_node={} table={}",
                            sourceNodeId, sourceTableName, targetNodeId, targetTableName);
                }
            }
        } catch (Exception e) {
            log.warn("[TM] Failed to extract DAG node IDs from task {}: {}", taskId, e.getMessage());
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", taskId);
        outputs.put("flow_id", taskId);  // TM 内部 flowId = taskId
        outputs.put("name", p.getName());
        outputs.put("sync_type", p.getSyncType());
        // DAG node 信息 — 供 Inspect 构建使用
        if (sourceNodeId != null) outputs.put("source_node_id", sourceNodeId);
        if (targetNodeId != null) outputs.put("target_node_id", targetNodeId);
        if (sourceConnectionId != null) outputs.put("source_connection_id", sourceConnectionId);
        if (targetConnectionId != null) outputs.put("target_connection_id", targetConnectionId);
        if (sourceTableName != null) outputs.put("source_table_name", sourceTableName);
        if (targetTableName != null) outputs.put("target_table_name", targetTableName);
        // Agent 信息 — 供后续 Inspect 使用
        if (p.getAgentId() != null) outputs.put("agent_id", p.getAgentId());
        return success(outputs);
    }

    /** 简单直连模式 — source → target DatabaseNode DAG. */
    private Map<String, Object> buildSimpleBody(TmTaskParameter p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", p.getName());
        // TM DatabaseNode 会强制 syncType 为 "migrate"
        body.put("syncType", "migrate");

        // === 关键：手动指定 agent ===
        // accessNodeType 必须为 MANUALLY_SPECIFIED_BY_THE_USER，否则 TM 会自动分配到不可用的 agent
        // accessNodeProcessId 就是要指定的 agent processId
        if (p.getAgentId() != null && !p.getAgentId().isBlank()) {
            body.put("accessNodeType", "MANUALLY_SPECIFIED_BY_THE_USER");
            body.put("accessNodeProcessId", p.getAgentId());
        } else {
            body.put("accessNodeType", "AUTOMATIC_PLATFORM_ALLOCATION");
        }

        // 构建 DAG — TM node type 是 "database"
        Map<String, Object> dag = new LinkedHashMap<>();

        // Source database node — 参考真实前端创建的节点完整字段
        Map<String, Object> sourceNode = buildSourceNode(p);

        // Target database node
        Map<String, Object> targetNode = buildTargetNode(p);

        // nodes 数组
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(sourceNode);
        nodes.add(targetNode);
        dag.put("nodes", nodes);

        // edges 数组
        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", sourceNode.get("id"));
        edge.put("target", targetNode.get("id"));
        edge.put("disabled", false);
        edges.add(edge);
        dag.put("edges", edges);

        body.put("dag", dag);
        return body;
    }

    /** 构建完整的源数据库节点 — 参考前端实际创建的字段. */
    private Map<String, Object> buildSourceNode(TmTaskParameter p) {
        Map<String, Object> node = new LinkedHashMap<>();
        String nodeId = UUID.randomUUID().toString();
        node.put("id", nodeId);
        node.put("type", "database");
        node.put("name", "source");
        node.put("connectionId", p.getSourceConnectionId());
        node.put("elementType", "Node");
        node.put("disabled", false);
        node.put("catalog", "data");

        // 表选择：如果指定了表就用 custom 模式，否则用表达式
        if (p.getSourceTables() != null && !p.getSourceTables().isEmpty()) {
            node.put("tableNames", p.getSourceTables());
            node.put("migrateTableSelectType", "custom");
        } else {
            node.put("tableExpression", ".*");
            node.put("migrateTableSelectType", "tableExpression");
        }

        // 运行时配置（参考真实前端节点）
        node.put("existDataProcessMode", "keepData");
        node.put("writeStrategy", "updateOrInsert");
        node.put("syncForeignKeyEnable", true);
        node.put("readBatchSize", 500);
        node.put("increaseReadSize", 1);
        node.put("enableConcurrentRead", false);
        node.put("concurrentReadThreadNumber", 0);
        node.put("applyCompareRule", false);
        node.put("compareIgnoreCase", false);
        node.put("ignoreMetrics", false);
        node.put("incrementExactlyOnceEnableTimeWindowDay", 3);
        node.put("noPkSyncMode", "ADD_HASH");
        node.put("noPrimaryKeyTableSelectType", "HasKeys");
        node.put("ddlConfiguration", "FILTER");
        node.put("isTransformed", false);

        // 空 nodeConfig
        Map<String, Object> nodeConfig = new LinkedHashMap<>();
        nodeConfig.put("highPerformance", false);
        nodeConfig.put("createAutoInc", false);
        nodeConfig.put("autoIncJumpValue", 1000000);
        nodeConfig.put("applyDefault", false);
        nodeConfig.put("hashSplit", false);
        nodeConfig.put("maxSplit", 20);
        nodeConfig.put("batchReadThread", 4);
        node.put("nodeConfig", nodeConfig);

        return node;
    }

    /** 构建完整的目标数据库节点. */
    private Map<String, Object> buildTargetNode(TmTaskParameter p) {
        Map<String, Object> node = new LinkedHashMap<>();
        String nodeId = UUID.randomUUID().toString();
        node.put("id", nodeId);
        node.put("type", "database");
        node.put("name", "target");
        node.put("connectionId", p.getTargetConnectionId());
        node.put("elementType", "Node");
        node.put("disabled", false);
        node.put("catalog", "data");

        // syncObjects — 目标节点的表映射
        if (p.getSourceTables() != null && !p.getSourceTables().isEmpty()) {
            Map<String, String> tableNameRelation = new LinkedHashMap<>();
            for (String table : p.getSourceTables()) {
                tableNameRelation.put(table, table);
            }
            Map<String, Object> syncObj = new LinkedHashMap<>();
            syncObj.put("type", "table");
            syncObj.put("objectNames", p.getSourceTables());
            syncObj.put("tableNameRelation", tableNameRelation);
            node.put("syncObjects", List.of(syncObj));
        }

        // 运行时配置
        node.put("existDataProcessMode", "keepData");
        node.put("writeStrategy", "updateOrInsert");
        node.put("syncForeignKeyEnable", true);
        node.put("readBatchSize", 500);
        node.put("increaseReadSize", 1);
        node.put("enableConcurrentRead", false);
        node.put("concurrentReadThreadNumber", 0);
        node.put("applyCompareRule", false);
        node.put("compareIgnoreCase", false);
        node.put("ignoreMetrics", false);
        node.put("incrementExactlyOnceEnableTimeWindowDay", 3);
        node.put("isTransformed", false);
        node.put("migrateTableSelectType", "custom");
        node.put("dmlPolicy", new LinkedHashMap<>());
        node.put("updateConditionFieldMap", new LinkedHashMap<>());

        node.put("nodeConfig", new LinkedHashMap<>());

        return node;
    }

    /** 自定义 DAG 节点模式. */
    private Map<String, Object> buildBodyWithCustomDag(TmTaskParameter p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", p.getName());
        body.put("syncType", p.getSyncType() != null ? p.getSyncType() : "sync");
        body.put("dagNodes", p.getDagNodes());
        body.put("flowStatus", "draft");
        return body;
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
