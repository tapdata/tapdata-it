package io.tapdata.it.performance;

import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.it.ConnectorIT;
import io.tapdata.it.UnderTest;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.functions.connector.source.BatchReadFunction;
import io.tapdata.pdk.apis.functions.connector.source.GetStreamOffsetFunction;
import io.tapdata.pdk.apis.functions.connector.source.StreamReadFunction;
import io.tapdata.pdk.apis.functions.connector.source.TimestampToStreamOffsetFunction;
import io.tapdata.pdk.apis.functions.connector.target.WriteRecordFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class PerformanceConnectorIT extends ConnectorIT {

    private PerformanceAdapter performanceAdapter;
    private PerformanceConfig performanceConfig;

    protected PerformanceAdapter createPerformanceAdapter() {
        return null;
    }

    protected StreamReadFunction performanceStreamReadFunction() {
        return functions().getStreamReadFunction();
    }

    @Test
    @Tag("performance")
    @UnderTest("batchRead")
    @DisplayName("performance batchRead uses 1000-row batches on 1KB records")
    void should_measure_batch_read_performance() throws Throwable {
        preparePerformanceTable();
        PerformanceConfig config = config();
        adapter().insertRows(config.getBatchRows(), 0, config.getPrepareBatchSize());
        assertEquals(config.getBatchRows(), adapter().countRows(), "source row count before batchRead");

        BatchReadFunction batchRead = require(functions()::getBatchReadFunction, "batchRead");
        AtomicLong received = new AtomicLong();
        Set<Object> ids = ConcurrentHashMap.newKeySet();
        long started = System.nanoTime();
        batchRead.batchRead(nodeContext(), adapter().table(), null, config.getBatchSize(), (events, offset) -> {
            if (events == null) {
                return;
            }
            for (TapEvent event : events) {
                if (event instanceof TapInsertRecordEvent) {
                    received.incrementAndGet();
                    ids.add(((TapInsertRecordEvent) event).getAfter().get("ID"));
                }
            }
        });
        PerformanceResult result = result("batchRead", received.get(), started);
        assertEquals(config.getBatchRows(), received.get(), "batchRead should return every prepared row");
        assertEquals(config.getBatchRows(), ids.size(), "batchRead UUID primary keys should be unique");
        assertMinimum(result.rowsPerSecond, config.getMinimumBatchRowsPerSecond(), "batchRead");
    }

    @Test
    @Tag("performance")
    @UnderTest("streamRead")
    @UnderTest("getStreamOffset")
    @UnderTest("timestampToStreamOffset")
    @DisplayName("performance streamRead uses recordSize 100 on 1KB records")
    void should_measure_stream_read_performance() throws Throwable {
        preparePerformanceTable();
        PerformanceConfig config = config();
        StreamReadFunction streamRead = require(this::performanceStreamReadFunction, "streamRead");
        Object offset = currentOffset();
        AtomicLong received = new AtomicLong();
        Set<Object> ids = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Semaphore signal = new Semaphore(0);
        StreamReadConsumer consumer = StreamReadConsumer.create((events, callbackOffset) -> {
            if (events != null) {
                for (TapEvent event : events) {
                    if (event instanceof TapRecordEvent
                            && adapter().table().getId().equalsIgnoreCase(((TapRecordEvent) event).getTableId())) {
                        received.incrementAndGet();
                        if (event instanceof TapInsertRecordEvent) {
                            ids.add(((TapInsertRecordEvent) event).getAfter().get("ID"));
                        }
                    }
                }
            }
            signal.release();
        });
        Thread streamThread = new Thread(() -> {
            try {
                streamRead.streamRead(nodeContext(), Collections.singletonList(adapter().table().getId()), offset,
                        config.getStreamRecordSize(), consumer);
            } catch (Throwable throwable) {
                error.set(throwable);
                signal.release();
            }
        }, "tap-it-performance-stream");
        streamThread.setDaemon(true);
        streamThread.start();
        waitForStreamStart(consumer, streamThread, error);

        long started = System.nanoTime();
        try {
            adapter().insertRows(config.getStreamRows(), 100000000L, config.getPrepareBatchSize());
            waitForRows(received, error, signal, config.getStreamRows(), config.getTimeoutSeconds());
            PerformanceResult result = result("streamRead", received.get(), started);
            assertEquals(config.getStreamRows(), received.get(), "streamRead should return every inserted row");
            assertEquals(config.getStreamRows(), ids.size(), "streamRead UUID primary keys should be unique");
            assertMinimum(result.rowsPerSecond, config.getMinimumStreamRowsPerSecond(), "streamRead");
        } finally {
            consumer.streamReadEnded();
            streamThread.interrupt();
        }
    }

    private Object currentOffset() throws Throwable {
        GetStreamOffsetFunction getStreamOffset = functions().getGetStreamOffsetFunction();
        if (getStreamOffset != null) {
            return getStreamOffset.getStreamOffset(nodeContext(), null);
        }
        TimestampToStreamOffsetFunction timestampToOffset = require(
                functions()::getTimestampToStreamOffsetFunction,
                "getStreamOffset or timestampToStreamOffset");
        return timestampToOffset.timestampToStreamOffset(nodeContext(), System.currentTimeMillis());
    }

    @Test
    @Tag("performance")
    @UnderTest("writeRecord")
    @DisplayName("performance writeRecord uses 4 threads and 500-row batches")
    void should_measure_write_record_performance() throws Throwable {
        preparePerformanceTable();
        PerformanceConfig config = config();
        WriteRecordFunction writeRecord = require(functions()::getWriteRecordFunction, "writeRecord");
        ExecutorService executor = Executors.newFixedThreadPool(config.getWriteThreads());
        List<Future<Long>> futures = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (int threadIndex = 0; threadIndex < config.getWriteThreads(); threadIndex++) {
                final int worker = threadIndex;
                futures.add(executor.submit(() -> {
                    long inserted = 0;
                    for (int batch = 0; batch < config.getWriteBatchesPerThread(); batch++) {
                        long base = 200000000L + (long) worker * config.getWriteBatchesPerThread()
                                * config.getWriteBatchSize() + (long) batch * config.getWriteBatchSize();
                        List<Map<String, Object>> rows = PerformanceRows.create(config.getWriteBatchSize(), base);
                        List<TapRecordEvent> events = new ArrayList<>(rows.size());
                        for (Map<String, Object> row : rows) {
                            events.add(TapSimplify.insertRecordEvent(adapter().prepareWriteRecordRow(row),
                                    adapter().table().getId()));
                        }
                        AtomicLong batchInserted = new AtomicLong();
                        try {
                            writeRecord.writeRecord(nodeContext(), events, adapter().table(),
                                    writeResult -> batchInserted.addAndGet(writeResult.getInsertedCount()));
                        } catch (Throwable throwable) {
                            if (throwable instanceof Exception) {
                                throw (Exception) throwable;
                            }
                            throw (Error) throwable;
                        }
                        inserted += batchInserted.get();
                    }
                    return inserted;
                }));
            }
            long inserted = 0;
            for (Future<Long> future : futures) {
                inserted += future.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            }
            int expected = config.getWriteThreads() * config.getWriteBatchSize()
                    * config.getWriteBatchesPerThread();
            PerformanceResult result = result("writeRecord", inserted, started);
            assertEquals(expected, inserted, "writeRecord callbacks should report every inserted row");
            assertEquals(expected, adapter().countRows(), "database should contain every written row");
            assertMinimum(result.rowsPerSecond, config.getMinimumWriteRowsPerSecond(), "writeRecord");
        } finally {
            executor.shutdownNow();
        }
    }

    private void preparePerformanceTable() throws Exception {
        adapter().dropTable();
        adapter().createTable();
        registerTable(adapter().table());
    }

    private PerformanceAdapter adapter() {
        if (performanceAdapter == null) {
            performanceAdapter = createPerformanceAdapter();
        }
        assertTrue(performanceAdapter != null, "performance adapter must be provided when performance tests are enabled");
        return performanceAdapter;
    }

    private PerformanceConfig config() {
        if (performanceConfig == null) {
            performanceConfig = PerformanceConfig.fromSystemProperties();
        }
        return performanceConfig;
    }

    private void waitForStreamStart(StreamReadConsumer consumer, Thread thread,
                                    AtomicReference<Throwable> error) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (consumer.getState() != StreamReadConsumer.STATE_STREAM_READ_STARTED
                && error.get() == null && thread.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertNull(error.get(), () -> "streamRead failed to start: " + error.get());
        assertTrue(thread.isAlive(), "streamRead thread should remain active after startup");
    }

    private void waitForRows(AtomicLong received, AtomicReference<Throwable> error, Semaphore signal,
                             int expected, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (received.get() < expected && error.get() == null && System.nanoTime() < deadline) {
            signal.tryAcquire(1, TimeUnit.SECONDS);
        }
        assertNull(error.get(), () -> "streamRead failed: " + error.get());
        assertTrue(received.get() >= expected,
                "streamRead timed out: expected " + expected + " rows, got " + received.get());
    }

    private PerformanceResult result(String api, long rows, long started) {
        double seconds = Math.max(0.001D, (System.nanoTime() - started) / 1_000_000_000D);
        double rowsPerSecond = rows / seconds;
        context.getLog().info("[PERFORMANCE] api={}, rows={}, elapsedSeconds={}, rowsPerSecond={}",
                api, rows, String.format("%.3f", seconds), String.format("%.2f", rowsPerSecond));
        return new PerformanceResult(rowsPerSecond);
    }

    private void assertMinimum(double actual, double minimum, String api) {
        if (minimum > 0D) {
            assertTrue(actual >= minimum, api + " throughput " + actual + " rows/s is below " + minimum);
        }
    }

    @Override
    protected void dropResidualTables() {
        if (performanceAdapter != null && config().isCleanup()) {
            try {
                performanceAdapter.dropTable();
            } catch (Exception ignored) {
            }
        }
        super.dropResidualTables();
    }

    private static final class PerformanceResult {
        private final double rowsPerSecond;

        private PerformanceResult(double rowsPerSecond) {
            this.rowsPerSecond = rowsPerSecond;
        }
    }
}
