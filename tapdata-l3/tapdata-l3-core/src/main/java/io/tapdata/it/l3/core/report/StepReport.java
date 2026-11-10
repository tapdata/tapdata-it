package io.tapdata.it.l3.core.report;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/** Step-level report entry — timing plus the metrics extracted from step outputs. */
@Getter
@Builder
public class StepReport {

    private final String id;
    private final String action;
    private final boolean success;
    private final long durationMs;
    private final Map<String, Object> metrics;
    private final String errorMessage;
    private final String stackTrace;
}
