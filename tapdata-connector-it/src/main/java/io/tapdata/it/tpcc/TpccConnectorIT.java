package io.tapdata.it.tpcc;

import io.tapdata.entity.event.TapBaseEvent;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.it.UnderTest;
import io.tapdata.it.performance.PerformanceConnectorIT;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.functions.connector.source.BatchCountFunction;
import io.tapdata.pdk.apis.functions.connector.source.GetStreamOffsetFunction;
import io.tapdata.pdk.apis.functions.connector.source.StreamReadFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class TpccConnectorIT extends PerformanceConnectorIT {

    private TpccAdapter tpccAdapter;
    private TpccConfig tpccConfig;
    private boolean prepared;

    protected abstract TpccAdapter createTpccAdapter();

    @Test
    @Tag("tpcc")
    @UnderTest("discoverSchema")
    @UnderTest("batchCount")
    @DisplayName("TPCC schema and batch counts match the source database")
    void should_read_tpcc_schema_and_counts() throws Throwable {
        ensurePrepared();
        Map<String, TapTable> tables = discoverTpccTables();
        Map<String, Long> expected = adapter().currentRowCounts();
        BatchCountFunction batchCount = require(functions()::getBatchCountFunction, "batchCount");

        assertEquals(adapter().tableNames().size(), tables.size(), "all TPCC tables should be discovered");
        for (String tableName : adapter().tableNames()) {
            TapTable table = tables.get(normalize(tableName));
            assertNotNull(table, "TPCC table should be discovered: " + tableName);
            long actual = batchCount.count(nodeContext(), table);
            assertEquals(expected.get(normalize(tableName)).longValue(), actual,
                    "connector batchCount should match source count for " + tableName);
        }
    }

    @Test
    @Tag("tpcc")
    @UnderTest("streamRead")
    @UnderTest("getStreamOffset")
    @DisplayName("TPCC workload produces multi-table CDC events")
    void should_stream_tpcc_transactions() throws Throwable {
        ensurePrepared();
        Map<String, TapTable> tables = discoverTpccTables();
        StreamCapture capture = startStream(tables, currentOffset());
        try {
            adapter().runWorkload(config());
            waitForWorkload(capture, "live TPCC workload");
            adapter().verifyConsistency();
        } finally {
            capture.stop();
        }
    }

    @Test
    @Tag("tpcc")
    @UnderTest("streamRead")
    @UnderTest("getStreamOffset")
    @DisplayName("TPCC workload can resume from a saved stream offset")
    void should_resume_tpcc_from_saved_offset() throws Throwable {
        ensurePrepared();
        Map<String, TapTable> tables = discoverTpccTables();
        Object savedOffset = currentOffset();
        adapter().runWorkload(config());

        StreamCapture capture = startStream(tables, savedOffset);
        try {
            waitForWorkload(capture, "historical TPCC workload");
            adapter().verifyConsistency();
        } finally {
            capture.stop();
        }
    }

    @AfterAll
    void cleanupTpcc() throws Exception {
        if (prepared && config().isCleanup()) {
            adapter().cleanup();
            prepared = false;
        }
    }

    private synchronized void ensurePrepared() throws Exception {
        if (prepared && adapter().isPrepared()) {
            return;
        }
        adapter().prepare(config());
        prepared = true;
    }

    private TpccAdapter adapter() {
        if (tpccAdapter == null) {
            tpccAdapter = createTpccAdapter();
        }
        return tpccAdapter;
    }

    private TpccConfig config() {
        if (tpccConfig == null) {
            tpccConfig = TpccConfig.fromSystemProperties();
        }
        return tpccConfig;
    }

    private Map<String, TapTable> discoverTpccTables() throws Throwable {
        List<TapTable> discovered = new ArrayList<>();
        context.getConnector().discoverSchema(connectionContext(), adapter().tableNames(),
                adapter().tableNames().size(), discovered::addAll);

        Map<String, TapTable> tables = new LinkedHashMap<>();
        for (TapTable table : discovered) {
            table.getNameFieldMap().values().forEach(field -> field.setTapType(typeResolver.resolve(field.getDataType())));
            registerTable(table);
            tables.put(normalize(table.getId()), table);
        }
        return tables;
    }

    private Object currentOffset() throws Throwable {
        GetStreamOffsetFunction getStreamOffset = require(functions()::getGetStreamOffsetFunction, "getStreamOffset");
        return getStreamOffset.getStreamOffset(nodeContext(), null);
    }

    private StreamCapture startStream(Map<String, TapTable> tables, Object offset) throws InterruptedException {
        StreamReadFunction streamRead = require(functions()::getStreamReadFunction, "streamRead");
        List<String> tableIds = tables.values().stream().map(TapTable::getId).collect(Collectors.toList());
        StreamCapture capture = new StreamCapture();
        capture.consumer = StreamReadConsumer.create((events, callbackOffset) -> {
            if (events != null) {
                capture.events.addAll(events);
            }
            capture.lastOffset.set(callbackOffset);
            capture.signal.release();
        });
        capture.thread = new Thread(() -> {
            try {
                streamRead.streamRead(nodeContext(), tableIds, offset, 100, capture.consumer);
            } catch (Throwable throwable) {
                capture.error.set(throwable);
                capture.signal.release();
            }
        }, "tap-it-tpcc-stream");
        capture.thread.setDaemon(true);
        capture.thread.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (capture.consumer.getState() != StreamReadConsumer.STATE_STREAM_READ_STARTED
                && capture.error.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertNull(capture.error.get(), () -> "TPCC streamRead failed to start: " + capture.error.get());
        assertTrue(capture.thread.isAlive(), "TPCC streamRead thread should remain active after startup");
        return capture;
    }

    private void waitForWorkload(StreamCapture capture, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config().getTimeoutSeconds());
        while (System.nanoTime() < deadline) {
            Throwable error = capture.error.get();
            assertNull(error, () -> description + " stream failed: " + error);
            List<TapEvent> events = capture.snapshot();
            Set<String> changedTables = changedTables(events);
            if (recordEventCount(events) >= config().getMinimumEvents()
                    && changedTables.size() >= config().getMinimumChangedTables()) {
                assertNotNull(capture.lastOffset.get(), description + " should advance the stream offset");
                return;
            }
            capture.signal.tryAcquire(1, TimeUnit.SECONDS);
        }

        List<TapEvent> events = capture.snapshot();
        Set<String> changedTables = changedTables(events);
        assertTrue(recordEventCount(events) >= config().getMinimumEvents(),
                description + " should produce at least " + config().getMinimumEvents()
                        + " record events, got " + recordEventCount(events));
        assertTrue(changedTables.size() >= config().getMinimumChangedTables(),
                description + " should change at least " + config().getMinimumChangedTables()
                        + " tables, got " + changedTables);
    }

    private int recordEventCount(List<TapEvent> events) {
        int count = 0;
        for (TapEvent event : events) {
            if (event instanceof TapRecordEvent) {
                count++;
            }
        }
        return count;
    }

    private Set<String> changedTables(List<TapEvent> events) {
        Set<String> tables = new LinkedHashSet<>();
        for (TapEvent event : events) {
            if (event instanceof TapBaseEvent && event instanceof TapRecordEvent) {
                String tableId = ((TapBaseEvent) event).getTableId();
                if (tableId != null) {
                    tables.add(normalize(tableId));
                }
            }
        }
        return tables;
    }

    private String normalize(String tableName) {
        return tableName == null ? null : tableName.toUpperCase();
    }

    private static final class StreamCapture {
        private final List<TapEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Object> lastOffset = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final Semaphore signal = new Semaphore(0);
        private StreamReadConsumer consumer;
        private Thread thread;

        private List<TapEvent> snapshot() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }

        private void stop() {
            if (consumer != null) {
                consumer.streamReadEnded();
            }
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
