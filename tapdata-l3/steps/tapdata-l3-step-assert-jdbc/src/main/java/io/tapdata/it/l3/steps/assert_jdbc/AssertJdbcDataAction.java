package io.tapdata.it.l3.steps.assert_jdbc;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.exception.AssertionFailedException;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.util.*;

/**
 * Assert data in any JDBC-accessible database — with optional polling for eventual consistency.
 * <p>
 * Core use case: verify CDC data has arrived at the target database after a TapData sync task.
 *
 * <h3>Assertion Modes</h3>
 * <table>
 *   <tr><th>mode</th><th>required inputs</th><th>semantic</th></tr>
 *   <tr><td>{@code row_count_equals}</td><td>{@code expected_count}</td><td>EXACT row count match</td></tr>
 *   <tr><td>{@code row_count_gte}</td><td>{@code expected_min}</td><td>row count ≥ N</td></tr>
 *   <tr><td>{@code row_count_gt}</td><td>{@code expected_min}</td><td>row count > N</td></tr>
 *   <tr><td>{@code exists}</td><td>—</td><td>at least 1 row returned</td></tr>
 *   <tr><td>{@code empty}</td><td>—</td><td>0 rows returned</td></tr>
 *   <tr><td>{@code value_equals}</td><td>{@code column}, {@code expected}</td><td>first row's column equals value</td></tr>
 * </table>
 *
 * <h3>Polling</h3>
 * Add {@code poll_interval} and {@code max_attempts} inputs to retry until the assertion passes
 * or attempts are exhausted. Default is <b>no polling</b> (single attempt).
 *
 * <pre>
 * # Simple row count assertion
 * - id: verify_sync
 *   action: assert_jdbc_data
 *   inputs:
 *     jdbc_url: "#{ #steps['create_pg'].outputs['jdbc_url'] }"
 *     username: "#{ #steps['create_pg'].outputs['username'] }"
 *     password: "#{ #steps['create_pg'].outputs['password'] }"
 *     sql: "SELECT COUNT(*) FROM c3_test"
 *     assert_mode: "row_count_equals"
 *     expected_count: 2
 *
 * # Polling for CDC eventual consistency (wait up to 60s, check every 3s)
 * - id: wait_for_cdc
 *   action: assert_jdbc_data
 *   inputs:
 *     jdbc_url: "#{ #steps['create_pg'].outputs['jdbc_url'] }"
 *     username: "#{ #steps['create_pg'].outputs['username'] }"
 *     password: "#{ #steps['create_pg'].outputs['password'] }"
 *     sql: "SELECT COUNT(*) FROM orders WHERE id > 100"
 *     assert_mode: "row_count_gte"
 *     expected_min: 5
 *     poll_interval: 3
 *     max_attempts: 20
 * </pre>
 */
public class AssertJdbcDataAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(AssertJdbcDataAction.class);

    /** jdbc:xxx → driver class mapping. SQLite + 主流数据库都支持。 */
    private static final Map<String, String> DRIVER_MAP = Map.of(
            "jdbc:sqlite:", "org.sqlite.JDBC",
            "jdbc:mysql:", "com.mysql.cj.jdbc.Driver",
            "jdbc:postgresql:", "org.postgresql.Driver",
            "jdbc:mariadb:", "org.mariadb.jdbc.Driver",
            "jdbc:clickhouse:", "com.clickhouse.jdbc.ClickHouseDriver",
            "jdbc:oracle:", "oracle.jdbc.OracleDriver",
            "jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    );

    @Override
    public String getActionName() {
        return "assert_jdbc_data";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        AssertJdbcDataParameter p = config.getInputParameterConfig(AssertJdbcDataParameter.class);

        // 业务必填校验
        if (p.getJdbcUrl() == null || p.getJdbcUrl().isBlank()) {
            throw new IllegalArgumentException("Required input missing: jdbc_url");
        }
        if (p.getSql() == null || p.getSql().isBlank()) {
            throw new IllegalArgumentException("Required input missing: sql");
        }

        // Resolve driver
        String driverClass = p.getDriverClass();
        if (driverClass == null || driverClass.isBlank()) {
            driverClass = detectDriver(p.getJdbcUrl());
        }
        if (driverClass != null && !driverClass.isBlank()) {
            try {
                // 优先用当前线程的 context CL —— BaseStep 已经在 worker 线程里设置好了
                // 对于内置 step + DSL extra_deps 场景，context CL 就是我们重建的 URLClassLoader
                // 里面包含了 sqlite-jdbc 等动态下载的 jar
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = getClass().getClassLoader();
                Class.forName(driverClass, true, cl);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("JDBC driver not found: " + driverClass
                        + " — add it via DSL extra_deps, available CL: "
                        + Thread.currentThread().getContextClassLoader(), e);
            }
        }

        // 判断 SQL 类型
        String sqlType = p.getSqlType();
        if ("auto".equals(sqlType)) {
            String trimmed = p.getSql().trim().toLowerCase();
            if (trimmed.startsWith("select") || trimmed.startsWith("show") || trimmed.startsWith("with")
                    || trimmed.startsWith("pragma") || trimmed.startsWith("values")) {
                sqlType = "query";
            } else {
                sqlType = "execute"; // DDL/DML
            }
        }

        long t0 = System.currentTimeMillis();

        if ("execute".equals(sqlType)) {
            // === DDL / DML 执行模式（无 assertion）===
            log.info("[AssertJDBC] EXECUTE: sql='{}'", truncate(p.getSql(), 120));
            int updated = executeUpdate(p.getJdbcUrl(), p.getUsername(), p.getPassword(), p.getSql());
            long elapsedMs = System.currentTimeMillis() - t0;
            log.info("[AssertJDBC] EXECUTE done: {} row(s) affected ({}ms)", updated, elapsedMs);
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("rows_affected", updated);
            outputs.put("elapsed_ms", elapsedMs);
            return success(outputs);
        }

        // === Query + Assert 模式 ===
        String assertMode = p.getAssertMode();
        if (assertMode == null || assertMode.isBlank()) {
            throw new IllegalArgumentException("assert_mode is required for SELECT queries (row_count_equals / exists / value_equals / ...)");
        }

        int pollIntervalSec = p.getPollInterval();
        int maxAttempts = p.getMaxAttempts();
        Integer expectedCount = p.getExpectedCount();
        Integer expectedMin = p.getExpectedMin();
        Object expectedValue = p.getExpected();
        String column = p.getColumn();

        log.info("[AssertJDBC] {}: sql='{}' [{}]", assertMode, truncate(p.getSql(), 120),
                pollIntervalSec > 0 ? String.format("poll %ds × %d attempts", pollIntervalSec, maxAttempts) : "single attempt");

        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                List<Map<String, Object>> rows = executeQuery(p.getJdbcUrl(), p.getUsername(), p.getPassword(), p.getSql());
                evaluateAssertion(assertMode, rows, expectedCount, expectedMin, expectedValue, column);

                long elapsedMs = System.currentTimeMillis() - t0;
                log.info("[AssertJDBC] PASSED after {} attempt(s) ({}ms)", attempt, elapsedMs);
                Map<String, Object> outputs = new LinkedHashMap<>();
                outputs.put("attempts", attempt);
                outputs.put("elapsed_ms", elapsedMs);
                outputs.put("row_count", rows.size());
                return success(outputs);
            } catch (AssertionFailedException e) {
                lastFailure = e;
                if (attempt < maxAttempts && pollIntervalSec > 0) {
                    Thread.sleep(Duration.ofSeconds(pollIntervalSec).toMillis());
                }
            }
        }

        throw (AssertionFailedException) lastFailure;
    }

    // ==================== Assertion evaluation ====================

    private void evaluateAssertion(String mode, List<Map<String, Object>> rows,
                                   int expectedCount, int expectedMin, Object expectedValue, String column) {
        int rowCount = rows.size();
        switch (mode) {
            case "row_count_equals" -> {
                if (expectedCount < 0) throw new IllegalArgumentException("assert_mode=row_count_equals requires 'expected_count'");
                if (rowCount != expectedCount) {
                    throw new AssertionFailedException(
                            String.format("Row count assertion FAILED: expected=%d, actual=%d", expectedCount, rowCount));
                }
            }
            case "row_count_gte" -> {
                if (expectedMin < 0) throw new IllegalArgumentException("assert_mode=row_count_gte requires 'expected_min'");
                if (rowCount < expectedMin) {
                    throw new AssertionFailedException(
                            String.format("Row count >= assertion FAILED: expected>=%d, actual=%d", expectedMin, rowCount));
                }
            }
            case "row_count_gt" -> {
                if (expectedMin < 0) throw new IllegalArgumentException("assert_mode=row_count_gt requires 'expected_min'");
                if (rowCount <= expectedMin) {
                    throw new AssertionFailedException(
                            String.format("Row count > assertion FAILED: expected>%d, actual=%d", expectedMin, rowCount));
                }
            }
            case "exists" -> {
                if (rowCount == 0) {
                    throw new AssertionFailedException("Expected at least 1 row, but got 0");
                }
            }
            case "empty" -> {
                if (rowCount > 0) {
                    throw new AssertionFailedException(
                            String.format("Expected 0 rows, but got %d", rowCount));
                }
            }
            case "value_equals" -> {
                if (column == null || expectedValue == null) {
                    throw new IllegalArgumentException("assert_mode=value_equals requires 'column' and 'expected'");
                }
                if (rowCount == 0) {
                    throw new AssertionFailedException("Expected value_equals but query returned 0 rows");
                }
                Object actual = rows.get(0).get(column);
                if (!Objects.equals(String.valueOf(expectedValue), String.valueOf(actual))) {
                    throw new AssertionFailedException(
                            String.format("Value assertion FAILED on column '%s': expected=%s, actual=%s",
                                    column, expectedValue, actual));
                }
            }
            default -> throw new IllegalArgumentException("Unknown assert_mode: " + mode);
        }
    }

    // ==================== JDBC ====================

    /** 执行 SELECT / SHOW / PRAGMA 查询。 */
    private List<Map<String, Object>> executeQuery(String jdbcUrl, String username, String password, String sql)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = openConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 1; c <= colCount; c++) {
                    row.put(meta.getColumnLabel(c), rs.getObject(c));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** 执行 DDL / DML — CREATE / INSERT / UPDATE / DELETE / DROP 等。 */
    private int executeUpdate(String jdbcUrl, String username, String password, String sql)
            throws SQLException {
        try (Connection conn = openConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        }
    }

    /** 打开 JDBC 连接 — username/password 为 null 时用无认证连接（SQLite、Derby 等文件数据库）。 */
    private Connection openConnection(String jdbcUrl, String username, String password) throws SQLException {
        // 关键：直接用 Driver.connect() 绕开 DriverManager 的 classloader 安全过滤。
        // Java 9+ 的 DriverManager 只会尝试"与自己同一 classloader"加载的 Driver，
        // 而 SQLite JDBC 是通过 context CL 动态加载的 PluginClassLoader 里的，
        // 与 AssertJdbcDataAction 的宿主 classloader 不同，所以 DriverManager 看不到它。
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = getClass().getClassLoader();

        String driverClassName = detectDriver(jdbcUrl);
        if (driverClassName == null || driverClassName.isBlank()) {
            // 没有已知的 driver 映射 — 退化为 DriverManager.getConnection（靠 JDBC 4.0 自动发现）
            Properties props = buildProps(username, password);
            return DriverManager.getConnection(jdbcUrl, props);
        }

        try {
            Class<?> driverCls = Class.forName(driverClassName, true, cl);
            java.sql.Driver driver = (java.sql.Driver) driverCls.getDeclaredConstructor().newInstance();
            Properties props = buildProps(username, password);
            Connection conn = driver.connect(jdbcUrl, props);
            if (conn == null) {
                throw new SQLException("Driver " + driverClassName + " refused URL " + jdbcUrl
                        + " (connect() returned null)");
            }
            return conn;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Failed to instantiate JDBC driver " + driverClassName, e);
        }
    }

    private Properties buildProps(String username, String password) {
        Properties props = new Properties();
        if (username != null && !username.isBlank()) props.setProperty("user", username);
        if (password != null) props.setProperty("password", password);
        return props;
    }

    private static String detectDriver(String jdbcUrl) {
        for (Map.Entry<String, String> e : DRIVER_MAP.entrySet()) {
            if (jdbcUrl.startsWith(e.getKey())) return e.getValue();
        }
        return "";
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
