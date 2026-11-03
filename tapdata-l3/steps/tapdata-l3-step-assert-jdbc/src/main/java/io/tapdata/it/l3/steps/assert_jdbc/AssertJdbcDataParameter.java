package io.tapdata.it.l3.steps.assert_jdbc;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * assert_jdbc_data Step 专属参数配置 —— 字段最多的一个 Step。
 * <p>
 * YAML 输入（snake_case）→ Java 字段（camelCase）由 Jackson SNAKE_CASE 自动映射。
 * 可选字段不填时保持 Java 默认值（null / 0 / false）。
 *
 * <pre>
 * inputs:
 *   jdbc_url: "..."
 *   username: "root"
 *   password: "xxx"
 *   sql: "SELECT COUNT(*) FROM ..."
 *   sql_type: "auto"              # auto | query | execute — 默认 auto
 *   assert_mode: "row_count_equals" # row_count_equals | row_count_gte | ... — SELECT 时必填
 *   driver_class: "..."           # 可选，自动探测
 *   poll_interval: 3               # 秒，默认 0 = 不轮询
 *   max_attempts: 20              # 默认 1
 *   expected_count: 2              # row_count_equals 模式用
 *   expected_min: 5                # row_count_gte / row_count_gt 模式用
 *   expected: "Alice"              # value_equals 模式用
 *   column: "name"                 # value_equals 模式用
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AssertJdbcDataParameter extends ParameterConfig {

    /** JDBC URL — 必填。 */
    private String jdbcUrl;

    /** 数据库用户名 — 可选（SQLite 等文件数据库不需要）。 */
    private String username;

    /** 数据库密码 — 可选。 */
    private String password;

    /** 要执行的 SQL — 必填。 */
    private String sql;

    /** SQL 类型：auto | query | execute — 默认 auto（根据 SQL 前缀自动判断）。 */
    private String sqlType = "auto";

    /** Assertion 模式 — SELECT 查询时必填；DDL/DML 执行模式下不需要。 */
    private String assertMode;

    /** 显式指定 JDBC driver class — 可选，缺省时自动探测。 */
    private String driverClass;

    /** 轮询间隔（秒）— 默认 0 表示只执行一次不重试。 */
    private int pollInterval;

    /** 最大尝试次数 — 默认 1。 */
    private int maxAttempts = 1;

    /** value_equals 模式下的期望值 — 保持 Object 类型兼容数字和字符串比较。 */
    private Object expected;

    /** row_count_equals 模式的期望行数。 */
    private Integer expectedCount;

    /** row_count_gte / row_count_gt 模式的期望最小行数。 */
    private Integer expectedMin;

    /** value_equals 模式下要比较的列名。 */
    private String column;
}
