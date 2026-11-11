package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Create a new datasource Connection in TM.
 * <p>
 * Automatically resolves {@code pdkHash} from the {@code database_type} via the
 * {@code /api/DatabaseTypes} API (unless you pass it explicitly).
 *
 * <pre>
 * # Minimal — resolves pdkHash automatically:
 * - id: create_mysql_conn
 *   action: tm_create_datasource
 *   inputs:
 *     name: "l3_test_mysql"
 *     database_type: "Mysql"              # Mysql / MongoDB / PostgreSQL / ...
 *     connection_type: "source_and_target" # source / target / source_and_target
 *     database_host: "#{ #steps['create_mysql'].outputs['host'] }"
 *     database_port: "#{ #steps['create_mysql'].outputs['port'] }"
 *     database_username: "#{ #steps['create_mysql'].outputs['username'] }"
 *     database_password: "#{ #steps['create_mysql'].outputs['password'] }"
 *     database_name: "#{ #steps['create_mysql'].outputs['database'] }"
 *
 * # Full — explicit pdkHash:
 * - id: create_pg_conn
 *   action: tm_create_datasource
 *   inputs:
 *     name: "l3_test_pg"
 *     database_type: "PostgreSQL"
 *     pdk_hash: "a5af410b..."
 *     connection_type: "source_and_target"
 *     database_host: "..."
 *     database_port: 5432
 *     database_username: "..."
 *     database_password: "..."
 *     database_name: "..."
 *     # Optional raw PDK config override (merged with flat fields):
 *     config:
 *       ssl: false
 * </pre>
 *
 * Outputs: {@code connection_id}, {@code name}, {@code pdk_hash}, {@code database_type}
 */
public class TmCreateDatasourceAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmCreateDatasourceAction.class);

    /** 在 before() 中惰性创建 — 反射实例化时不能带依赖。 */
    private TmClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public TmCreateDatasourceAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_create_datasource";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmCreateDatasourceParameter p = config.getInputParameterConfig(TmCreateDatasourceParameter.class);

        // 必填校验
        if (p.getName() == null || p.getName().isBlank()) {
            throw new IllegalArgumentException("Required input missing: name");
        }
        if (p.getDatabaseType() == null || p.getDatabaseType().isBlank()) {
            throw new IllegalArgumentException("Required input missing: database_type");
        }
        if (p.getDatabaseHost() == null || p.getDatabaseHost().isBlank()) {
            throw new IllegalArgumentException("Required input missing: database_host");
        }
        if (p.getDatabaseUsername() == null || p.getDatabaseUsername().isBlank()) {
            throw new IllegalArgumentException("Required input missing: database_username");
        }

        // Auto-resolve pdkHash from DatabaseTypes API
        String pdkHash = p.getPdkHash();
        if (pdkHash == null || pdkHash.isBlank()) {
            pdkHash = resolvePdkHash(p.getDatabaseType());
            log.info("[TM] Auto-resolved pdkHash for {} → {}", p.getDatabaseType(), pdkHash);
        }

        String connectionType = p.getConnectionType();

        // Build flat request body (matches DataSourceConnectionDto)
        // pdkType is CRITICAL — engine's TestConnectionHandler throws
        // "Unknown connection pdk type" when pdkType is null (only "pdk" is valid)
        // Also matches browser frontend DatabaseForm.vue payload exactly
        Map<String, Object> body = new HashMap<>();
        body.put("name", p.getName());
        body.put("database_type", p.getDatabaseType());
        body.put("pdkHash", pdkHash);
        body.put("pdkType", "pdk");                       // REQUIRED — engine validates this
        body.put("connection_type", connectionType);
        body.put("database_host", p.getDatabaseHost());
        body.put("database_port", p.getDatabasePort());
        body.put("database_username", p.getDatabaseUsername());
        body.put("database_password", p.getDatabasePassword());
        body.put("database_name", p.getDatabaseName());
        body.put("submit", true);                         // trigger async connection test
        body.put("status", "testing");                    // match browser state

        // Build PDK config JSON (nested)
        Map<String, Object> pdkConfig = new LinkedHashMap<>();
        pdkConfig.put("__connectionType", connectionType);
        pdkConfig.put("host", p.getDatabaseHost());
        pdkConfig.put("port", p.getDatabasePort());
        pdkConfig.put("username", p.getDatabaseUsername());
        pdkConfig.put("password", p.getDatabasePassword());
        if (p.getDatabaseName() != null) pdkConfig.put("database", p.getDatabaseName());

        // Merge caller-provided config overrides
        Map<String, Object> rawConfig = p.getConfig();
        if (rawConfig != null) {
            pdkConfig.putAll(rawConfig);
        }
        body.put("config", pdkConfig);

        // Optional: 指定 agent — 和 Task 一致使用 tapsupertest5 才能连上 dbforge MySQL
        if (p.getAgentId() != null && !p.getAgentId().isBlank()) {
            body.put("accessNodeType", "MANUALLY_SPECIFIED_BY_THE_USER");
            body.put("accessNodeProcessId", p.getAgentId());
            log.info("[TM] Datasource will use agent: {}", p.getAgentId());
        }

        log.info("[TM] Creating Connection '{}' (type={}, pdkHash={})", p.getName(), p.getDatabaseType(), pdkHash);
        JsonNode resp = client.post("/api/Connections", body);

        String connectionId = text(resp, "id");
        log.info("[TM] Connection created: id={}", connectionId);

        // Auto-cleanup unless keep_alive
        if (!config.isKeepAlive() && connectionId != null) {
            ctx.registerCleanup(() -> {
                try {
                    log.info("[TM] Auto-deleting Connection {}", connectionId);
                    client.delete("/api/Connections/" + connectionId);
                } catch (Exception e) {
                    log.warn("[TM] Failed to auto-delete Connection {}: {}", connectionId, e.getMessage());
                }
            });
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("connection_id", connectionId);
        outputs.put("name", text(resp, "name"));
        outputs.put("pdk_hash", text(resp, "pdkHash"));
        outputs.put("database_type", text(resp, "database_type"));
        return success(outputs);
    }

    /**
     * Query /api/DatabaseTypes to find the pdkHash matching the given database type.
     * 全新集群上 connectors 由 engine 启动后陆续注册到 TM，
     * DatabaseTypes 可能延迟数分钟才出现目标类型 — 轮询等待。
     */
    private String resolvePdkHash(String databaseType) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + PDK_RESOLVE_TIMEOUT_MS;
        int attempt = 0;
        while (true) {
            attempt++;
            JsonNode types = client.get("/api/DatabaseTypes");
            if (types.isArray()) {
                for (JsonNode t : types) {
                    String tType = text(t, "type");
                    String tName = text(t, "name");
                    if (databaseType.equalsIgnoreCase(tType) || databaseType.equalsIgnoreCase(tName)) {
                        String hash = text(t, "pdkHash");
                        if (hash != null) return hash;
                    }
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalArgumentException("Cannot auto-resolve pdkHash for database_type='" + databaseType
                        + "' after " + attempt + " attempts over " + (PDK_RESOLVE_TIMEOUT_MS / 1000)
                        + "s. Pass pdk_hash explicitly or check /api/DatabaseTypes for supported types.");
            }
            if (attempt == 1 || attempt % 6 == 0) {
                log.info("[TM] DatabaseTypes not containing '{}' yet (attempt {}), waiting for engine connector registration...",
                        databaseType, attempt);
            }
            Thread.sleep(PDK_RESOLVE_POLL_MS);
        }
    }

    /** pdkHash 自动解析等待窗口 — 覆盖新集群 engine connector 注册延迟。 */
    private static final long PDK_RESOLVE_TIMEOUT_MS = 300_000L;
    private static final long PDK_RESOLVE_POLL_MS = 5_000L;

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
