package io.tapdata.it.l3.api;

import io.tapdata.it.l3.api.model.TmApiException;
import com.tapdata.tm.sdk.available.TmAvailableRestTemplate;
import com.tapdata.tm.sdk.interceptor.VersionHeaderInterceptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * TM API 传输上下文：持有 {@link TmAvailableRestTemplate}（tm-sdk 提供，附带 TM 可用性探活语义），
 * 负责将一次语义调用转换为原始 HTTP 请求并返回响应体字符串，交由 {@link AbstractTmApi} 统一拆包。
 * <p>
 * 传输层采用 JDK {@link HttpClient}（{@link JdkClientHttpRequestFactory}）而非默认的
 * {@code SimpleClientHttpRequestFactory} —— 后者基于 {@code HttpURLConnection}，禁止 PATCH 方法，
 * 而 TM 的 {@code confirm}/{@code confirmStart}/{@code update} 等端点为 PATCH；JDK HttpClient 无此限制，
 * 且无需额外引入 Apache HttpClient（tm-common 传递的 spring-web 7.x 只对接 HttpClient 5，依赖更重）。
 * <p>
 * <b>认证口径</b>：TM 的 {@code LoginUserResolver#doResolve} 依次认可 {@code user_id} 头、
 * <b>查询串 {@code access_token}</b>、{@code authorization} 头，<b>不读</b>{@code access-token} 头；
 * 故令牌由 {@link #authenticated(URI)} 统一拼到请求地址上（登录类接口无令牌时自动跳过）。
 * 请求头栈仅 {@link VersionHeaderInterceptor}（版本 UA）；
 * 使用 {@link BufferingClientHttpRequestFactory} 包装，使拦截器可重复读取响应体。
 */
public class TmApiContext {

    /** TM 令牌查询参数名（对齐 {@code LoginUserResolver} 的 {@code access_token} 解析）。 */
    private static final String ACCESS_TOKEN_PARAM = "access_token";

    private final String baseUrl;
    private final TmAvailableRestTemplate rest;
    private volatile String accessToken;

    public TmApiContext(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = stripTrailingSlash(baseUrl);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.rest = new TmAvailableRestTemplate(new BufferingClientHttpRequestFactory(factory));
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new VersionHeaderInterceptor());
        this.rest.setInterceptors(interceptors);
    }

    /** 归一后的基址（无尾斜杠），形如 {@code http://<nodeIP>:<nodePort>/api}。 */
    public String baseUrl() {
        return baseUrl;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    /**
     * 发起原始 JSON HTTP 请求，返回响应体字符串（可能为 TM 宕机时的合成 503 体，交由拆包层判定）。
     *
     * @param method HTTP 方法
     * @param path   相对 {@link #baseUrl()} 的路径，可含已编码的查询串（如 {@code /task?filter=%7B..%7D}）
     * @param body   请求体 JSON 字符串（无体传 null）
     */
    public String exchange(HttpMethod method, String path, String body) {
        URI uri = authenticated(URI.create(baseUrl + path));
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<String> entity;
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            entity = new HttpEntity<>(body, headers);
        } else {
            entity = new HttpEntity<>(headers);
        }
        return doExchange(uri, method, entity, path);
    }

    /**
     * 发起 {@code multipart/form-data} 请求（供 {@code PdkAPI.uploadJar} 上传 connector jar）。
     *
     * @param path  相对 {@link #baseUrl()} 的路径
     * @param parts 表单部件：字段名 -> 值；值可为标量、{@link org.springframework.core.io.Resource}（文件）
     *              或其 {@link Iterable}（如重复的 {@code source} JSON、多个 {@code file}）
     */
    public String exchangeMultipart(HttpMethod method, String path, Map<String, ?> parts) {
        URI uri = authenticated(URI.create(baseUrl + path));
        org.springframework.util.MultiValueMap<String, Object> body =
                new org.springframework.util.LinkedMultiValueMap<>();
        if (parts != null) {
            parts.forEach((k, v) -> {
                if (v instanceof Iterable) {
                    ((Iterable<?>) v).forEach(item -> body.add(k, item));
                } else if (v != null) {
                    body.add(k, v);
                }
            });
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        return doExchange(uri, method, new HttpEntity<>(body, headers), path);
    }

    private String doExchange(URI uri, HttpMethod method, HttpEntity<?> entity, String path) {
        ResponseEntity<String> response;
        try {
            response = rest.exchange(uri, method, entity, String.class);
        } catch (RuntimeException e) {
            throw new TmApiException("TM request failed: " + method + " " + uri + " (" + e.getMessage() + ")", null, e);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new TmApiException("TM returned HTTP " + response.getStatusCode() + " for " + path, null);
        }
        return response.getBody();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 为请求地址附加 {@code access_token} 查询参数（TM 认可的令牌载体）；未登录或已自带该参数时原样返回。
     */
    private URI authenticated(URI uri) {
        String token = accessToken;
        if (token == null || token.isEmpty()) {
            return uri;
        }
        String query = uri.getRawQuery();
        if (query != null && query.contains(ACCESS_TOKEN_PARAM + "=")) {
            return uri;
        }
        String joined = uri.toString() + (query == null ? "?" : "&") + ACCESS_TOKEN_PARAM + "=" + token;
        return URI.create(joined);
    }
}
