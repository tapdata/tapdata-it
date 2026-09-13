package io.tapdata.it.tpcc;

import io.tapdata.entity.event.TapBaseEvent;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.ddl.table.TapCreateTableEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.it.UnderTest;
import io.tapdata.it.performance.PerformanceConnectorIT;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.functions.connector.source.BatchCountFunction;
import io.tapdata.pdk.apis.functions.connector.source.GetStreamOffsetFunction;
import io.tapdata.pdk.apis.functions.connector.source.StreamReadFunction;
import io.tapdata.pdk.apis.functions.connector.source.TimestampToStreamOffsetFunction;
import io.tapdata.pdk.apis.functions.connector.target.CreateTableV2Function;
import io.tapdata.pdk.apis.functions.connector.target.DropTableFunction;
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

    protected StreamReadFunction tpccStreamReadFunction() {
        return functions().getStreamReadFunction();
    }

    protected GetStreamOffsetFunction tpccGetStreamOffsetFunction() {
        return functions().getGetStreamOffsetFunction();
    }

    @Test
    @Tag("tpcc")
    @UnderTest("discoverSchema")
    @UnderTest("batchCount")
    @DisplayName("TPCC schema and batch counts match the source database")
    void should_read_tpcc_schema_and_counts() throws Throwable {
        ensurePrepared();
        Map<String, TapTable> tables = discoverTpccTables();
        Map<String, Long> expected = normalizedRowCounts();
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
    @UnderTest("batchRead")
    @DisplayName("TPCC full source rows are readable through batchRead")
    void should_batch_read_all_tpcc_source_rows() throws Throwable {
        ensurePrepared();
        Map<String, TapTable> tables = discoverTpccTables();
        Map<String, Long> expected = normalizedRowCounts();

        for (String tableName : adapter().tableNames()) {
            TapTable table = tables.get(normalize(tableName));
            assertNotNull(table, "TPCC table should be discovered: " + tableName);
            List<Map<String, Object>> rows = batchReadAll(table);
            assertEquals(expected.get(normalize(tableName)).longValue(), rows.size(),
                    "batchRead should return every TPCC row for " + tableName);
        }
    }

    @Test
    @Tag("tpcc")
    @UnderTest("createTableV2")
    @UnderTest("writeRecord")
    @UnderTest("batchCount")
    @DisplayName("TPCC rows can be written through the connector target APIs")
    void should_write_tpcc_rows_to_target_tables() throws Throwable {
        ensurePrepared();
        Map<String, TapTable> sourceTables = discoverTpccTables();
        CreateTableV2Function createTable = require(functions()::getCreateTableV2Function, "createTableV2");
        DropTableFunction dropTable = require(functions()::getDropTableFunction, "dropTable");
        BatchCountFunction batchCount = require(functions()::getBatchCountFunction, "batchCount");
        List<TapTable> targetTables = new ArrayList<>();

        try {
            for (String tableName : adapter().tableNames()) {
                TapTable sourceTable = sourceTables.get(normalize(tableName));
                TapTable targetTable = targetTable(sourceTable);
                targetTables.add(targetTable);
                TapCreateTableEvent createEvent = TapSimplify.createTableEvent(targetTable);
                createTable.createTable(nodeContext(), createEvent);

                List<Map<String, Object>> rows = batchReadAll(sourceTable);
                long inserted = 0L;
                for (int from = 0; from < rows.size(); from += 1000) {
                    int to = Math.min(from + 1000, rows.size());
                    inserted += writeInsertEventsViaEngineCodec(rows.subList(from, to), targetTable);
                }
                assertEquals(rows.size(), inserted, "writeRecord should insert every TPCC row for " + tableName);
                assertEquals(rows.size(), batchCount.count(nodeContext(), targetTable),
                        "target TPCC row count should match source for " + tableName);
            }
        } finally {
            for (int index = targetTables.size() - 1; index >= 0; index--) {
                try {
                    dropTable.dropTable(nodeContext(), TapSimplify.dropTableEvent(targetTables.get(index).getId()));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Test
    @Tag("tpcc")
    @UnderTest("streamRead")
    @UnderTest("getStreamOffset")
    @UnderTest("timestampToStreamOffset")
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
    @UnderTest("timestampToStreamOffset")
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

    private TapTable targetTable(TapTable source) {
        String targetName = "TIT_" + normalize(source.getName());
        TapTable target = new TapTable(targetName, targetName);
        for (TapField field : source.getNameFieldMap().values()) {
            target.add(field.clone());
        }
        target.refreshPrimaryKeys();
        registerTable(target);
        return target;
    }

    private Object currentOffset() throws Throwable {
        GetStreamOffsetFunction getStreamOffset = tpccGetStreamOffsetFunction();
        if (getStreamOffset != null) {
            return getStreamOffset.getStreamOffset(nodeContext(), null);
        }
        TimestampToStreamOffsetFunction timestampToOffset = require(functions()::getTimestampToStreamOffsetFunction,
                "getStreamOffset or timestampToStreamOffset");
        return timestampToOffset.timestampToStreamOffset(nodeContext(), System.currentTimeMillis());
    }

    private StreamCapture startStream(Map<String, TapTable> tables, Object offset) throws InterruptedException {
        StreamReadFunction streamRead = require(this::tpccStreamReadFunction, "streamRead");
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

    private Map<String, Long> normalizedRowCounts() throws Exception {
        Map<String, Long> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : adapter().currentRowCounts().entrySet()) {
            normalized.put(normalize(entry.getKey()), entry.getValue());
        }
        return normalized;
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
