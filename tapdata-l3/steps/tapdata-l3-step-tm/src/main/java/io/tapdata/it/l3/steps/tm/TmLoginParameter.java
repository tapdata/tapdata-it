package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * tm_login Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   wait_timeout_seconds: 300   # 等待 TM 就绪的最大时长；默认 180
 *   poll_interval_ms: 5000      # 重试间隔；默认 5000
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TmLoginParameter extends ParameterConfig {

    /** 等待 TM 就绪的最大时长 (秒) — 默认 180。 */
    private int waitTimeoutSeconds = 180;

    /** 重试间隔 (毫秒) — 默认 5000。 */
    private int pollIntervalMs = 5000;
}
