package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * exec 参数。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ExecParameter extends ParameterConfig {

    /** K8s namespace — 必填。 */
    private String namespace;

    /** Pod 名称（支持前缀匹配，如 "tapdata-server" 会匹配第一个 tapdata-server-xxx）— 必填。 */
    private String pod;

    /** 要执行的 shell 命令 — 必填。 */
    private String command;

    /** 指定容器名（pod 多容器时使用）。 */
    private String container;

    /** 可选断言：stdout 必须包含该子串，否则 step 失败。 */
    private String expectStdoutContains;

    /** 远程 K8s 服务器 host。 */
    private String host = "113.98.206.142";
}
