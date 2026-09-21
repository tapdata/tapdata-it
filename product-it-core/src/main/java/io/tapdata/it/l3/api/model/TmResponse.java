package io.tapdata.it.l3.api.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 管理端统一响应包装的框架侧映射（对齐 {@code com.tapdata.tm.base.dto.ResponseMessage}）。
 * <p>
 * 不复用 tm-api 的 {@code ResponseMessage}（其属 Web 层、拖入重量级依赖），仅映射线上传输字段，
 * 使 {@code product-it-core} 与 TM Web 层解耦。成功判定：{@code code} 为 {@code "ok"}/空。
 *
 * @param <T> 业务数据类型（如 TaskDto、Page、List 等）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmResponse<T> {

    /** 请求处理码：成功为 "ok"；失败为错误码（如 "503"、业务错误 key）。 */
    private String code;

    /** 错误消息（服务端字段名为 message，兼容 MockTM 的 msg）。 */
    @JsonAlias("msg")
    private String message;

    /** 业务数据。 */
    private T data;

    private String reqId;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getReqId() {
        return reqId;
    }

    public void setReqId(String reqId) {
        this.reqId = reqId;
    }

    /** 是否成功：code 为空或等于 "ok"。TM 宕机探活返回 code=503 亦被判为失败。 */
    @JsonIgnore
    public boolean isSuccess() {
        return code == null || code.isEmpty() || "ok".equalsIgnoreCase(code);
    }
}
