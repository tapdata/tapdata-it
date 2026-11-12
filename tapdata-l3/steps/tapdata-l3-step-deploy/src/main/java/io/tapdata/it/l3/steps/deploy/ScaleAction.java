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
 * scale —— 对指定组件（server / engine / apiserver）扩 / 缩副本。
 *
 * <pre>
 * inputs:
 *   namespace: tapdata-xxx      # 必填
 *   component: engine            # 必填，server / engine / apiserver
 *   replicas: 3                  # 必填
 *   host: 113.98.206.142         # 可选，默认值
 * </pre>
 */
public class ScaleAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(ScaleAction.class);

    @Override
    public String getActionName() {
        return "scale";
    }

    private static final Map<String, String> COMPONENT_TO_DEPLOYMENT = Map.of(
            "server", "tapdata-server",
            "engine", "tapdata-engine",
            "apiserver", "tapdata-apiserver"
    );

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        ScaleParameter p = config.getInputParameterConfig(ScaleParameter.class);

        // ---- 校验 ----
        if (p.getNamespace() == null || p.getNamespace().isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        if (p.getComponent() == null || !COMPONENT_TO_DEPLOYMENT.containsKey(p.getComponent().toLowerCase())) {
            throw new IllegalArgumentException("component must be one of: server / engine / apiserver (got: " + p.getComponent() + ")");
        }
        if (p.getReplicas() == null || p.getReplicas() < 0) {
            throw new IllegalArgumentException("replicas must be >= 0");
        }

        String deployment = COMPONENT_TO_DEPLOYMENT.get(p.getComponent().toLowerCase());
        K8sClient k8s = new K8sClient(p.getHost());

        log.info("[scale] namespace={} component={} deployment={} -> replicas={}",
                p.getNamespace(), p.getComponent(), deployment, p.getReplicas());

        // 1. kubectl scale
        k8s.kubectl(p.getNamespace(),
                "scale deployment " + deployment + " --replicas=" + p.getReplicas())
                .assertSuccess("kubectl scale " + deployment);

        // 2. 等 rollout 完成（replicas=0 时跳过）
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("component", p.getComponent());
        outputs.put("replicas", p.getReplicas());

        if (p.getReplicas() > 0) {
            k8s.kubectl(p.getNamespace(),
                    "rollout status deployment " + deployment + " --timeout=300s")
                    .assertSuccess("kubectl rollout status " + deployment);

            // 3. 读 ready replicas
            K8sClient.ExecResult r = k8s.kubectl(p.getNamespace(),
                    "get deployment " + deployment + " -o jsonpath='{.status.readyReplicas}'");
            r.assertSuccess("kubectl get deployment " + deployment);
            outputs.put("readyReplicas", r.getStdoutTrimmed());
        }

        log.info("[scale] done — component={} replicas={}", p.getComponent(), p.getReplicas());
        return success(outputs);
    }
}
