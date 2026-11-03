package io.tapdata.it.l3.common.exception;

/**
 * Raised when a step's hard timeout expires (from CompletableFuture.orTimeout).
 * Carries the step id and timeout_seconds for diagnostic panels.
 */
public class StepTimeoutException extends RuntimeException {

    private final String stepId;
    private final int timeoutSeconds;

    public StepTimeoutException(String stepId, int timeoutSeconds) {
        super(String.format("Step '%s' timed out after %d seconds", stepId, timeoutSeconds));
        this.stepId = stepId;
        this.timeoutSeconds = timeoutSeconds;
    }

    public StepTimeoutException(String stepId, int timeoutSeconds, Throwable cause) {
        super(String.format("Step '%s' timed out after %d seconds", stepId, timeoutSeconds), cause);
        this.stepId = stepId;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getStepId() {
        return stepId;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
