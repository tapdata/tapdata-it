package io.tapdata.it.l3.api.model;

/**
 * TM API 调用异常：当管理端返回非 {@code ok} 业务码、HTTP 传输失败或环境不可用时抛出。
 * 承载 {@code ResponseMessage.code} 与错误消息，便于用例断言具体错误码。
 */
public class TmApiException extends RuntimeException {

    private final String code;

    public TmApiException(String message) {
        this(message, null, null);
    }

    public TmApiException(String message, String code) {
        this(message, code, null);
    }

    public TmApiException(String message, String code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 业务错误码（对应 ResponseMessage.code），传输层异常时可能为 null。 */
    public String getCode() {
        return code;
    }
}
