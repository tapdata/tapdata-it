package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * get_license 参数。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class GetLicenseParameter extends ParameterConfig {

    /** License Server 地址，例如 http://192.168.1.184:18080/ — 必填。 */
    private String baseUrl;

    /** License Server 登录账号（uid）— 默认 license-temp。 */
    private String username = "license-temp";

    /** License Server 登录密码 — 必填。 */
    private String password;

    /** 客户名称，写入 License。 */
    private String customer = "tapdata-it";

    /** 申请原因，写入 License。 */
    private String reason = "L3 automated e2e test";

    /**
     * 机器指纹。留空表示申请试用 License —— TM 端不校验 SID，
     * 适合 K8s 场景（Pod 重建后 IP / MAC 会变，绑定 SID 反而失效）。
     */
    private String sid = "";

    /** 申请的有效天数（试用 License 在 TM 端仍会被限制为首次部署后 14 天）。 */
    private Integer validDays = 30;

    /** License 版本号。 */
    private String version = "4.23.0";

    /** License 类型。 */
    private String licenseType = "OP";

    /** 允许的 Engine 数量上限。 */
    private Integer engineLimit = 3;

    /** HTTP 超时秒数。 */
    private Integer httpTimeoutSeconds = 30;
}
