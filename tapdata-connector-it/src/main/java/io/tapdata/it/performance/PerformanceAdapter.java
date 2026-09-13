package io.tapdata.it.performance;

import io.tapdata.entity.schema.TapTable;

import java.util.List;
import java.util.Map;

public interface PerformanceAdapter {

    TapTable table();

    void createTable() throws Exception;

    void insertRows(List<Map<String, Object>> rows) throws Exception;

    default void insertRows(int count, long sequenceBase, int batchSize) throws Exception {
        for (int offset = 0; offset < count; offset += batchSize) {
            int currentBatchSize = Math.min(batchSize, count - offset);
            insertRows(PerformanceRows.create(currentBatchSize, sequenceBase + offset));
        }
    }

    long countRows() throws Exception;

    void dropTable() throws Exception;
}
