package io.tapdata.it.l3.steps.dbforge;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Provisions a database lease via dbforge.
 * <p>
 * <pre>
 * - id: create_mysql_db
 *   action: dbforge_create_database
 *   inputs:
 *     db_type: mysql
 *     mode: dedicated
 *     topology: single
 *   keep_alive: false
 *   outputs auto-registered: lease_id, jdbc_url, uri, host, port, database, username, password
 * </pre>
 *
 * <p>
 * <b>Lifecycle</b>: DbforgeClient 在 {@link #before(StepConfig, ScenarioContext)} 中创建，
 * 从 ~/.dbforge 或环境变量读取配置 — 不需要 Spring 注入。
 */
public class DbforgeCreateDatabaseAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(DbforgeCreateDatabaseAction.class);

    /** 在 before() 中惰性创建 — 每次调用都 fresh（配置可能变了）。 */
    private DbforgeClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public DbforgeCreateDatabaseAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new DbforgeClient();
        }
    }

    @Override
    public String getActionName() {
        return "dbforge_create_database";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        Map<String, Object> inputs = config.getInputs();

        String dbType = required(inputs, "db_type");
        String mode = (String) inputs.get("mode");
        String topology = (String) inputs.get("topology");

        log.info("[DBForge] Creating lease: db_type={}, mode={}, topology={}", dbType, mode, topology);

        DbforgeClient.LeaseResult lease = client.createLease(dbType, mode, topology);

        if (!config.isKeepAlive()) {
            final String leaseId = lease.leaseId;
            ctx.registerCleanup(() -> {
                try {
                    log.info("[DBForge] Auto-releasing lease {}", leaseId);
                    client.destroyLease(leaseId);
                } catch (Exception e) {
                    log.warn("[DBForge] Failed to release lease {}: {}", leaseId, e.getMessage());
                }
            });
        }

        log.info("[DBForge] Lease created: id={}, jdbc_url={}", lease.leaseId, lease.jdbcUrl);
        return success(lease.toMap());
    }

    private static String required(Map<String, Object> inputs, String key) {
        Object v = inputs.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Required input missing: " + key);
        }
        return v.toString();
    }
}
