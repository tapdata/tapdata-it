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
}
