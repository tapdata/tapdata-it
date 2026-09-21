package io.tapdata.it.l3.env;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分布式 HA 部署模式：TM / engine / apiserver 多副本，engine 额外暴露 headless service
 * （供节点互访/水平扩展），绑定 {@code deployments/distributed-ha/} 清单。
 * <p>
 * 副本数可经 {@code -Dl3.replicas.tm}/{@code l3.replicas.engine}/{@code l3.replicas.api} 覆盖。
 */
public class DistributedHaDeployment implements TapdataDeployment {

    @Override
    public DeploymentMode mode() {
        return DeploymentMode.DISTRIBUTED_HA;
    }

    @Override
    public List<String> resourceFiles() {
        return List.of(
                "10-mongodb.yaml",
                "20-tm.yaml",
                "30-engine.yaml",
                "31-engine-headless.yaml",
                "40-apiserver.yaml");
    }

    @Override
    public Map<String, String> context(L3EnvConfig cfg) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("TM_REPLICAS", String.valueOf(cfg.integer("replicas.tm", 2)));
        ctx.put("ENGINE_REPLICAS", String.valueOf(cfg.integer("replicas.engine", 3)));
        ctx.put("API_REPLICAS", String.valueOf(cfg.integer("replicas.api", 2)));
        return ctx;
    }
}
