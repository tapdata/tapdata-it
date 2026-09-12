package io.tapdata.it.performance;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PerformanceRows {

    static final int NUMERIC_FIELDS = 16;
    static final int STRING_FIELDS = 32;

    private PerformanceRows() {
    }

    public static List<Map<String, Object>> create(int count, long sequenceBase) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long sequence = sequenceBase + index;
            Map<String, Object> row = new LinkedHashMap<>(64);
            row.put("ID", UUID.nameUUIDFromBytes(("tap-performance-" + sequence)
                    .getBytes(StandardCharsets.UTF_8)).toString());
            row.put("EVENT_TIME", new Timestamp(1704067200000L + sequence));
            for (int field = 1; field <= NUMERIC_FIELDS; field++) {
                row.put(fieldName("N", field), sequence * 100L + field);
            }
            for (int field = 1; field <= STRING_FIELDS; field++) {
                row.put(fieldName("S", field), fixedString(sequence, field));
            }
            rows.add(row);
        }
        return rows;
    }

    static String fieldName(String prefix, int index) {
        return String.format("%s%02d", prefix, index);
    }

    private static String fixedString(long sequence, int field) {
        String value = String.format("%012d-%02d-TAP-PERFORMANCE", sequence, field);
        return value.length() > 26 ? value.substring(0, 26) : String.format("%-26s", value).replace(' ', 'X');
    }
}
