package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * List existing Connections in TM, optionally filtered.
 *
 * <pre>
 * - id: list_conns
 *   action: tm_list_datasources
 *   inputs:
 *     database_type: "Mysql"        # optional filter
 *     limit: 10                     # optional, default 50
 * </pre>
 *
 * Outputs: {@code total}, {@code connections} (list of {id, name, database_type, connection_type, status})
 */
public class TmListDatasourcesAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmListDatasourcesAction.class);

    private TmClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public TmListDatasourcesAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_list_datasources";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        Map<String, Object> inputs = config.getInputs();
        String databaseType = (String) inputs.get("database_type"); // optional filter
        int limit = parseInt(inputs.getOrDefault("limit", 50), 50);

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("limit", limit);

        if (databaseType != null && !databaseType.isBlank()) {
            // TM uses JSON filter param — try both approaches
            queryParams.put("filter", "{\"database_type\":\"" + databaseType + "\"}");
        }

        log.info("[TM] Listing Connections (limit={}, filter={})", limit, databaseType);
        JsonNode resp = client.get("/api/Connections", queryParams);

        JsonNode items = resp.has("items") ? resp.get("items") : resp;
        int total = resp.has("total") ? resp.get("total").asInt() : items.size();

        java.util.List<Map<String, Object>> connections = new java.util.ArrayList<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", text(item, "id"));
                c.put("name", text(item, "name"));
                c.put("database_type", text(item, "database_type"));
                c.put("connection_type", text(item, "connection_type"));
                c.put("status", text(item, "status"));
                connections.add(c);
            }
        }

        log.info("[TM] Connections: total={}, returned={}", total, connections.size());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("total", total);
        outputs.put("connections", connections);
        return success(outputs);
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }

    private static int parseInt(Object v, int def) {
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}
