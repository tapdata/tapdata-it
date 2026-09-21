package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmApiException;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.http.HttpMethod;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * TM API 基类：内聚所有 HTTP 传输与 {@link TmResponse} 拆包细节，子类只需声明语义方法
 * （对应 Controller 的一个端点），不关心 URL 拼接、序列化、错误码判定。
 * <p>
 * 请求体既支持类型化 DTO（经 {@link TmJson} 序列化），也支持原样 JSON 字符串
 * （便于用例以版本无关的 JSON 模板构造带多态 DAG 的复杂请求，框架不臆测服务端类型判定）。
 */
public abstract class AbstractTmApi {

    protected final TmApiContext ctx;

    protected AbstractTmApi(TmApiContext ctx) {
        this.ctx = ctx;
    }

    // ---- 原始动词（返回响应体字符串）----
    // 命名带 Raw 后缀：与子类的语义方法（如 TaskAPI#delete(id)、DataSourceAPI#delete(id)）区分，避免返回类型冲突。

    protected String getRaw(String path) {
        return ctx.exchange(HttpMethod.GET, path, null);
    }

    protected String deleteRaw(String path) {
        return ctx.exchange(HttpMethod.DELETE, path, null);
    }

    protected String postRaw(String path, Object body) {
        return ctx.exchange(HttpMethod.POST, path, toBodyString(body));
    }

    protected String putRaw(String path, Object body) {
        return ctx.exchange(HttpMethod.PUT, path, toBodyString(body));
    }

    protected String patchRaw(String path, Object body) {
        return ctx.exchange(HttpMethod.PATCH, path, toBodyString(body));
    }

    // ---- 拆包：发请求 + 校验业务码 + 返回 data ----

    /** 发起请求并返回完整 {@link TmResponse}（含 code/message/data），非成功码抛 {@link TmApiException}。 */
    protected <T> TmResponse<T> call(HttpMethod method, String path, Object body, TypeReference<TmResponse<T>> type) {
        String json = ctx.exchange(method, path, toBodyString(body));
        if (json == null || json.trim().isEmpty()) {
            throw new TmApiException("Empty response body from TM for " + method + " " + path);
        }
        TmResponse<T> response = TmJson.read(json, type);
        if (!response.isSuccess()) {
            throw new TmApiException(
                    "TM API error [" + response.getCode() + "]: " + response.getMessage() + " @ " + method + " " + path,
                    response.getCode());
        }
        return response;
    }

    /** 发起请求并直接返回业务数据 {@code data}。 */
    protected <T> T data(HttpMethod method, String path, Object body, TypeReference<TmResponse<T>> type) {
        return call(method, path, body, type).getData();
    }

    /** 拼接查询参数（自动 URL 编码），用于 {@code ?filter=} 等。 */
    protected static String withQuery(String path, String key, String value) {
        if (value == null) {
            return path;
        }
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return path + (path.contains("?") ? "&" : "?") + key + "=" + encoded;
    }

    /**
     * 从响应数据 Map 中提取主键 id。TM 的 {@code id} 为 Mongo ObjectId，序列化形态可能是十六进制串
     * 或 {@code {"$oid":".."}}，此处统一归一为字符串，屏蔽差异。
     */
    protected static String extractId(java.util.Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object id = data.containsKey("id") ? data.get("id") : data.get("_id");
        return stringifyId(id);
    }

    /** 归一 id 表示：{@code ".."} / {@code {"$oid":".."}} / ObjectId → 十六进制字符串。 */
    protected static String stringifyId(Object id) {
        if (id == null) {
            return null;
        }
        if (id instanceof java.util.Map) {
            Object oid = ((java.util.Map<?, ?>) id).get("$oid");
            return oid == null ? String.valueOf(id) : oid.toString();
        }
        return id.toString();
    }

    private static String toBodyString(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String) {
            return (String) body; // 原样 JSON 模板
        }
        return TmJson.write(body);
    }
}
