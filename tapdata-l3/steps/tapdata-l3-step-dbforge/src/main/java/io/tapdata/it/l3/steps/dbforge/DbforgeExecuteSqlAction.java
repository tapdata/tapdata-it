package io.tapdata.it.l3.steps.dbforge;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Execute SQL/DDL/DML on any JDBC-accessible database.
 * <p>
 * No Spring dependency — plain BaseStep with no-arg constructor.
 * Drivers resolved from jdbc_url prefix or explicit driver_class input.
 *
 * <pre>
 * - id: init_table
 *   action: dbforge_execute_sql
 *   inputs:
 *     jdbc_url: "jdbc:mysql://..."
 *     username: "root"
 *     password: "xxx"
 *     sql: |
 *       CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR(50));
 *       INSERT INTO test VALUES (1, 'Alice');
 * </pre>
 */
public class DbforgeExecuteSqlAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(DbforgeExecuteSqlAction.class);

    /** jdbc:xxx → driver class mapping for built-in databases. */
    private static final Map<String, String> DRIVER_MAP = Map.of(
            "jdbc:mysql:", "com.mysql.cj.jdbc.Driver",
            "jdbc:postgresql:", "org.postgresql.Driver",
            "jdbc:mariadb:", "org.mariadb.jdbc.Driver",
            "jdbc:clickhouse:", "com.clickhouse.jdbc.ClickHouseDriver",
            "jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:oracle:", "oracle.jdbc.OracleDriver"
    );

    @Override
    public String getActionName() {
        return "dbforge_execute_sql";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        Map<String, Object> inputs = config.getInputs();

        String jdbcUrl = required(inputs, "jdbc_url");
        String username = required(inputs, "username");
        String password = (String) inputs.get("password"); // can be null for some DBs
        String sql = required(inputs, "sql");
        String driverClass = (String) inputs.get("driver_class"); // optional, auto-detect if missing

        // Resolve driver class
        if (driverClass == null || driverClass.isBlank()) {
            driverClass = detectDriver(jdbcUrl);
            log.info("[DBForge] Auto-detected driver: {}", driverClass);
        }

        // Load driver (DriverManager will find it; explicit load as fallback)
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "JDBC driver not found: " + driverClass + ". Add it to classpath or use driver_class input to override.", e);
        }

        // Split multi-statement SQL
        List<String> statements = splitStatements(sql);
        log.info("[DBForge] Executing {} statement(s) on {}", statements.size(), jdbcUrl);

        int totalAffected = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            for (int i = 0; i < statements.size(); i++) {
                String stmt = statements.get(i).trim();
                if (stmt.isEmpty()) continue;

                log.info("[DBForge] [{}/{}] {}...", i + 1, statements.size(), truncate(stmt, 80));
                Map<String, Object> stmtResult = new LinkedHashMap<>();
                stmtResult.put("index", i + 1);

                try (Statement s = conn.createStatement()) {
                    if (s.execute(stmt)) {
                        // Query — collect first column of first row (useful for SELECT 1)
                        try (ResultSet rs = s.getResultSet()) {
                            int colCount = rs.getMetaData().getColumnCount();
                            List<Object> rows = new ArrayList<>();
                            while (rs.next() && rows.size() < 100) {
                                if (colCount == 1) {
                                    rows.add(rs.getObject(1));
                                } else {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    for (int c = 1; c <= colCount; c++) {
                                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                                    }
                                    rows.add(row);
                                }
                            }
                            stmtResult.put("rows", rows);
                            log.info("[DBForge] Query returned {} row(s)", rows.size());
                        }
                    } else {
                        int affected = s.getUpdateCount();
                        totalAffected += Math.max(0, affected);
                        stmtResult.put("affected_rows", Math.max(0, affected));
                        log.info("[DBForge] DML/DDL affected {} row(s)", affected);
                    }
                }
                results.add(stmtResult);
            }
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("affected_rows", totalAffected);
        outputs.put("statement_count", statements.size());
        outputs.put("results", results);
        return success(outputs);
    }

    /** Detect JDBC driver class from URL prefix. */
    private static String detectDriver(String jdbcUrl) {
        for (Map.Entry<String, String> entry : DRIVER_MAP.entrySet()) {
            if (jdbcUrl.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        // Fallback: let DriverManager figure it out
        log.warn("[DBForge] Unknown jdbc: scheme in '{}', relying on DriverManager auto-discovery", jdbcUrl);
        return "";
    }

    /** Split SQL by semicolons, respecting strings (naive: just split by ; and filter empties). */
    private static List<String> splitStatements(String sql) {
        // Simple split — sufficient for C3/C5 use cases.
        // Future: can be enhanced to respect string literals (single/double quotes)
        String[] parts = sql.split(";");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                result.add(p);
            }
        }
        return result;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String required(Map<String, Object> inputs, String key) {
        Object v = inputs.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Required input missing: " + key);
        }
        return v.toString();
    }
}
