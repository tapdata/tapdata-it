package io.tapdata.it.l3.common.exception;

/**
 * Raised by assert_* steps when the actual value does not match the expected value.
 * Includes both expected and actual values for diagnostic panels.
 */
public class AssertionFailedException extends RuntimeException {

    private final Object expected;
    private final Object actual;

    public AssertionFailedException(String message) {
        super(message);
        this.expected = null;
        this.actual = null;
    }

    public AssertionFailedException(String message, Object expected, Object actual) {
        super(String.format("%s (expected=%s, actual=%s)", message, expected, actual));
        this.expected = expected;
        this.actual = actual;
    }

    public Object getExpected() {
        return expected;
    }

    public Object getActual() {
        return actual;
    }
}
