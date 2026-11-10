package io.tapdata.it.l3.steps.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.BaseStep;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * get_license —— 向 License Server 申请一个新的 License，输出 license 内容。
 *
 * <p>流程：POST {baseUrl}/ldap/login 登录 → POST {baseUrl}/license 申请。
 * 申请的 License 不绑定 SID（试用 License），TM 端跳过机器指纹校验，Pod 重建后依然有效。
 *
 * <pre>
 * inputs:
 *   base_url: http://192.168.1.184:18080        # 必填
 *   username: license-temp                      # 可选，登录 uid
 *   password: Gotapd8!                          # 必填
 *   customer: tapdata-it                        # 可选
 *   reason: L3 automated e2e test               # 可选
 *   sid: ""                                     # 可选，留空 = 试用 License
 *   valid_days: 30                              # 可选
 *   version: 4.23.0                             # 可选
 *   license_type: OP                            # 可选
 *   engine_limit: 3                             # 可选
 *
 * outputs:
 *   license: "..."
 * </pre>
 *
 * <p>部署时直接引用：{@code license: "#{ #steps['fetch_license'].outputs['license'] }"}
 */
public class GetLicenseAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(GetLicenseAction.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String getActionName() {
        return "get_license";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        GetLicenseParameter p = config.getInputParameterConfig(GetLicenseParameter.class);

        if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("base_url is required");
        }
        if (p.getPassword() == null || p.getPassword().isBlank()) {
            throw new IllegalArgumentException("password is required");
        }

        String base = normalizeBaseUrl(p.getBaseUrl());
        log.info("[get_license] requesting license from {}", base);

        // 1. 登录 License Server
        String loginBody = "{\"uid\":\"" + p.getUsername() + "\",\"password\":\"" + p.getPassword() + "\"}";
        JsonNode loginResp = postJson(base + "/ldap/login", loginBody, p.getUsername(), p.getHttpTimeoutSeconds());
        if (loginResp.path("code").asInt(-1) != 200) {
            return failure(new RuntimeException("license server login failed: " + loginResp));
        }

        // 2. 申请 License
        String reqBody = "{\"customer\":\"" + p.getCustomer() + "\","
                + "\"reason\":\"" + p.getReason() + "\","
                + "\"sid\":\"" + (p.getSid() == null ? "" : p.getSid()) + "\","
                + "\"valid_days\":" + p.getValidDays() + ","
                + "\"version\":\"" + p.getVersion() + "\","
                + "\"licenseType\":\"" + p.getLicenseType() + "\","
                + "\"engineLimit\":" + p.getEngineLimit() + "}";
        JsonNode resp = postJson(base + "/license", reqBody, p.getUsername(), p.getHttpTimeoutSeconds());

        JsonNode contentNode = resp.path("data").path("content");
        if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
            return failure(new RuntimeException("license server returned no license content: " + resp));
        }
        String content = contentNode.asText();
        log.info("[get_license] license acquired ({} chars, customer={}, engineLimit={})",
                content.length(), p.getCustomer(), p.getEngineLimit());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("license", content);
        outputs.put("customer", p.getCustomer());
        outputs.put("validDays", p.getValidDays());
        outputs.put("engineLimit", p.getEngineLimit());
        return success(outputs);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String base = baseUrl.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private JsonNode postJson(String url, String body, String uid, int timeoutSeconds) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutSeconds * 1000);
            conn.setReadTimeout(timeoutSeconds * 1000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("uid", uid);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new IllegalStateException("POST " + url + " -> HTTP " + code + ": " + text);
            }
            return JSON.readTree(text);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("POST " + url + " failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
