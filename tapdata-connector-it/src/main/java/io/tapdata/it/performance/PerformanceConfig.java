package io.tapdata.it.performance;

public final class PerformanceConfig {

    private final int batchRows;
    private final int batchSize;
    private final int streamRows;
    private final int streamRecordSize;
    private final int prepareBatchSize;
    private final int writeThreads;
    private final int writeBatchSize;
    private final int writeBatchesPerThread;
    private final int timeoutSeconds;
    private final double minimumBatchRowsPerSecond;
    private final double minimumStreamRowsPerSecond;
    private final double minimumWriteRowsPerSecond;
    private final boolean cleanup;

    private PerformanceConfig(int batchRows, int batchSize, int streamRows, int streamRecordSize,
                              int prepareBatchSize, int writeThreads, int writeBatchSize, int writeBatchesPerThread,
                              int timeoutSeconds, double minimumBatchRowsPerSecond,
                              double minimumStreamRowsPerSecond, double minimumWriteRowsPerSecond,
                              boolean cleanup) {
        this.batchRows = batchRows;
        this.batchSize = batchSize;
        this.streamRows = streamRows;
        this.streamRecordSize = streamRecordSize;
        this.prepareBatchSize = prepareBatchSize;
        this.writeThreads = writeThreads;
        this.writeBatchSize = writeBatchSize;
        this.writeBatchesPerThread = writeBatchesPerThread;
        this.timeoutSeconds = timeoutSeconds;
        this.minimumBatchRowsPerSecond = minimumBatchRowsPerSecond;
        this.minimumStreamRowsPerSecond = minimumStreamRowsPerSecond;
        this.minimumWriteRowsPerSecond = minimumWriteRowsPerSecond;
        this.cleanup = cleanup;
    }

    public static PerformanceConfig fromSystemProperties() {
        return new PerformanceConfig(
                integer("performance.batchRows", "PERFORMANCE_BATCH_ROWS", 10000),
                integer("performance.batchSize", "PERFORMANCE_BATCH_SIZE", 1000),
                integer("performance.streamRows", "PERFORMANCE_STREAM_ROWS", 2000),
                integer("performance.streamRecordSize", "PERFORMANCE_STREAM_RECORD_SIZE", 100),
                integer("performance.prepareBatchSize", "PERFORMANCE_PREPARE_BATCH_SIZE", 1000),
                integer("performance.writeThreads", "PERFORMANCE_WRITE_THREADS", 2),
                integer("performance.writeBatchSize", "PERFORMANCE_WRITE_BATCH_SIZE", 100),
                integer("performance.writeBatchesPerThread", "PERFORMANCE_WRITE_BATCHES_PER_THREAD", 10),
                integer("performance.timeoutSeconds", "PERFORMANCE_TIMEOUT_SECONDS", 300),
                decimal("performance.minimumBatchRowsPerSecond", "PERFORMANCE_MINIMUM_BATCH_ROWS_PER_SECOND", 0D),
                decimal("performance.minimumStreamRowsPerSecond", "PERFORMANCE_MINIMUM_STREAM_ROWS_PER_SECOND", 0D),
                decimal("performance.minimumWriteRowsPerSecond", "PERFORMANCE_MINIMUM_WRITE_ROWS_PER_SECOND", 0D),
                bool("performance.cleanup", "PERFORMANCE_CLEANUP", true));
    }

    private static String value(String property, String environment, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environment);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static int integer(String property, String environment, int defaultValue) {
        return Integer.parseInt(value(property, environment, String.valueOf(defaultValue)));
    }

    private static double decimal(String property, String environment, double defaultValue) {
        return Double.parseDouble(value(property, environment, String.valueOf(defaultValue)));
    }

    private static boolean bool(String property, String environment, boolean defaultValue) {
        return Boolean.parseBoolean(value(property, environment, String.valueOf(defaultValue)));
    }

    public int getBatchRows() { return batchRows; }
    public int getBatchSize() { return batchSize; }
    public int getStreamRows() { return streamRows; }
    public int getStreamRecordSize() { return streamRecordSize; }
    public int getPrepareBatchSize() { return prepareBatchSize; }
    public int getWriteThreads() { return writeThreads; }
    public int getWriteBatchSize() { return writeBatchSize; }
    public int getWriteBatchesPerThread() { return writeBatchesPerThread; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public double getMinimumBatchRowsPerSecond() { return minimumBatchRowsPerSecond; }
    public double getMinimumStreamRowsPerSecond() { return minimumStreamRowsPerSecond; }
    public double getMinimumWriteRowsPerSecond() { return minimumWriteRowsPerSecond; }
    public boolean isCleanup() { return cleanup; }
}
