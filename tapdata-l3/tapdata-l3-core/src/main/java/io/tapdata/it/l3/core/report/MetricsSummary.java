package io.tapdata.it.l3.core.report;

import lombok.Builder;
import lombok.Getter;

/** Run-level metrics aggregated from step outputs (design doc §8.1 / §8.2). */
@Getter
@Builder
public class MetricsSummary {

    private final double avgReplicationLagMs;
    private final double maxReplicationLagMs;
    private final double peakThroughputRps;
    private final double avgDbForgeProvisionTimeMs;
}
