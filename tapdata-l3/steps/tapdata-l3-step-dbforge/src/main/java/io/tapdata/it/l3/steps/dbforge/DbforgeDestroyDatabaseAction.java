package io.tapdata.it.l3.steps.dbforge;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Manually destroy a dbforge lease (alternative to auto-cleanup).
 *
 * <pre>
 * - id: cleanup
 *   action: dbforge_destroy_database
 *   inputs:
 *     lease_id: "#{ #steps['create_mysql_db'].outputs['lease_id'] }"
 * </pre>
 *
 * <b>Lifecycle</b>: DbforgeClient 在 before() 中惰性创建。
 */
public class DbforgeDestroyDatabaseAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(DbforgeDestroyDatabaseAction.class);

    private DbforgeClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public DbforgeDestroyDatabaseAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new DbforgeClient();
        }
    }

    @Override
    public String getActionName() {
        return "dbforge_destroy_database";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        DbforgeDestroyDatabaseParameter p = config.getInputParameterConfig(DbforgeDestroyDatabaseParameter.class);
        if (p.getLeaseId() == null || p.getLeaseId().isBlank()) {
            throw new IllegalArgumentException("Required input 'lease_id' is missing");
        }
        log.info("[DBForge] Manual destroy lease {}", p.getLeaseId());
        client.destroyLease(p.getLeaseId());
        return success();
    }
}
