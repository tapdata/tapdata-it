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

    /** 安全随机列类型池 — 只使用 MySQL 严格模式下不会被截断的类型. */
    private static final String[] COLUMN_TYPES = {
            "INTEGER", "BIGINT", "VARCHAR(255)", "DOUBLE", "DECIMAL(10,2)", "DATE"
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

    /** 复用与 JdbcWriteIncrementalAction 相同的驱动坐标声明。 */
    @Override
    public List<String> getRuntimeDependencies() {
        return List.of(
                "mysql:mysql-connector-java:8.0.29",
                "org.mariadb.jdbc:mariadb-java-client:3.3.3",
                "org.postgresql:postgresql:42.1.4",
                "org.xerial:sqlite-jdbc:3.45.3.0",
                "com.clickhouse:clickhouse-jdbc:0.4.6",
                "com.oracle.database.jdbc:ojdbc11:23.3.0.23.09"
        );
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
                    List<ColumnInfo> columns = extractColumnInfo(tableName, conn);
                    List<String> columnNames = columns.stream().map(ColumnInfo::name).toList();
                    String insertSql = buildInsertSql(tableName, columnNames);
                    log.info("[JdbcInit] Inserting {} rows into {}", rowsToInsert, tableName);

                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        for (int i = 0; i < rowsToInsert; i++) {
                            bindRandomValues(ps, columns, i);
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
            // 自动生成随机列 — 循环分配安全类型，保证后续 bindRandomValues 能正确匹配
            int colCount = Math.max(1, defaultColumns);
            for (int i = 0; i < colCount; i++) {
                String colType = COLUMN_TYPES[i % COLUMN_TYPES.length];
                String colName = "col_" + (char) ('a' + i);
                sb.append("  ").append(quoteId(colName)).append(" ").append(colType).append(",\n");
            }
        }

        // 去掉最后的逗号
        sb.setLength(sb.length() - 2);
        sb.append("\n)");
        return sb.toString();
    }

    private List<ColumnInfo> extractColumnInfo(String tableName, Connection conn) throws SQLException {
        List<ColumnInfo> infos = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                int dataType = rs.getInt("DATA_TYPE");
                infos.add(new ColumnInfo(name, dataType));
            }
        }
        if (infos.isEmpty()) {
            // fallback: id BIGINT + col_x as VARCHAR
            infos.add(new ColumnInfo("id", java.sql.Types.BIGINT));
            for (int i = 0; i < 5; i++) {
                infos.add(new ColumnInfo("col_" + (char) ('a' + i), java.sql.Types.VARCHAR));
            }
        }
        return infos;
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

    private void bindRandomValues(PreparedStatement ps, List<ColumnInfo> columns, int rowIndex) throws SQLException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < columns.size(); i++) {
            ColumnInfo col = columns.get(i);
            int paramIndex = i + 1;
            int sqlType = col.sqlType();

            try {
                switch (sqlType) {
                    case java.sql.Types.TINYINT, java.sql.Types.SMALLINT,
                         java.sql.Types.INTEGER -> ps.setInt(paramIndex, random.nextInt(-100000, 100000));
                    case java.sql.Types.BIGINT -> ps.setLong(paramIndex, rowIndex + 1);
                    case java.sql.Types.REAL, java.sql.Types.FLOAT -> ps.setFloat(paramIndex, (float) (random.nextDouble() * 1000 - 500));
                    case java.sql.Types.DOUBLE, java.sql.Types.NUMERIC,
                         java.sql.Types.DECIMAL -> ps.setDouble(paramIndex, random.nextDouble() * 10000 - 5000);
                    case java.sql.Types.VARCHAR, java.sql.Types.CHAR,
                         java.sql.Types.LONGVARCHAR, java.sql.Types.NVARCHAR,
                         java.sql.Types.NCHAR, java.sql.Types.CLOB,
                         java.sql.Types.LONGNVARCHAR, java.sql.Types.NCLOB ->
                            ps.setString(paramIndex, "val_" + random.nextInt(100000));
                    case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE ->
                            ps.setTimestamp(paramIndex, Timestamp.valueOf("2024-01-01 12:00:00"));
                    case java.sql.Types.DATE -> ps.setDate(paramIndex, java.sql.Date.valueOf("2024-01-15"));
                    case java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE ->
                            ps.setTime(paramIndex, java.sql.Time.valueOf("12:00:00"));
                    case java.sql.Types.BOOLEAN, java.sql.Types.BIT ->
                            ps.setBoolean(paramIndex, random.nextBoolean());
                    case java.sql.Types.BINARY, java.sql.Types.VARBINARY,
                         java.sql.Types.LONGVARBINARY, java.sql.Types.BLOB ->
                            ps.setBytes(paramIndex, ("blob_" + random.nextInt(1000)).getBytes());
                    default -> {
                        // fallback: 先试 setString，不行就 setObject
                        try {
                            ps.setString(paramIndex, "fallback_" + random.nextInt(100000));
                        } catch (SQLException ex) {
                            ps.setObject(paramIndex, rowIndex + 1);
                        }
                    }
                }
            } catch (SQLException e) {
                log.warn("[JdbcInit] bind column '{}' (sqlType={}) failed, using fallback: {}",
                        col.name(), sqlType, e.getMessage());
                // 最终 fallback: setObject
                ps.setObject(paramIndex, rowIndex + 1);
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
        return JdbcParameter.detectDriverClass(jdbcUrl);
    }

    private static String quoteId(String name) {
        // SQLite / MySQL / PostgreSQL 通用
        return "`" + name + "`";
    }
}
