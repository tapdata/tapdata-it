package io.tapdata.it.l3.env;

import java.util.List;
import java.util.Map;

/**
 * 单机部署模式：TM / engine / apiserver 各 1 副本，绑定 {@code deployments/single-node/} 清单。
 */
public class SingleNodeDeployment implements TapdataDeployment {

    @Override
    public DeploymentMode mode() {
        return DeploymentMode.SINGLE_NODE;
    }

    @Override
    public List<String> resourceFiles() {
        return List.of(
                "10-mongodb.yaml",
                "20-tm.yaml",
                "30-engine.yaml",
                "40-apiserver.yaml");
    }

    @Override
    public Map<String, String> context(L3EnvConfig cfg) {
        return Map.of(
                "TM_REPLICAS", "1",
                "ENGINE_REPLICAS", "1",
                "API_REPLICAS", "1");
    }
}
