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

    /** Default TM for L3 IT environments — can be overridden via config/env/property. */
    private static final String DEFAULT_URL = "http://113.98.206.140:24021";
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

        this.baseUrl = System.getProperty("tm.url",
                System.getenv("TM_URL") != null ? System.getenv("TM_URL")
                        : url != null ? url : DEFAULT_URL);
        this.accessCode = System.getProperty("tm.access.code",
                System.getenv("TM_ACCESS_CODE") != null ? System.getenv("TM_ACCESS_CODE")
                        : accessCode != null ? accessCode : DEFAULT_ACCESS_CODE);

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("[TM] Initialized: baseUrl={}", baseUrl);
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
            throw new IllegalStateException("TM token refresh failed: " + e.getMessage(), e);
        }
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

    /** DELETE {path} with optional query params. */
    public JsonNode delete(String path, Map<String, Object> queryParams) throws IOException, InterruptedException {
        return send("DELETE", path, queryParams, null);
    }

    /** DELETE {path} with no extra query params. */
    public JsonNode delete(String path) throws IOException, InterruptedException {
        return delete(path, null);
    }

    // ==================== Internal ====================

    /** Send request, parse TM ResponseMessage, throw on non-ok code. */
    private JsonNode send(String method, String path, Map<String, Object> queryParams, Object body)
            throws IOException, InterruptedException {
        String token = getToken();
        String url = buildUrl(path, token, queryParams);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token);

        if (body != null) {
            reqBuilder.header("Content-Type", "application/json");
            reqBuilder.method(method,
                    HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        } else {
            reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        log.debug("[TM] {} {}", method, url);
        HttpResponse<String> resp = http.send(reqBuilder.timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 300) {
            throw new IOException("TM HTTP " + resp.statusCode() + " on " + url + ": " + resp.body());
        }

        JsonNode root = MAPPER.readTree(resp.body());
        String code = root.has("code") ? root.get("code").asText() : "";
        if (!"ok".equals(code)) {
            String msg = root.has("message") ? root.get("message").asText() : resp.body();
            throw new IOException("TM API error on " + path + ": code=" + code + ", message=" + msg);
        }
        return root.has("data") ? root.get("data") : root;
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

    private record CachedToken(String token, long expiresAt) {}
}
