package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * tm_update_setting Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   key: "jobHeartTimeout"
 *   value: "60000"
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TmUpdateSettingParameter extends ParameterConfig {

    /** Settings 记录的 key — 必填，例如 jobHeartTimeout。 */
    private String key;

    /** 要设置的 value — 必填。纯数字会以 JSON number 发送。 */
    private String value;
}
