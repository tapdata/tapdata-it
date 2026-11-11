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
 * Authenticate with TM and cache the access token.
 * <p>
 * 支持可选的就绪等待：新部署的集群 TM 应用启动需要时间，
 * 设置 {@code wait_timeout_seconds} 后会轮询重试直到拿到 token 或超时。
 *
 * <pre>
 * - id: login_tm
 *   action: tm_login
 *   inputs:
 *     wait_timeout_seconds: 300   # 可选，默认 180；0 = 不重试，立即失败
 *     poll_interval_ms: 5000      # 可选，默认 5000
 * </pre>
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
        TmLoginParameter p = config.getInputParameterConfig(TmLoginParameter.class);
        int waitTimeout = p.getWaitTimeoutSeconds() > 0 ? p.getWaitTimeoutSeconds() : 180;
        int pollInterval = p.getPollIntervalMs() > 0 ? p.getPollIntervalMs() : 5000;

        long deadline = System.currentTimeMillis() + waitTimeout * 1000L;
        int attempts = 0;
        Exception lastError = null;
        String token = null;

        while (true) {
            attempts++;
            try {
                token = client.getToken();
                break;
            } catch (Exception e) {
                lastError = e;
                if (System.currentTimeMillis() >= deadline) {
                    throw new IllegalStateException("TM login failed after " + attempts
                            + " attempt(s) over " + waitTimeout + "s: " + e.getMessage(), e);
                }
                if (attempts == 1 || attempts % 6 == 0) {
                    log.info("[TM] TM not ready yet (attempt {}), retrying in {}ms: {}", attempts, pollInterval, e.getMessage());
                }
                Thread.sleep(pollInterval);
            }
        }

        log.info("[TM] Login OK after {} attempt(s) — token acquired (len={})", attempts, token.length());
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("access_token", token);
        outputs.put("tm_base_url", client.getBaseUrl());
        outputs.put("attempts", attempts);
        return success(outputs);
    }
}
