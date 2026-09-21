package io.tapdata.it.l3.env;

import java.util.List;
import java.util.Map;

/**
 * 一种部署架构模式的清单装配描述（需求2："多个不同架构的部署模式用不同 k8s 配置管理"）。
 * <p>
 * 实现类只提供三件事：<b>模式</b>、<b>该模式目录下按 apply 顺序排列的清单文件</b>、<b>模式特有的渲染变量</b>
 * （副本数等）。命名空间创建、image-pull Secret、NodePort 分配、渲染、apply、就绪等待与探活
 * 均由 {@link DeploymentOrchestrator} 统一内聚，避免各模式重复。
 */
public interface TapdataDeployment {

    DeploymentMode mode();

    /** 相对 {@code deployments/<resourceDir>/} 的清单文件名，按声明顺序 apply。 */
    List<String> resourceFiles();

    /** 模式特有的渲染变量（会与公共变量合并，如 {@code TM_REPLICAS}/{@code ENGINE_REPLICAS}）。 */
    Map<String, String> context(L3EnvConfig cfg);

    /** 按模式解析出对应的 {@link TapdataDeployment} 实现。 */
    static TapdataDeployment forMode(DeploymentMode mode) {
        switch (mode) {
            case DISTRIBUTED_HA:
                return new DistributedHaDeployment();
            case SINGLE_NODE:
            default:
                return new SingleNodeDeployment();
        }
    }
}
