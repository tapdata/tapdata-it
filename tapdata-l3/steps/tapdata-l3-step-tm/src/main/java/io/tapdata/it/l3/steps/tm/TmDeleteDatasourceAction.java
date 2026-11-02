package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delete a Connection from TM.
 *
 * <pre>
 * - id: cleanup_conn
 *   action: tm_delete_datasource
 *   inputs:
 *     connection_id: "#{ #steps['create_mysql_conn'].outputs['connection_id'] }"
 * </pre>
 */
public class TmDeleteDatasourceAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmDeleteDatasourceAction.class);

    private TmClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public TmDeleteDatasourceAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_delete_datasource";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmDeleteDatasourceParameter p = config.getInputParameterConfig(TmDeleteDatasourceParameter.class);
        if (p.getConnectionId() == null || p.getConnectionId().isBlank()) {
            throw new IllegalArgumentException("Required input missing: connection_id");
        }
        log.info("[TM] Deleting Connection {}", p.getConnectionId());
        client.delete("/api/Connections/" + p.getConnectionId());
        log.info("[TM] Connection {} deleted", p.getConnectionId());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("connection_id", p.getConnectionId());
        outputs.put("deleted", true);
        return success(outputs);
    }
}
