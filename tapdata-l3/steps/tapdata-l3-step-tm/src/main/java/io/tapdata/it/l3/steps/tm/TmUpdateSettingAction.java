package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Upsert a TM Settings entry (key/value) — e.g. tune jobHeartTimeout for HA failover tests.
 * <p>
 * 查询 {@code GET /api/Settings?filter={"where":{"key":"..."}}}:
 * 已存在则 {@code PATCH /api/Settings/{id}} 更新 value, 否则 {@code POST /api/Settings} 新建。
 * 数字形式的 value 以 JSON number 发送, 其余以 string 发送 (TM 端 parseInt 两者兼容)。
 *
 * <pre>
 * - id: tune_heartbeat
 *   action: tm_update_setting
 *   inputs:
 *     key: "jobHeartTimeout"
 *     value: "60000"
 * </pre>
 *
 * Outputs: {@code key}, {@code value}, {@code setting_id}, {@code operation}
 */
public class TmUpdateSettingAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmUpdateSettingAction.class);

    private TmClient client;

    /** 无参构造器 — 反射实例化必备。 */
    public TmUpdateSettingAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) {
            client = new TmClient();
        }
    }

    @Override
    public String getActionName() {
        return "tm_update_setting";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        TmUpdateSettingParameter p = config.getInputParameterConfig(TmUpdateSettingParameter.class);
        if (p.getKey() == null || p.getKey().isBlank()) {
            throw new IllegalArgumentException("Required input missing: key");
        }
        if (p.getValue() == null || p.getValue().isBlank()) {
            throw new IllegalArgumentException("Required input missing: value");
        }

        Object typedValue = toTypedValue(p.getValue());

        String filter = URLEncoder.encode("{\"where\":{\"key\":\"" + p.getKey() + "\"}}",
                StandardCharsets.UTF_8);
        JsonNode existing = client.get("/api/Settings?filter=" + filter);

        String settingId;
        String operation;
        if (existing != null && existing.isArray() && existing.size() > 0) {
            JsonNode row = existing.get(0);
            settingId = text(row, "id");
            Object oldValue = row.has("value") && !row.get("value").isNull()
                    ? row.get("value").isNumber() ? row.get("value").asLong() : row.get("value").asText()
                    : null;
            log.info("[TM] Settings '{}' found: id={}, oldValue={} → updating to {}", p.getKey(), settingId, oldValue, typedValue);
            // Settings 不支持 PATCH/PUT — 用 POST /api/Settings/update?where={"key":"..."} 更新
            String where = URLEncoder.encode("{\"key\":\"" + p.getKey() + "\"}", StandardCharsets.UTF_8);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("value", typedValue);
            client.post("/api/Settings/update?where=" + where, body);
            operation = "updated";
        } else {
            log.info("[TM] Settings '{}' not found → creating with value {}", p.getKey(), typedValue);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", p.getKey());
            body.put("value", typedValue);
            JsonNode created = client.post("/api/Settings", body);
            settingId = text(created, "id");
            operation = "created";
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("key", p.getKey());
        outputs.put("value", p.getValue());
        outputs.put("setting_id", settingId);
        outputs.put("operation", operation);
        return success(outputs);
    }

    /** 纯数字 → Long，否则保留原始字符串。 */
    private static Object toTypedValue(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static String text(JsonNode n, String key) {
        return n != null && n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
