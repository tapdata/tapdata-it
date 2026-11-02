package io.tapdata.it.l3.steps.jdbc;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Initialize source database — auto-create tables and populate with random data.
 * <p>
 * 自动根据用户配置的 {@code columns_per_table}、{@code rows_per_table}、{@code table_count}
 * 生成随机列（INTEGER / VARCHAR / DOUBLE / DATETIME 等）和随机数据。
 *
 * <pre>
 * # 简单模式 — 1 表 5 列 100 行
 * - id: init_source
 *   action: jdbc_init_source
 *   inputs:
 *     jdbc_url: "#{ #steps['create_src_db'].outputs['jdbc_url'] }"
 *     username: "#{ #steps['create_src_db'].outputs['username'] }"
 *     password: "#{ #steps['create_src_db'].outputs['password'] }"
 *     table_count: 2
 *     columns_per_table: 5
 *     rows_per_table: 100
 *
 * # 自定义表名和列
 * - id: init_source_custom
 *   action: jdbc_init_source
 *   inputs:
 *     jdbc_url: "#{ #steps['create_src_db'].outputs['jdbc_url'] }"
 *     table_names: ["users", "orders"]
 *     column_defs:
 *       - {name: "id", type: "BIGINT", primaryKey: true, autoIncrement: true}
 *       - {name: "name", type: "VARCHAR(100)"}
 *       - {name: "age", type: "INT"}
 *     rows_per_table: 50
 * </pre>
 *
 * Outputs: {@code table_names}, {@code total_rows}, {@code tables_created}
 */
public class JdbcInitSourceAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(JdbcInitSourceAction.class);

    /** 随机列类型池. */
    private static final String[] COLUMN_TYPES = {
            "INTEGER", "BIGINT", "VARCHAR(255)", "DOUBLE", "TEXT", "TIMESTAMP", "BOOLEAN"
    };

    /** 随机表名前缀. */
    private static final String[] TABLE_PREFIXES = {"test", "src", "gp", "sync", "data"};
    private static final String[] TABLE_NAMES = {
            "users", "orders", "products", "categories", "customers", "suppliers",
            "transactions", "logs", "sessions", "items"
    };

    @Override
    public String getActionName() {
        return "jdbc_init_source";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        JdbcParameter p = config.getInputParameterConfig(JdbcParameter.class);

        if (p.getJdbcUrl() == null || p.getJdbcUrl().isBlank()) {
            throw new IllegalArgumentException("Required input missing: jdbc_url");
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        long runId = random.nextLong(100000, 999999);

        // Resolve driver if needed
        resolveDriver(p);

        // Build table names list
        List<String> tableNames = new ArrayList<>();
        if (p.getTableNames() != null && !p.getTableNames().isEmpty()) {
            tableNames.addAll(p.getTableNames());
        } else {
            for (int i = 0; i < p.getTableCount(); i++) {
                String prefix = TABLE_PREFIXES[random.nextInt(TABLE_PREFIXES.length)];
                String name = TABLE_NAMES[random.nextInt(TABLE_NAMES.length)];
                tableNames.add(String.format("%s_%s_%d_%d", prefix, name, runId, i + 1));
            }
        }

        int totalRows = 0;
        List<String> createdTables = new ArrayList<>();

        try (Connection conn = openConnection(p)) {
            conn.setAutoCommit(false);

            for (String tableName : tableNames) {
                // Step 1: DDL — DROP + CREATE
                if (p.isDropIfExists()) {
                    String dropSql = String.format("DROP TABLE IF EXISTS %s", quoteId(tableName));
                    log.debug("[JdbcInit] {}", dropSql);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(dropSql);
                    }
                }

                String createSql = buildCreateTableSql(tableName, p.getColumnDefs(), p.getColumnsPerTable());
                log.info("[JdbcInit] Creating table: {} ({} columns)", tableName, p.getColumnDefs() != null
                        ? p.getColumnDefs().size() : p.getColumnsPerTable());
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(createSql);
                }

                // Step 2: INSERT — 批量生成随机数据
                int rowsToInsert = p.getRowsPerTable();
                if (rowsToInsert > 0) {
                    List<String> columnNames = extractColumnNames(tableName, conn);
                    String insertSql = buildInsertSql(tableName, columnNames);
                    log.info("[JdbcInit] Inserting {} rows into {}", rowsToInsert, tableName);

                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        for (int i = 0; i < rowsToInsert; i++) {
                            bindRandomValues(ps, columnNames.size(), i);
                            ps.addBatch();

                            if ((i + 1) % 100 == 0) {
                                ps.executeBatch();
                            }
                        }
                        ps.executeBatch();
                    }
                }

                conn.commit();
                createdTables.add(tableName);
                totalRows += rowsToInsert;
                log.info("[JdbcInit] Table {} ready: {} rows", tableName, rowsToInsert);
            }
        }

        log.info("[JdbcInit] Done — created {} tables, {} total rows", createdTables.size(), totalRows);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("table_names", createdTables);
        outputs.put("total_rows", totalRows);
        outputs.put("tables_created", createdTables.size());
        return success(outputs);
    }

    // ==================== SQL Generation ====================

    private String buildCreateTableSql(String tableName, List<Map<String, Object>> customColumns, int defaultColumns) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(quoteId(tableName)).append(" (\n");
        sb.append("  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n");

        if (customColumns != null && !customColumns.isEmpty()) {
            for (Map<String, Object> col : customColumns) {
                String name = (String) col.get("name");
                String type = (String) col.get("type");
                if (name == null || type == null) continue;
                sb.append("  ").append(quoteId(name)).append(" ").append(type);
                if (!Boolean.TRUE.equals(col.get("nullable"))) {
                    sb.append(" NOT NULL");
                }
                sb.append(",\n");
            }
        } else {
            // 自动生成随机列
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int colCount = Math.max(1, defaultColumns);
            for (int i = 0; i < colCount; i++) {
                String colType = COLUMN_TYPES[random.nextInt(COLUMN_TYPES.length)];
                String colName = "col_" + (char) ('a' + i);
                sb.append("  ").append(quoteId(colName)).append(" ").append(colType).append(",\n");
            }
        }

        // 去掉最后的逗号
        sb.setLength(sb.length() - 2);
        sb.append("\n)");
        return sb.toString();
    }

    private List<String> extractColumnNames(String tableName, Connection conn) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
            }
        }
        // 如果元数据没拿到，手动加 id + col_x
        if (names.isEmpty()) {
            names.add("id");
            for (int i = 0; i < 5; i++) names.add("col_" + (char) ('a' + i));
        }
        return names;
    }

    private String buildInsertSql(String tableName, List<String> columnNames) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(quoteId(tableName)).append(" (");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) {
                sb.append(", ");
                placeholders.append(", ");
            }
            sb.append(quoteId(columnNames.get(i)));
            placeholders.append("?");
        }
        sb.append(") VALUES (").append(placeholders).append(")");
        return sb.toString();
    }

    private void bindRandomValues(PreparedStatement ps, int paramCount, int rowIndex) throws SQLException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < paramCount; i++) {
            if (i == 0) {
                // id 列
                ps.setLong(i + 1, rowIndex + 1);
            } else {
                int type = random.nextInt(7);
                switch (type) {
                    case 0 -> ps.setInt(i + 1, random.nextInt(-10000, 10000));
                    case 1 -> ps.setLong(i + 1, random.nextLong(-1000000, 1000000));
                    case 2 -> ps.setString(i + 1, "val_" + random.nextInt(1000));
                    case 3 -> ps.setDouble(i + 1, random.nextDouble() * 1000 - 500);
                    case 4 -> ps.setString(i + 1, "text_" + random.nextInt(1000));
                    case 5 -> ps.setTimestamp(i + 1, Timestamp.valueOf("2024-01-01 00:00:00"));
                    default -> ps.setBoolean(i + 1, random.nextBoolean());
                }
            }
        }
    }

    // ==================== JDBC Helpers ====================

    private Connection openConnection(JdbcParameter p) throws SQLException {
        Properties props = new Properties();
        if (p.getUsername() != null && !p.getUsername().isBlank()) props.setProperty("user", p.getUsername());
        if (p.getPassword() != null) props.setProperty("password", p.getPassword());

        // 优先用 Driver.connect() 绕开 DriverManager
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = getClass().getClassLoader();

        String driverClass = p.getDriverClass();
        if (driverClass == null || driverClass.isBlank()) {
            driverClass = detectDriver(p.getJdbcUrl());
        }

        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class<?> driverCls = Class.forName(driverClass, true, cl);
                java.sql.Driver driver = (java.sql.Driver) driverCls.getDeclaredConstructor().newInstance();
                Connection conn = driver.connect(p.getJdbcUrl(), props);
                if (conn == null) throw new SQLException("Driver returned null for URL " + p.getJdbcUrl());
                return conn;
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                throw new SQLException("Failed to instantiate JDBC driver " + driverClass, e);
            }
        }

        return DriverManager.getConnection(p.getJdbcUrl(), props);
    }

    private void resolveDriver(JdbcParameter p) throws ClassNotFoundException {
        String driverClass = p.getDriverClass();
        if (driverClass == null || driverClass.isBlank()) {
            driverClass = detectDriver(p.getJdbcUrl());
        }
        if (driverClass != null && !driverClass.isBlank()) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = getClass().getClassLoader();
            Class.forName(driverClass, true, cl);
        }
    }

    private static String detectDriver(String jdbcUrl) {
        if (jdbcUrl == null) return "";
        Map<String, String> map = Map.of(
                "jdbc:sqlite:", "org.sqlite.JDBC",
                "jdbc:mysql:", "com.mysql.cj.jdbc.Driver",
                "jdbc:postgresql:", "org.postgresql.Driver",
                "jdbc:mariadb:", "org.mariadb.jdbc.Driver",
                "jdbc:clickhouse:", "com.clickhouse.jdbc.ClickHouseDriver"
        );
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (jdbcUrl.startsWith(e.getKey())) return e.getValue();
        }
        return "";
    }

    private static String quoteId(String name) {
        // SQLite / MySQL / PostgreSQL 通用
        return "`" + name + "`";
    }
}
