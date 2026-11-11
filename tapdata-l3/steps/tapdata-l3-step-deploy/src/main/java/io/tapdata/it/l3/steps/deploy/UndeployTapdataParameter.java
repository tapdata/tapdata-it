package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * undeploy_tapdata 参数。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UndeployTapdataParameter extends ParameterConfig {

    /** 要卸载的 namespace — 必填。 */
    private String namespace;

    /** Helm release 名称 — 默认 tapdata。 */
    private String helmRelease = "tapdata";

    /** 是否同时删除 namespace — 默认 true。 */
    private Boolean deleteNamespace = true;

    /**
     * 卸载成功后取消所有挂起中的 "tm" 标签清理钩子（TM 任务/连接/校验的自动删除）。
     * 适用场景：这些 TM 资源创建在**本环境内**的 TM 上，环境卸载后它们已无法访问，
     * 保留钩子只会触发无意义的重试风暴（即使有熔断也要等 ~1min）。
     * 默认 false — 共享 TM（192.168.1.190）场景的资源仍需正常清理。
     */
    private Boolean cancelPendingCleanups = false;

    /** 远程 K8s 服务器 host。 */
    private String host = "113.98.206.142";
}
