package io.tapdata.it.l3.env;

/**
 * 部署架构模式：不同模式绑定 {@code resources/deployments/<resourceDir>/} 下的一套清单
 * （需求2："多个不同架构的部署模式用不同 k8s 配置管理"）。
 */
public enum DeploymentMode {

    /** 单机：TM / engine / apiserver 各 1 副本 + 独立 MongoDB。 */
    SINGLE_NODE("single-node"),

    /** 分布式 HA：engine 多副本（含 headless service），TM 亦多副本，验证高可用拓扑。 */
    DISTRIBUTED_HA("distributed-ha");

    private final String resourceDir;

    DeploymentMode(String resourceDir) {
        this.resourceDir = resourceDir;
    }

    /** 对应 {@code src/main/resources/deployments/} 下的子目录名。 */
    public String resourceDir() {
        return resourceDir;
    }

    public static DeploymentMode from(String value) {
        if (value == null || value.trim().isEmpty()) {
            return SINGLE_NODE;
        }
        return DeploymentMode.valueOf(value.trim().toUpperCase().replace('-', '_'));
    }
}
