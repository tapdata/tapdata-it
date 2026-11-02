package io.tapdata.it.l3.common.exception;

/**
 * Raised during YAML → ScenarioConfig parsing (missing fields, parallel independence
 * violation, required timeout_seconds missing, etc.). Error message is user-facing.
 */
public class DslParseException extends RuntimeException {

    public DslParseException(String message) {
        super(message);
    }

    public DslParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
