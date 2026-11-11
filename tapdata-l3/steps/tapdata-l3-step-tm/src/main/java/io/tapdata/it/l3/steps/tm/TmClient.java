package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight HTTP client for TapData TM (Manager) REST API.
 * <p>
 * <b>Auth</b>: exchanges an {@code accessCode} for an opaque bearer token via
 * {@code POST /api/users/generatetoken}, caches the token until near expiry,
 * and appends it as {@code ?access_token=} to every subsequent request.
 * <p>
 * <b>Config</b>: reads from config file (~/.tapdata-tm) or env vars / system props:
 * <pre>
 *   Config file format:  url = http://...   access_code = xxxxxxxx-xxxx-xxxx
 *   Env vars:            TM_URL, TM_ACCESS_CODE
 *   System props:        -Dtm.url=...  -Dtm.access.code=...
 * </pre>
 * <p>
 * <b>Response contract</b>: TM always returns {@code {code, message, data, reqId, ts}}.
 * This client treats any {@code code != "ok"} as an error.
 */
public class TmClient {

    private static final Logger log = LoggerFactory.getLogger(TmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long REFRESH_BUFFER_MS = 60_000L;
    /** 网关错误 (502/503/504) 重试次数与间隔 — 覆盖多 TM 节点滚动重启的瞬时不就绪。 */
    private static final int GATEWAY_RETRY_MAX = 12;
    private static final long GATEWAY_RETRY_INTERVAL_MS = 5_000L;

    /** Default TM for L3 IT environments — can be overridden via config/env/property. */
    private static final String DEFAULT_URL = "http://192.168.1.190:24021";
    private static final String DEFAULT_ACCESS_CODE = "3324cfdf-7d3e-4792-bd32-571638d4562f";

    private final String baseUrl;
    private final String accessCode;
    private final HttpClient http;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public TmClient() {
        // Multi-layer config resolution (mirrors DbforgeClient convention)
        String configPath = System.getProperty("tm.config",
                System.getenv("TM_CONFIG") != null ? System.getenv("TM_CONFIG")
                        : System.getProperty("user.home") + "/.tapdata-tm");
        String url = null, accessCode = null;
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(configPath);
            if (java.nio.file.Files.exists(p)) {
                for (String line : java.nio.file.Files.readAllLines(p)) {
                    line = line.trim();
                    if (line.isBlank() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String k = line.substring(0, eq).trim();
                        String v = line.substring(eq + 1).trim();
                        if ("url".equalsIgnoreCase(k)) url = v;
                        if ("access_code".equalsIgnoreCase(k) || "accesscode".equalsIgnoreCase(k)) accessCode = v;
                    }
                }
            }
        } catch (IOException ignored) {}

        this.baseUrl = resolveConfig("tm.url",
                new String[]{"TM_BASE_URL", "TM_URL"},
                new String[]{"TM_URL"},
                url, DEFAULT_URL);
        this.accessCode = resolveConfig("tm.access.code",
                new String[]{"TM_ACCESS_CODE"},
                new String[]{"TM_ACCESS_CODE"},
                accessCode, DEFAULT_ACCESS_CODE);

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("[TM] Initialized: baseUrl={}", baseUrl);
    }

    /** Multi-layer config resolution — covers -D flag, DSL env (ScenarioRunner 注入), system env, config file, default.
     *  lowerKey  — System.getProperty 小写 key (tm.url, 最高优先级)
     *  propAliases — System.getProperty 大写别名 (TM_BASE_URL, TM_URL — DSL env 注入到 System Properties 的)
     *  envAliases — System.getenv 大写别名 (TM_URL — OS 环境变量)
     *  configFileValue — ~/.tapdata-tm 文件里读到的值 (可为 null)
     *  defaultValue — 硬编码默认值 (兜底) */
    private static String resolveConfig(String lowerKey, String[] propAliases, String[] envAliases,
                                        String configFileValue, String defaultValue) {
        String v;
        if ((v = System.getProperty(lowerKey)) != null) return v;
        for (String alias : propAliases) {
            if ((v = System.getProperty(alias)) != null) return v;
        }
        for (String alias : envAliases) {
            String env = System.getenv(alias);
            if (env != null && !env.isBlank()) return env;
        }
        if (configFileValue != null && !configFileValue.isBlank()) return configFileValue;
        return defaultValue;
    }

    // ==================== Auth ====================

    /** Get cached token or refresh if near expiry. */
    public String getToken() {
        CachedToken c = cached.get();
        long now = System.currentTimeMillis();
        if (c != null && c.expiresAt - now > REFRESH_BUFFER_MS) {
            return c.token;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        CachedToken c = cached.get();
        long now = System.currentTimeMillis();
        if (c != null && c.expiresAt - now > REFRESH_BUFFER_MS) {
            return c.token;
        }
        Exception lastError = null;
        for (int attempt = 1; attempt <= GATEWAY_RETRY_MAX; attempt++) {
            try {
                Map<String, Object> body = Map.of("accesscode", accessCode);
                JsonNode resp = postRaw("/api/users/generatetoken", null, body);
                JsonNode data = resp.get("data");
                String token = data.get("id").asText();
                long ttlSeconds = data.has("ttl") ? data.get("ttl").asLong() : 1209600;
                long createdMs = System.currentTimeMillis();
                long expiresAt = createdMs + ttlSeconds * 1000L;
                cached.set(new CachedToken(token, expiresAt));
                log.info("[TM] Token acquired, ttl={}s, expiresIn={}min", ttlSeconds, (expiresAt - createdMs) / 60000);
                return token;
            } catch (Exception e) {
                lastError = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean gateway = msg.contains("HTTP 502") || msg.contains("HTTP 503") || msg.contains("HTTP 504");
                if (!gateway || attempt == GATEWAY_RETRY_MAX) {
                    break;
                }
                log.warn("[TM] generatetoken gateway error (attempt {}/{}), retrying in {}ms", attempt, GATEWAY_RETRY_MAX, GATEWAY_RETRY_INTERVAL_MS);
                try {
                    Thread.sleep(GATEWAY_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("TM token refresh failed: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ==================== HTTP verbs ====================

    /** GET {path} with optional query params (excluding access_token, auto-appended). */
    public JsonNode get(String path, Map<String, Object> queryParams) throws IOException, InterruptedException {
        return send("GET", path, queryParams, null);
    }

    /** GET {path} with no extra query params. */
    public JsonNode get(String path) throws IOException, InterruptedException {
        return get(path, null);
    }

    /** POST {path} with optional query params + JSON body. */
    public JsonNode post(String path, Map<String, Object> queryParams, Object body) throws IOException, InterruptedException {
        return send("POST", path, queryParams, body);
    }

    /** POST {path} with no extra query params. */
    public JsonNode post(String path, Object body) throws IOException, InterruptedException {
        return post(path, null, body);
    }

    /** PUT {path} with optional query params + JSON body. */
    public JsonNode put(String path, Map<String, Object> queryParams, Object body) throws IOException, InterruptedException {
        return send("PUT", path, queryParams, body);
    }

    /** PATCH {path} with JSON body. 使用 curl 子进程因为 Java HttpClient PATCH 有请求体丢失的 bug，且 PluginClassLoader 隔离导致无法引入 OkHttp. */
    public JsonNode patch(String path, Object body) throws IOException, InterruptedException {
        String token = getToken();
        String url = buildUrl(path, token, null);
        String jsonBody = MAPPER.writeValueAsString(body);
        log.debug("[TM] >>> PATCH {} body={}", url, jsonBody);

        // 使用 curl 子进程 — 这是唯一在 PluginClassLoader 隔离下可靠发送 PATCH 的方式
        ProcessBuilder pb = new ProcessBuilder("curl", "-s", "-X", "PATCH",
                "-H", "Content-Type: application/json",
                "-H", "Authorization: Bearer " + token,
                "-d", jsonBody,
                url);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String respBody = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = p.waitFor();

        if (exitCode != 0) {
            throw new IOException("curl PATCH failed (exit=" + exitCode + "): " + respBody);
        }
        log.debug("[TM] <<< PATCH {} body={}", url, respBody.length() > 300 ? respBody.substring(0, 300) + "..." : respBody);

        JsonNode root = MAPPER.readTree(respBody);
        String respCode = root.has("code") ? root.get("code").asText() : "";
        if (!"ok".equals(respCode)) {
            String msg = root.has("message") ? root.get("message").asText() : respBody;
            throw new IOException("TM API error on " + path + ": code=" + respCode + ", message=" + msg);
        }
        return root.has("data") ? root.get("data") : root;
    }

    /** DELETE {path} with optional query params. */
    public JsonNode delete(String path, Map<String, Object> queryParams) throws IOException, InterruptedException {
        return send("DELETE", path, queryParams, null);
    }

    /** DELETE {path} with no extra query params. */
    public JsonNode delete(String path) throws IOException, InterruptedException {
        return delete(path, null);
    }

    // ==================== Internal ====================

    /**
     * Send request, parse TM ResponseMessage, throw on non-ok code.
     * 对 502/503/504 做有限重试 — 多 TM 节点滚动重启期间，NodePort 可能短暂命中未就绪节点。
     */
    private JsonNode send(String method, String path, Map<String, Object> queryParams, Object body)
            throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= GATEWAY_RETRY_MAX; attempt++) {
            try {
                return sendOnce(method, path, queryParams, body);
            } catch (NotLoginException e) {
                // 多 TM 节点下 token 可能被某节点拒绝（刚扩容的节点元数据未同步等）— 强制刷新 token 重试
                if (attempt == GATEWAY_RETRY_MAX) {
                    throw e;
                }
                lastError = e;
                cached.set(null);
                log.warn("[TM] {} {} → not-login (attempt {}/{}), refreshing token and retrying in {}ms",
                        method, path, attempt, GATEWAY_RETRY_MAX, GATEWAY_RETRY_INTERVAL_MS);
                Thread.sleep(GATEWAY_RETRY_INTERVAL_MS);
            } catch (IOException e) {
                if (!isGatewayError(e) || attempt == GATEWAY_RETRY_MAX) {
                    throw e;
                }
                lastError = e;
                log.warn("[TM] {} {} → gateway error (attempt {}/{}), retrying in {}ms: {}",
                        method, path, attempt, GATEWAY_RETRY_MAX, GATEWAY_RETRY_INTERVAL_MS, e.getMessage());
                Thread.sleep(GATEWAY_RETRY_INTERVAL_MS);
            }
        }
        throw lastError != null ? lastError : new IOException("TM request failed: " + method + " " + path);
    }

    private JsonNode sendOnce(String method, String path, Map<String, Object> queryParams, Object body)
            throws IOException, InterruptedException {
        String token = getToken();
        String url = buildUrl(path, token, queryParams);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token);

        if (body != null) {
            reqBuilder.header("Content-Type", "application/json");
            String jsonBody = MAPPER.writeValueAsString(body);
            log.debug("[TM] >>> {} {} body={}", method, url, jsonBody);
            reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            log.debug("[TM] >>> {} {}", method, url);
            reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        // 120s 超时 — re-schemas 等接口需要去数据源加载 schema，比较慢
        HttpResponse<String> resp = http.send(reqBuilder.timeout(Duration.ofSeconds(120)).build(),
                HttpResponse.BodyHandlers.ofString());

        log.debug("[TM] <<< {} {} → {} body={}", method, url, resp.statusCode(), resp.body().length() > 300 ? resp.body().substring(0, 300) + "..." : resp.body());

        if (resp.statusCode() == 502 || resp.statusCode() == 503 || resp.statusCode() == 504) {
            throw new GatewayException("TM HTTP " + resp.statusCode() + " on " + url + ": " + resp.body());
        }
        if (resp.statusCode() == 401) {
            throw new NotLoginException("TM HTTP 401 on " + url + ": " + resp.body());
        }
        if (resp.statusCode() >= 300) {
            throw new IOException("TM HTTP " + resp.statusCode() + " on " + url + ": " + resp.body());
        }

        JsonNode root = MAPPER.readTree(resp.body());
        String code = root.has("code") ? root.get("code").asText() : "";
        if ("NotLogin".equals(code)) {
            throw new NotLoginException("TM API NotLogin on " + path + ": " + resp.body());
        }
        if (!"ok".equals(code)) {
            String msg = root.has("message") ? root.get("message").asText() : resp.body();
            throw new IOException("TM API error on " + path + ": code=" + code + ", message=" + msg);
        }
        return root.has("data") ? root.get("data") : root;
    }

    private static boolean isGatewayError(IOException e) {
        return e instanceof GatewayException;
    }

    /** 网关类错误（上游未就绪），可重试。 */
    private static class GatewayException extends IOException {
        GatewayException(String message) {
            super(message);
        }
    }

    /** 401 / NotLogin — token 被某节点拒绝，清缓存刷新后可重试。 */
    private static class NotLoginException extends IOException {
        NotLoginException(String message) {
            super(message);
        }
    }

    /** Raw POST that doesn't parse ResponseMessage — used for token refresh (needs full response). */
    private JsonNode postRaw(String path, Map<String, Object> queryParams, Object body)
            throws IOException, InterruptedException {
        String token = null; // generatetoken 不需要 token，但 TM 也不拒绝带 token
        String url = buildUrl(path, token, queryParams);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IOException("TM HTTP " + resp.statusCode() + " on " + url + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body());
    }

    private String buildUrl(String path, String token, Map<String, Object> extraParams) {
        String prefix = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path.startsWith("/") ? path : "/" + path;
        StringBuilder url = new StringBuilder(prefix).append(suffix);
        boolean first = !url.toString().contains("?");
        if (extraParams != null) {
            for (Map.Entry<String, Object> e : extraParams.entrySet()) {
                if (e.getValue() == null) continue;
                url.append(first ? '?' : '&');
                first = false;
                url.append(encode(e.getKey())).append('=').append(encode(String.valueOf(e.getValue())));
            }
        }
        if (token != null) {
            url.append(first ? '?' : '&').append("access_token=").append(token);
        }
        return url.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ==================== Debug / Diagnostic helpers ====================

    /**
     * Dump task milestone progression + key status fields for debugging.
     * 使用场景: wait_task 超时或 task 到 error 终态时调用, 快速定位哪个阶段失败.
     */
    public String dumpTask(String taskId) {
        try {
            JsonNode task = get("/api/Task/" + taskId);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Task [%s] %s:\n", taskId, text(task, "name")));
            sb.append(String.format("  flowStatus=%s, status=%s, agentId=%s\n",
                    text(task, "flowStatus"), text(task, "status"), text(task, "agentId")));

            // Milestone — 最有用的诊断信息
            JsonNode milestone = task.path("attrs").path("milestone");
            if (milestone.isObject()) {
                sb.append("  Milestones:\n");
                milestone.fields().forEachRemaining(entry -> {
                    String stage = entry.getKey();
                    JsonNode info = entry.getValue();
                    String status = text(info, "status");
                    String errorCode = text(info, "errorCode");
                    String errorMsg = text(info, "errorMessage");
                    sb.append(String.format("    %s: status=%s, errorCode=%s, errorMsg=%s\n",
                            stage, status, errorCode,
                            errorMsg != null && errorMsg.length() > 200 ? errorMsg.substring(0, 200) + "..." : errorMsg));
                });
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to dump task " + taskId + ": " + e.getMessage();
        }
    }

    /** Dump Inspect detail — 帮助排查校验失败/超时. */
    public String dumpInspect(String inspectId) {
        try {
            JsonNode insp = get("/api/Inspects/find/" + inspectId);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Inspect [%s] %s:\n", inspectId, text(insp, "name")));
            sb.append(String.format("  status=%s, result=%s, agentId=%s, agentTags=%s\n",
                    text(insp, "status"), text(insp, "result"),
                    text(insp, "agentId"), insp.path("agentTags")));
            String err = text(insp, "errorMsg");
            if (err != null) {
                sb.append("  errorMsg: ").append(err.length() > 500 ? err.substring(0, 500) + "..." : err).append("\n");
            }
            JsonNode tasks = insp.path("tasks");
            if (tasks.isArray()) {
                sb.append(String.format("  Tasks (%d):\n", tasks.size()));
                tasks.forEach(t -> {
                    sb.append(String.format("    src=%s→tgt=%s: result=%s\n",
                            t.path("source").path("table").asText("?"),
                            t.path("target").path("table").asText("?"),
                            t.path("result").asText("?")));
                });
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to dump inspect " + inspectId + ": " + e.getMessage();
        }
    }

    /** Dump Connection detail — 帮助排查连接测试失败. */
    public String dumpConnection(String connId) {
        try {
            JsonNode conn = get("/api/Connections/" + connId);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Connection [%s] %s:\n", connId, text(conn, "name")));
            sb.append(String.format("  databaseType=%s, status=%s, accessNodeType=%s, accessNodeProcessId=%s\n",
                    text(conn, "databaseType"), text(conn, "status"),
                    text(conn, "accessNodeType"), text(conn, "accessNodeProcessId")));
            // Alarm info
            JsonNode alarms = conn.path("alarmInfos");
            if (alarms.isArray() && alarms.size() > 0) {
                sb.append("  Alarms:\n");
                alarms.forEach(a -> sb.append("    ").append(a).append("\n"));
            }
            JsonNode attrs = conn.path("attrs");
            if (!attrs.isMissingNode()) {
                sb.append("  attrs.loadFieldsStatus=").append(text(attrs, "loadFieldsStatus")).append("\n");
                sb.append("  attrs.tableCount=").append(text(attrs, "tableCount")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to dump connection " + connId + ": " + e.getMessage();
        }
    }

    private static String text(JsonNode n, String key) {
        return n != null && n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }

    private record CachedToken(String token, long expiresAt) {}
}
