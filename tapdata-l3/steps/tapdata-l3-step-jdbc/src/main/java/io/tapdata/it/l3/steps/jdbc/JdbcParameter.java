package io.tapdata.it.l3.steps.jdbc;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * Parameters for JDBC init/write steps.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class JdbcParameter extends ParameterConfig {

    // ===== 连接信息 =====

    private String jdbcUrl;
    private String username;
    private String password;
    private String driverClass;

    /**
     * JDBC Driver 的 Maven 坐标 (groupId:artifactId:version)。
     * <p>
     * 如果留空，会根据 jdbc_url 自动推断（mysql / postgresql / mariadb / sqlite / clickhouse）。
     * 自定义驱动请手动指定坐标 —— 框架会在运行时自动下载并追加到 Step 的 ClassLoader。
     */
    private String driverCoordinate;

    // ===== Init Source 专属 =====

    /** 要创建的表数量. */
    private int tableCount = 1;

    /** 每个表的初始行数. */
    private int rowsPerTable = 100;

    /** 每个表的列数 (自动生成 id + 随机列). */
    private int columnsPerTable = 5;

    /** 自定义表名列表 — 数量不足 tableCount 时自动生成. */
    private List<String> tableNames;

    /** 自定义列定义 — [{name, type, nullable}]. 为空则自动生成. */
    private List<Map<String, Object>> columnDefs;

    /** 如果为 true，初始化时先 DROP 再 CREATE. 默认 true. */
    private boolean dropIfExists = true;

    // ===== Write Incremental 专属 =====

    /** 目标表名. */
    private String tableName;

    /** 写入操作类型: "insert" / "update" / "delete" / "transaction". */
    private String operationType = "insert";

    /** 每种操作的数量 (insert=10, update=20, delete=100). */
    private Map<String, Integer> operationCounts;

    /** 是否用事务包裹. 默认 true. */
    private boolean useTransaction = true;

    /** 自定义 SQL 脚本 — 如果提供了这个就忽略 operationType/operationCounts. */
    private List<String> customSql;

    /** 自定义脚本文件路径 — 与 customSql 二选一. */
    private String scriptPath;

    // ===== JDBC Driver 坐标自动映射 =====

    /**
     * 从 jdbcUrl 前缀推断对应的 JDBC Driver Maven 坐标。
     * 映射表覆盖最常见的 6 种数据库；不在表中的返回 null（需要用户手动指定 driverCoordinate）。
     */
    private static final Map<String, String> URL_TO_COORDINATE = Map.of(
            "jdbc:mysql:",      "mysql:mysql-connector-java:8.0.29",
            "jdbc:mariadb:",    "org.mariadb.jdbc:mariadb-java-client:3.3.3",
            "jdbc:postgresql:", "org.postgresql:postgresql:42.1.4",
            "jdbc:sqlite:",     "org.xerial:sqlite-jdbc:3.45.3.0",
            "jdbc:clickhouse:", "com.clickhouse:clickhouse-jdbc:0.4.6",
            "jdbc:oracle:",     "com.oracle.database.jdbc:ojdbc11:23.3.0.23.09"
    );

    /**
     * 解析最终生效的 driverCoordinate —— 用户显式指定优先，否则按 jdbcUrl 自动推断。
     */
    public String resolveDriverCoordinate() {
        if (driverCoordinate != null && !driverCoordinate.isBlank()) return driverCoordinate;
        if (jdbcUrl == null) return null;
        for (Map.Entry<String, String> e : URL_TO_COORDINATE.entrySet()) {
            if (jdbcUrl.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * 从 jdbcUrl 前缀推断对应的 JDBC Driver class name。
     */
    public static String detectDriverClass(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        Map<String, String> urlToClass = Map.of(
                "jdbc:mysql:",      "com.mysql.cj.jdbc.Driver",
                "jdbc:mariadb:",    "org.mariadb.jdbc.Driver",
                "jdbc:postgresql:", "org.postgresql.Driver",
                "jdbc:sqlite:",     "org.sqlite.JDBC",
                "jdbc:clickhouse:", "com.clickhouse.jdbc.ClickHouseDriver",
                "jdbc:oracle:",     "oracle.jdbc.OracleDriver"
        );
        for (Map.Entry<String, String> e : urlToClass.entrySet()) {
            if (jdbcUrl.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }
}
