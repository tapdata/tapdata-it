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

    /** 远程 K8s 服务器 host。 */
    private String host = "113.98.206.142";
}
