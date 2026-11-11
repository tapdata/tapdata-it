package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.BaseStep;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * undeploy_tapdata —— 卸载指定 namespace 下的 tapdata。
 *
 * <pre>
 * inputs:
 *   namespace: tapdata-xxx        # 必填
 *   helm_release: tapdata          # 可选
 *   delete_namespace: true         # 可选，默认 true
 *   cancel_pending_cleanups: true  # 可选，默认 false — 卸载后取消挂起的 "tm" 清理钩子
 *   host: 113.98.206.142
 * </pre>
 */
public class UndeployTapdataAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(UndeployTapdataAction.class);

    @Override
    public String getActionName() {
        return "undeploy_tapdata";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        UndeployTapdataParameter p = config.getInputParameterConfig(UndeployTapdataParameter.class);

        if (p.getNamespace() == null || p.getNamespace().isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }

        K8sClient k8s = new K8sClient(p.getHost());
        String helmRelease = p.getHelmRelease() == null || p.getHelmRelease().isBlank()
                ? "tapdata" : p.getHelmRelease();

        log.info("[undeploy] namespace={} helmRelease={} deleteNs={}",
                p.getNamespace(), helmRelease, p.getDeleteNamespace());

        // 1. helm uninstall（忽略不存在的 release —— 用 --ignore-not-found 3.12+ 才支持，这里先查一下）
        K8sClient.ExecResult status = k8s.exec("helm status " + helmRelease + " -n " + p.getNamespace() + " >/dev/null 2>&1; echo $?");
        int rc = 1;
        try { rc = Integer.parseInt(status.getStdoutTrimmed()); } catch (NumberFormatException ignored) {}

        if (rc == 0) {
            k8s.helm(p.getNamespace(), "uninstall " + helmRelease + " --wait");
            log.info("[undeploy] helm uninstall done");
        } else {
            log.warn("[undeploy] helm release '{}' not found in ns '{}' — skipping", helmRelease, p.getNamespace());
        }

        // 2. 删除 namespace
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("namespace", p.getNamespace());
        outputs.put("helmRelease", helmRelease);
        outputs.put("uninstalled", true);

        if (p.getDeleteNamespace() == null || p.getDeleteNamespace()) {
            k8s.exec("kubectl delete namespace " + p.getNamespace() + " --wait=true --timeout=120s");
            log.info("[undeploy] namespace deleted");
            outputs.put("namespaceDeleted", true);
        }

        // 3. 可选：取消挂起的 "tm" 清理钩子（资源在本环境的 TM 上，环境已卸载 → 清理无意义）
        if (p.getCancelPendingCleanups() != null && p.getCancelPendingCleanups()) {
            int cancelled = ctx.unregisterCleanups("tm");
            if (cancelled > 0) {
                log.info("[undeploy] cancelled {} pending 'tm' cleanup hook(s) — env already torn down", cancelled);
                outputs.put("cancelledCleanupHooks", cancelled);
            }
        }

        return success(outputs);
    }
}
