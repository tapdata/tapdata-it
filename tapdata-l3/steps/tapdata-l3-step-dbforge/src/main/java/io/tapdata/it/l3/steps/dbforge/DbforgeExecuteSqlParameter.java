package io.tapdata.it.l3.steps.dbforge;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * dbforge_execute_sql Step 专属参数配置。
 * <p>
 * YAML 输入（snake_case）→ Java 字段（camelCase）由 Jackson SNAKE_CASE 自动映射。
 *
 * <pre>
 * inputs:
 *   jdbc_url: "jdbc:mysql://..."
 *   username: "root"
 *   password: "xxx"
 *   sql: "CREATE TABLE ..."
 *   driver_class: "com.mysql.cj.jdbc.Driver"  # optional
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DbforgeExecuteSqlParameter extends ParameterConfig {

    /** JDBC URL — 必填。 */
    private String jdbcUrl;

    /** 数据库用户名 — 必填。 */
    private String username;

    /** 数据库密码 — 部分 DB（如 SQLite）可以为 null。 */
    private String password;

    /** 要执行的 SQL（支持多语句用分号分隔） — 必填。 */
    private String sql;

    /** 显式指定 JDBC driver class — 可选，缺省时根据 jdbcUrl 前缀自动探测。 */
    private String driverClass;
}
