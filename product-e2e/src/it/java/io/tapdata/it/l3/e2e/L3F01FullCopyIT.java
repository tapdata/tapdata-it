package io.tapdata.it.l3.e2e;

import io.tapdata.entity.utils.DataMap;
import io.tapdata.it.generator.RandomDataFactory;
import io.tapdata.it.l3.api.DataSourcePayload;
import io.tapdata.it.l3.api.TaskDtoFactory;
import io.tapdata.it.l3.base.ProductIT;
import io.tapdata.it.l3.base.TmAwaits;
import io.tapdata.it.schema.TestDataType;
import io.tapdata.it.schema.TestFieldSpec;
import io.tapdata.it.schema.TestTableSpec;
import io.tapdata.it.verifier.ConnectorVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3-F-01 全量复制黄金路径：MySQL → MongoDB（完整产品黑盒）。
 * <p>
 * 覆盖链路：TM 建连接（引擎测连 + 建模）→ 表发现 → 装配 {@code migrate/initial_sync} 任务 →
 * confirm（目标端自动建集合）→ start → 引擎全量读 + 写 → 任务 {@code complete}。
 * <p>
 * <b>造数与验证全部旁路</b>（复用 {@code tapdata-it-common}，对齐 A3：tm-common 只作管理端公共 DTO）：
 * 源表建立/写入走 {@link ConnectorVerifier#createTable}/{@link ConnectorVerifier#insert}
 * （JDBC 直连，不经 Connector 的 createTableV2/writeRecord），
 * 结果核对直连目标 MongoDB（不经 Connector 的 batchRead/batchCount），
 * 避免「用被测能力验证被测能力」的自洽通过。
 * <p>
 * 表名随机（{@code l3f01_*}）、数据固定种子可复现，跑在共享的 {@code test_it} 库上，
 * 前后置各自清理，不依赖也不污染既有数据。
 */
@DisplayName("L3-F-01 全量复制黄金路径（MySQL → MongoDB）")
class L3F01FullCopyIT extends ProductIT {

    /** 两用连接配置（TM 建连接 + 旁路直连），见 {@code src/it/resources/config/l3f01-connections.json}。 */
    private static final String CONNECTIONS_RESOURCE = "config/l3f01-connections.json";

    private static final String TABLE_PREFIX = "l3f01_";
    private static final String PK = "id";
    private static final int TABLE_COUNT = 2;
    private static final int ROW_COUNT = 100;
    /** 固定种子：失败时可按同一数据序列复现 */
    private static final long DATA_SEED = 20260907L;

    private static ConnectorVerifier sourceVerifier;
    private static ConnectorVerifier targetVerifier;

    /** 本用例动态创建的表名（源建、目标由产品自动建）。 */
    private static final List<String> tableNames = new ArrayList<>();
    /** 表名 → 期望行（造数时的原始值，逐行逐列比对基准）。 */
    private static final Map<String, List<Map<String, Object>>> expectedRows = new LinkedHashMap<>();

    private static String sourceConnectionId;
    private static String targetConnectionId;
    private static String taskId;
    private static String runSuffix;

    @BeforeAll
    static void prepareSourceDataAndConnections() throws Exception {
        DataMap config = loadConnectionJson(CONNECTIONS_RESOURCE);
        DataMap sourceNode = sub(config, "source");
        DataMap targetNode = sub(config, "target");
        // 本地笔记本经 kubectl port-forward 访问集群内 DB 时，仅改写旁路验证器副本，
        // 原节点仍保留集群 DNS 供 TM 建连接（l3.verifier.* 仅由本用例识别）。
        DataMap sourceVerifierNode = copyNode(sourceNode);
        DataMap targetVerifierNode = copyNode(targetNode);
        applyVerifierOverrides(sourceVerifierNode, "source", "jdbcUrl");
        applyVerifierOverrides(targetVerifierNode, "target", "uri");

        String sourceType = sourceNode.getString("database_type");
        String targetType = targetNode.getString("database_type");

        // A5：connector jar 由 TM 首启默认上传，用例只做注册预检，不上传 jar
        requireConnectorRegistered(sourceType, targetType);

        sourceVerifier = directVerifier(sourceVerifierNode);
        targetVerifier = directVerifier(targetVerifierNode);
        // 兜底清理历史残留（表名随机，Mongo 侧无固定前缀可清，逐表删除见 tearDown）
        sourceVerifier.dropTablesByPrefix(TABLE_PREFIX);

        Random random = new Random(DATA_SEED);
        for (int i = 0; i < TABLE_COUNT; i++) {
            TestTableSpec spec = stableTypesSpec();
            List<Map<String, Object>> rows = RandomDataFactory.generateRows(spec, ROW_COUNT, random);
            sourceVerifier.createTable(spec.getTableName(), spec.getFields());
            sourceVerifier.insert(spec.getTableName(), rows);
            tableNames.add(spec.getTableName());
            expectedRows.put(spec.getTableName(), rows);
        }
        for (String table : tableNames) {
            assertEquals(ROW_COUNT, sourceVerifier.count(table), "source seed rows mismatch: " + table);
        }

        runSuffix = "_" + System.currentTimeMillis();
        sourceConnectionId = createConnection(DataSourcePayload.fromNode("l3f01_src" + runSuffix, sourceNode, null, true));
        targetConnectionId = createConnection(DataSourcePayload.fromNode("l3f01_dst" + runSuffix, targetNode, null, false));
        // 产品视角结构断言：源库表已被 TM 元数据发现（任务能选到表的前提）
        awaitTablesDiscovered(sourceConnectionId, tableNames);
    }

    @Test
    @DisplayName("全量复制：任务收敛 complete，目标端自动建集合并逐行逐列一致")
    void fullCopyReplicatesAllRowsToTarget() throws Exception {
        TaskDtoFactory.Endpoint source = TaskDtoFactory.Endpoint.of(tm().dataSourceApi(), sourceConnectionId);
        TaskDtoFactory.Endpoint target = TaskDtoFactory.Endpoint.of(tm().dataSourceApi(), targetConnectionId);

        taskId = startCopyTask("l3-f-01-full-copy" + runSuffix, source, target, tableNames, false);

        awaitTaskCompleted(taskId);
        assertEquals(TmAwaits.STATUS_COMPLETE, tm().taskApi().status(taskId),
                "task " + taskId + " did not reach terminal status");

        for (String table : tableNames) {
            assertTrue(targetVerifier.tableExists(table),
                    "target collection not auto created by product: " + table);
            assertEquals(ROW_COUNT, targetVerifier.count(table),
                    "target row count mismatch on collection: " + table);
            assertTableEquals(table, expectedRows.get(table), targetVerifier.selectAll(table));
        }
    }

    @AfterAll
    static void cleanup() {
        removeTaskQuietly(taskId);
        removeConnectionQuietly(sourceConnectionId);
        removeConnectionQuietly(targetConnectionId);
        // 源端 JDBC 可按前缀枚举兜底清理；目标端 MongoVerifier 无集合前缀枚举，按已知表名逐集合删除
        ConnectorVerifier src = sourceVerifier;
        if (src != null) {
            quietly(() -> src.dropTablesByPrefix(TABLE_PREFIX));
        }
        for (String table : tableNames) {
            quietlyDrop(src, table);
            quietlyDrop(targetVerifier, table);
        }
        closeQuietly(sourceVerifier);
        closeQuietly(targetVerifier);
    }

    /** 用例级旁路地址覆盖：-Dl3.verifier.<node>.<key>=<value> 只影响直连验证器，不影响 TM 连接配置。 */
    private static void applyVerifierOverrides(DataMap node, String nodeName, String... keys) {
        for (String key : keys) {
            String prop = System.getProperty("l3.verifier." + nodeName + "." + key);
            if (prop != null && !prop.isEmpty()) {
                node.put(key, prop);
            }
        }
    }

    private static DataMap copyNode(DataMap node) {
        DataMap copy = DataMap.create();
        if (node != null) {
            copy.putAll(node);
        }
        return copy;
    }

    // ---- 表/数据规格 ----

    /**
     * 跨库稳定字段集：BIGINT 主键 + 整型/字符串/浮点/定点/布尔。
     * <p>刻意不含 DATE/DATETIME/TIMESTAMP/BLOB——三者在不同目标端的存储与序列化口径不一致
     * （时区、精度、Binary vs Base64），属于类型映射专项用例（L3-F-02+）的范围，
     * 黄金路径只锁死「链路桥得通、数据不丢不变」。
     */
    private static TestTableSpec stableTypesSpec() {
        return TestTableSpec.builder()
                .tableName(TestTableSpec.randomTableName(TABLE_PREFIX))
                .recordCount(ROW_COUNT)
                .addField(field("id", "bigint", TestDataType.BIGINT).primaryKey(true).nullable(false).build())
                .addField(field("c_int", "int", TestDataType.INT).build())
                .addField(field("c_bigint", "bigint", TestDataType.BIGINT).build())
                .addField(field("c_varchar", "varchar(255)", TestDataType.VARCHAR).build())
                .addField(field("c_double", "double", TestDataType.DOUBLE).build())
                .addField(field("c_decimal", "decimal(18,4)", TestDataType.DECIMAL).build())
                .addField(field("c_boolean", "boolean", TestDataType.BOOLEAN).build())
                .build();
    }

    private static TestFieldSpec.Builder field(String name, String dataType, TestDataType testDataType) {
        return TestFieldSpec.builder().name(name).dataType(dataType).testDataType(testDataType);
    }

    // ---- 逐行逐列比对 ----

    /**
     * 按主键对齐源/目标行并逐列比对：目标端多出的列（如 Mongo {@code _id}）与缺失行、多余行均判失败。
     */
    private static void assertTableEquals(String table, List<Map<String, Object>> expected,
                                          List<Map<String, Object>> actual) {
        Map<Object, Map<String, Object>> remaining = new LinkedHashMap<>();
        for (Map<String, Object> row : actual) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("_id");
            Object key = pkKey(copy.get(PK));
            assertNotNull(key, "target row without primary key column " + PK + " in " + table);
            assertTrue(remaining.put(key, copy) == null, "duplicated primary key " + key + " in " + table);
        }
        assertEquals(expected.size(), remaining.size(),
                "row count mismatch on " + table + " (target has extra/missing rows)");
        for (Map<String, Object> exp : expected) {
            Object key = pkKey(exp.get(PK));
            Map<String, Object> act = remaining.remove(key);
            assertNotNull(act, "missing row " + table + "." + PK + "=" + key + " in target");
            for (Map.Entry<String, Object> column : exp.entrySet()) {
                assertTrue(valueEquals(column.getValue(), act.get(column.getKey())),
                        "value mismatch " + table + "." + column.getKey() + " (pk=" + key + "): expected="
                                + column.getValue() + " actual=" + act.get(column.getKey()));
            }
        }
        assertTrue(remaining.isEmpty(), "unexpected extra rows in target " + table + ": " + remaining.keySet());
    }

    /** 主键归一：源端 Long / 目标端 Int32、Int64 视为同值。 */
    private static Object pkKey(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number ? ((Number) value).longValue() : String.valueOf(value);
    }

    /**
     * 跨存储的值等价：数值按 {@link BigDecimal#compareTo} 忽略精度标度差异
     * （MySQL DECIMAL(18,4) 与 Mongo String/Decimal128 表示不同但值相同），其余按字符串形态比较。
     * <p>
     * MySQL BOOLEAN 实际为 TINYINT(1)，同步到 Mongo 后可能以 Integer 0/1 存在，
     * 故布尔值与 0/1 数字视为等价。
     */
    private static boolean valueEquals(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof Boolean || actual instanceof Boolean) {
            return booleanValue(expected) == booleanValue(actual);
        }
        if (isNumeric(expected) && isNumeric(actual)) {
            try {
                return new BigDecimal(String.valueOf(expected).trim())
                        .compareTo(new BigDecimal(String.valueOf(actual).trim())) == 0;
            } catch (NumberFormatException e) {
                return String.valueOf(expected).equals(String.valueOf(actual));
            }
        }
        return String.valueOf(expected).equals(String.valueOf(actual));
    }

    /** 将布尔语义值（Boolean、0/1 数字、"true"/"false"/"0"/"1"）归一化为布尔。 */
    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String s = String.valueOf(value).trim();
        return Boolean.parseBoolean(s) || "1".equals(s);
    }

    private static boolean isNumeric(Object value) {
        return value instanceof Number || (value instanceof String && ((String) value).matches("[-+]?\\d*\\.?\\d+"));
    }

    private static void quietlyDrop(ConnectorVerifier verifier, String table) {
        if (verifier == null) {
            return;
        }
        try {
            if (verifier.tableExists(table)) {
                verifier.dropTable(table);
            }
        } catch (Exception e) {
            LOG.warning("Drop " + table + " failed (ignored): " + e.getMessage());
        }
    }

    /** 可抛 checked 异常的清理动作（与 {@code ProductIT} 内部同款，供本类前置/后置复用）。 */
    private interface CleanupAction {
        void run() throws Exception;
    }

    private static void quietly(CleanupAction action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warning("Cleanup failed (ignored): " + e.getMessage());
        }
    }
}
