package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authenticate with TM and cache the access token.
 *
 * Outputs: {@code access_token}, {@code tm_base_url}
 *
 * <b>Lifecycle</b>: TmClient 在 before() 中惰性创建。
 */
public class TmLoginAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmLoginAction.class);

    private TmClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public TmLoginAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_login";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        String token = client.getToken();
        log.info("[TM] Login OK — token acquired (len={})", token.length());
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("access_token", token);
        outputs.put("tm_base_url", client.getBaseUrl());
        return success(outputs);
    }
}
