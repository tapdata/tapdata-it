package io.tapdata.it.l3.steps.jdbc;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Write incremental data to source database — INSERT / UPDATE / DELETE / TRANSACTION.
 * <p>
 * 用于验证 CDC 增量同步能力。按用户指定的操作类型和数量批量生成增量操作。
 *
 * <pre>
 * # 标准增量 — INSERT 10, UPDATE 20, DELETE 100
 * - id: write_incremental
 *   action: jdbc_write_incremental
 *   timeout_seconds: 120
 *   inputs:
 *     jdbc_url: "#{ #steps['create_src_db'].outputs['jdbc_url'] }"
 *     table_name: "#{ #steps['init_source'].outputs['table_names'][0] }"
 *     use_transaction: true
 *     operation_counts:
 *       insert: 10
 *       update: 20
 *       delete: 100
 *
 * # 自定义 SQL 脚本
 * - id: write_custom_sql
 *   action: jdbc_write_incremental
 *   inputs:
 *     jdbc_url: "#{ #steps['create_src_db'].outputs['jdbc_url'] }"
 *     custom_sql:
 *       - "INSERT INTO users (name, age) VALUES ('Alice', 30)"
 *       - "UPDATE users SET age = 31 WHERE name = 'Alice'"
 *       - "DELETE FROM users WHERE age > 100"
 *
 * # 外部脚本文件
 * - id: write_from_file
 *   action: jdbc_write_incremental
 *   inputs:
 *     jdbc_url: "#{ #steps['create_src_db'].outputs['jdbc_url'] }"
 *     script_path: "/path/to/incremental.sql"
 * </pre>
 *
 * Outputs: {@code total_operations}, {@code operations_insert}, {@code operations_update}, {@code operations_delete}
 */
public class JdbcWriteIncrementalAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(JdbcWriteIncrementalAction.class);

    @Override
    public String getActionName() {
        return "jdbc_write_incremental";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        JdbcParameter p = config.getInputParameterConfig(JdbcParameter.class);

        if (p.getJdbcUrl() == null || p.getJdbcUrl().isBlank()) {
            throw new IllegalArgumentException("Required input missing: jdbc_url");
        }

        // Resolve driver
        resolveDriver(p);

        // Mode 1: Custom SQL list or file — 直接执行
        if (p.getCustomSql() != null && !p.getCustomSql().isEmpty()) {
            return executeCustomSql(p, p.getCustomSql());
        }

        if (p.getScriptPath() != null && !p.getScriptPath().isBlank()) {
            return executeCustomSql(p, loadScriptFromFile(p.getScriptPath()));
        }

        // Mode 2: 按 operation_counts 生成增量操作
        if (p.getOperationCounts() == null || p.getOperationCounts().isEmpty()) {
            // 默认操作
            Map<String, Integer> defaults = new LinkedHashMap<>();
            defaults.put("insert", 10);
            defaults.put("update", 20);
            defaults.put("delete", 100);
            p.setOperationCounts(defaults);
        }

        if (p.getTableName() == null || p.getTableName().isBlank()) {
            throw new IllegalArgumentException("Required input missing: table_name (for operation_counts mode)");
        }

        return executeIncrementalOperations(p);
    }

    // ==================== Mode 1: Custom SQL ====================

    private StepResult executeCustomSql(JdbcParameter p, List<String> sqlStatements) throws Exception {
        log.info("[JdbcIncremental] Executing {} custom SQL statements", sqlStatements.size());

        long totalOperations = 0;
        long startMs = System.currentTimeMillis();

        try (Connection conn = openConnection(p)) {
            if (p.isUseTransaction()) {
                conn.setAutoCommit(false);
            }

            for (String sql : sqlStatements) {
                if (sql == null || sql.isBlank() || sql.trim().startsWith("--")) continue;
                String trimmed = sql.trim();
                try (Statement stmt = conn.createStatement()) {
                    boolean hasResult = stmt.execute(trimmed);
                    if (!hasResult) {
                        totalOperations++;
                    }
                } catch (Exception e) {
                    if (p.isUseTransaction()) {
                        conn.rollback();
                        throw e;
                    } else {
                        log.warn("[JdbcIncremental] SQL failed: {} — {}", trimmed, e.getMessage());
                    }
                }
            }

            if (p.isUseTransaction()) {
                conn.commit();
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("[JdbcIncremental] Custom SQL done: {} statements, {}ms", sqlStatements.size(), elapsed);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("total_operations", totalOperations);
        outputs.put("elapsed_ms", elapsed);
        return success(outputs);
    }

    private List<String> loadScriptFromFile(String path) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new IOException("Script file not found: " + path);
        }
        String content = Files.readString(p);
        // 简单地按 ; 分割 — 生产环境应使用真正的 SQL parser
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.lines().toList()) {
            if (line.trim().startsWith("--")) continue; // 跳过注释
            current.append(line).append('\n');
            if (line.trim().endsWith(";")) {
                statements.add(current.toString().replace(";", "").trim());
                current = new StringBuilder();
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    // ==================== Mode 2: Auto-generated incremental ops ====================

    private StepResult executeIncrementalOperations(JdbcParameter p) throws Exception {
        String tableName = p.getTableName();
        Map<String, Integer> counts = p.getOperationCounts();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // 获取表的列信息
        List<String> allColumns = new ArrayList<>();
        String idColumn = "id"; // 假设第一列是主键
        try (Connection conn = openConnection(p)) {
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, null)) {
                while (rs.next()) {
                    allColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        if (allColumns.isEmpty()) {
            throw new IllegalStateException("No columns found for table: " + tableName);
        }

        log.info("[JdbcIncremental] Target table: {}, columns: {}", tableName, allColumns);

        long totalInsert = counts.getOrDefault("insert", 0);
        long totalUpdate = counts.getOrDefault("update", 0);
        long totalDelete = counts.getOrDefault("delete", 0);
        long totalOps = totalInsert + totalUpdate + totalDelete;

        log.info("[JdbcIncremental] Planned ops — insert={}, update={}, delete={}",
                totalInsert, totalUpdate, totalDelete);

        long startMs = System.currentTimeMillis();

        try (Connection conn = openConnection(p)) {
            if (p.isUseTransaction()) {
                conn.setAutoCommit(false);
            }

            // INSERT
            if (totalInsert > 0) {
                int idMax = getMaxId(conn, tableName, idColumn);
                List<String> valueColumns = allColumns.stream().filter(c -> !c.equalsIgnoreCase(idColumn)).toList();

                String insertSql = buildInsert(tableName, allColumns);
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    for (int i = 0; i < totalInsert; i++) {
                        int colIdx = 1;
                        ps.setLong(colIdx++, idMax + i + 1);
                        for (int c = 0; c < valueColumns.size(); c++) {
                            bindRandomValue(ps, colIdx++, random);
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                log.info("[JdbcIncremental] INSERT × {} done", totalInsert);
            }

            // UPDATE — 基于现有 id
            if (totalUpdate > 0) {
                int idMax = getMaxId(conn, tableName, idColumn);
                int minId = Math.max(1, idMax > 200 ? idMax - 200 : 1);
                if (idMax >= minId) {
                    String updateCol = allColumns.stream()
                            .filter(c -> !c.equalsIgnoreCase(idColumn))
                            .findFirst().orElse(null);
                    if (updateCol != null) {
                        String updateSql = String.format(
                                "UPDATE %s SET %s = ? WHERE %s = ?",
                                quoteId(tableName), quoteId(updateCol), quoteId(idColumn));
                        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                            for (int i = 0; i < totalUpdate; i++) {
                                ps.setString(1, "updated_" + random.nextInt(100000));
                                ps.setLong(2, random.nextLong(minId, idMax + 1));
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                        log.info("[JdbcIncremental] UPDATE × {} done", totalUpdate);
                    }
                }
            }

            // DELETE — 基于现有 id
            if (totalDelete > 0) {
                int idMax = getMaxId(conn, tableName, idColumn);
                int minId = Math.max(1, idMax > 300 ? idMax - 300 : 1);
                if (idMax >= minId && totalDelete <= (idMax - minId + 1)) {
                    String deleteSql = String.format(
                            "DELETE FROM %s WHERE %s = ?", quoteId(tableName), quoteId(idColumn));
                    try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                        Set<Long> usedIds = new HashSet<>();
                        int remaining = (int) totalDelete;
                        while (remaining > 0 && usedIds.size() < (idMax - minId + 1)) {
                            long id = random.nextLong(minId, idMax + 1);
                            if (usedIds.add(id)) {
                                ps.setLong(1, id);
                                ps.addBatch();
                                remaining--;
                            }
                        }
                        ps.executeBatch();
                    }
                    log.info("[JdbcIncremental] DELETE × {} done", totalDelete);
                }
            }

            if (p.isUseTransaction()) {
                conn.commit();
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("[JdbcIncremental] All incremental ops done: {} total, {}ms", totalOps, elapsed);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("total_operations", totalOps);
        outputs.put("operations_insert", totalInsert);
        outputs.put("operations_update", totalUpdate);
        outputs.put("operations_delete", totalDelete);
        outputs.put("elapsed_ms", elapsed);
        return success(outputs);
    }

    private String buildInsert(String tableName, List<String> columns) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(quoteId(tableName)).append(" (");
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) { sb.append(", "); ph.append(", "); }
            sb.append(quoteId(columns.get(i)));
            ph.append("?");
        }
        sb.append(") VALUES (").append(ph).append(")");
        return sb.toString();
    }

    private int getMaxId(Connection conn, String tableName, String idColumn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     String.format("SELECT MAX(%s) FROM %s", quoteId(idColumn), quoteId(tableName)))) {
            if (rs.next()) {
                int max = rs.getInt(1);
                return max > 0 ? max : 0;
            }
        }
        return 0;
    }

    private void bindRandomValue(PreparedStatement ps, int idx, ThreadLocalRandom random) throws SQLException {
        int type = random.nextInt(5);
        switch (type) {
            case 0 -> ps.setInt(idx, random.nextInt(-10000, 10000));
            case 1 -> ps.setLong(idx, random.nextLong(-1000000, 1000000));
            case 2 -> ps.setString(idx, "inc_" + random.nextInt(100000));
            case 3 -> ps.setDouble(idx, random.nextDouble() * 1000 - 500);
            default -> ps.setBoolean(idx, random.nextBoolean());
        }
    }

    // ==================== JDBC Helpers ====================

    private Connection openConnection(JdbcParameter p) throws SQLException {
        Properties props = new Properties();
        if (p.getUsername() != null && !p.getUsername().isBlank()) props.setProperty("user", p.getUsername());
        if (p.getPassword() != null) props.setProperty("password", p.getPassword());

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = getClass().getClassLoader();

        String driverClass = p.getDriverClass();
        if (driverClass == null || driverClass.isBlank()) driverClass = detectDriver(p.getJdbcUrl());

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
        if (driverClass == null || driverClass.isBlank()) driverClass = detectDriver(p.getJdbcUrl());
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
        return "`" + name + "`";
    }
}
