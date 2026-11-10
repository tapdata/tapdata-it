package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * scale 参数。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ScaleParameter extends ParameterConfig {

    /** K8s namespace — 必填。 */
    private String namespace;

    /** 目标组件 — server / engine / apiserver — 必填。 */
    private String component;

    /** 目标副本数 — 必填。 */
    private Integer replicas;

    /** 远程 K8s 服务器 host。 */
    private String host = "113.98.206.142";
}
