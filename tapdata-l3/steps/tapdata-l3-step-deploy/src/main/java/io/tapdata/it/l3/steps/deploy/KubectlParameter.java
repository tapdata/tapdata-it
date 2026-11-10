package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * kubectl 参数。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class KubectlParameter extends ParameterConfig {

    /** K8s namespace — 必填。 */
    private String namespace;

    /** kubectl 子命令，例如 "delete pod xxx" / "get deploy xxx -o jsonpath=..." — 必填。 */
    private String command;

    /** 可选断言：stdout 必须包含该子串，否则 step 失败。 */
    private String expectStdoutContains;

    /** 可选断言：stdout（trim 后）必须完全等于该值，否则 step 失败。 */
    private String expectStdoutEquals;

    /** 可选断言：stdout 不得包含该子串，否则 step 失败。 */
    private String expectStdoutNotContains;

    /** 轮询最大次数（配合断言使用），默认 1 —— 即不重试。 */
    private Integer maxAttempts = 1;

    /** 轮询间隔（秒），默认 3。 */
    private Integer pollIntervalSeconds = 3;

    /** 命令超时（秒），默认 180。 */
    private Integer commandTimeoutSeconds = 180;

    /** 远程 K8s 服务器 host。 */
    private String host = "113.98.206.142";
}
