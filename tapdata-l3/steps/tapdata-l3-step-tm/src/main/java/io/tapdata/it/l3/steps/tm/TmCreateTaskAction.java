package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
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

        JsonNode resp = client.post("/api/Task", body);

        String taskId = text(resp, "id");
        log.info("[TM] Task created: id={}, name={}", taskId, p.getName());

        // Auto-cleanup
        if (!config.isKeepAlive() && taskId != null) {
            ctx.registerCleanup(() -> {
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

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("task_id", taskId);
        outputs.put("name", p.getName());
        outputs.put("sync_type", p.getSyncType());
        return success(outputs);
    }

    /** 简单直连模式 — source → target 单节点 DAG. */
    private Map<String, Object> buildSimpleBody(TmTaskParameter p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", p.getName());
        body.put("syncType", p.getSyncType() != null ? p.getSyncType() : "sync");

        // 构建 DAG (TM Task 使用 DAG 结构)
        Map<String, Object> dag = new LinkedHashMap<>();
        Map<String, Object> sourceNode = new LinkedHashMap<>();
        Map<String, Object> targetNode = new LinkedHashMap<>();

        sourceNode.put("id", "source_node");
        sourceNode.put("type", "Source");
        sourceNode.put("connectionId", p.getSourceConnectionId());
        sourceNode.put("connectionName", "source_" + p.getName());

        // Tables
        if (p.getSourceTables() != null && !p.getSourceTables().isEmpty()) {
            List<Map<String, Object>> tableList = new ArrayList<>();
            for (String t : p.getSourceTables()) {
                Map<String, Object> table = new LinkedHashMap<>();
                table.put("name", t);
                table.put("type", "table");
                tableList.add(table);
            }
            sourceNode.put("tableNames", p.getSourceTables());
            sourceNode.put("tables", tableList);
        }

        targetNode.put("id", "target_node");
        targetNode.put("type", "Target");
        targetNode.put("connectionId", p.getTargetConnectionId());
        targetNode.put("connectionName", "target_" + p.getName());

        if (p.getTargetTablePrefix() != null) {
            targetNode.put("tableNamePrefix", p.getTargetTablePrefix());
        }

        dag.put("source", sourceNode);
        dag.put("target", targetNode);

        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", "source_node");
        edge.put("target", "target_node");
        edges.add(edge);
        dag.put("edges", edges);

        body.put("dag", dag);
        body.put("flowStatus", "draft");
        return body;
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
