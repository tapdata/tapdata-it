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
 * Create a TM Inspect (data verification) task tied to a sync task.
 * <p>
 * API: {@code POST /api/Inspects}
 *
 * <pre>
 * - id: create_inspect_task
 *   action: tm_create_inspect
 *   inputs:
 *     name: "gp_inspect_1"
 *     task_id: "#{ #steps['create_sync_task'].outputs['task_id'] }"
 *     source_connection_id: "#{ #steps['create_src_conn'].outputs['connection_id'] }"
 *     target_connection_id: "#{ #steps['create_tgt_conn'].outputs['connection_id'] }"
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
            throw new IllegalArgumentException("Required input missing: task_id");
        }

        Map<String, Object> body;

        if (p.getRawInspectConfig() != null && !p.getRawInspectConfig().isEmpty()) {
            body = new LinkedHashMap<>(p.getRawInspectConfig());
            body.put("name", p.getName());
        } else {
            body = buildInspectBody(p);
        }

        log.info("[TM] Creating Inspect task '{}' for sync task {}", p.getName(), p.getTaskId());
        JsonNode resp = client.post("/api/Inspects", body);

        String inspectId = text(resp, "id");
        log.info("[TM] Inspect created: id={}", inspectId);

        // Auto-cleanup
        if (!config.isKeepAlive() && inspectId != null) {
            ctx.registerCleanup(() -> {
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

    private Map<String, Object> buildInspectBody(TmInspectParameter p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", p.getName());
        body.put("taskId", p.getTaskId());
        body.put("agentType", p.getAgentType() != null ? p.getAgentType() : "Collect");

        // Platform info — required by Inspect API
        Map<String, Object> platformInfo = new LinkedHashMap<>();
        platformInfo.put("agentType", p.getAgentType() != null ? p.getAgentType() : "Collect");
        platformInfo.put("taskId", p.getTaskId());
        body.put("platformInfo", platformInfo);

        // Source connection
        Map<String, Object> sourceConn = new LinkedHashMap<>();
        sourceConn.put("id", p.getSourceConnectionId());
        body.put("sourceConnection", sourceConn);

        // Target connection
        Map<String, Object> targetConn = new LinkedHashMap<>();
        targetConn.put("id", p.getTargetConnectionId());
        body.put("targetConnection", targetConn);

        // Tables to inspect
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<String> tables = p.getTables();
        if (tables != null && !tables.isEmpty()) {
            for (String table : tables) {
                Map<String, Object> task = new LinkedHashMap<>();
                task.put("sourceTable", table);
                task.put("targetTable", table);
                task.put("inspectType", "all");
                tasks.add(task);
            }
        } else {
            // 空列表表示使用同步任务中的所有表
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("inspectType", "all");
            tasks.add(task);
        }
        body.put("tasks", tasks);

        return body;
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
