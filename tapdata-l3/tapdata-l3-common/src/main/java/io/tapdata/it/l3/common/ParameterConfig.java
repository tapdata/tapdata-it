package io.tapdata.it.l3.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 所有 Step 参数配置类的**基类**。
 * <p>
 * 每个 Step 实现可以定义自己的 XxxParameterConfig 继承本类，
 * 继承后自动拥有 {@code stepId}、{@code action}、{@code timeoutSeconds} 这三个通用字段，
 * 然后在子类里声明自己专属的输入参数（如 jdbcUrl、sql、assertMode 等）。
 * <p>
 * Step 内部通过 {@link StepConfig#getInputParameterConfig(Class)} 一次性拿到类型安全的参数对象，
 * 告别繁琐的 {@code config.getInputs().get("jdbc_url")} + 类型强转 + required helper。
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 1. 定义子类（一般放在 step 模块里，与 XxxAction 同包）
 * public class JdbcExecuteSqlParameter extends ParameterConfig {
 *     private String jdbcUrl;
 *     private String username;
 *     private String password;
 *     private String sql;
 *     private String driverClass;  // optional
 *     // Lombok @Data 自动生成 getter/setter
 * }
 *
 * // 2. Step 内部一行拿到
 * protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
 *     JdbcExecuteSqlParameter p = config.getInputParameterConfig(JdbcExecuteSqlParameter.class);
 *     if (p.getSql() == null) throw new IllegalArgumentException("sql is required");
 *     // ... 直接 p.getJdbcUrl() / p.getUsername()
 * }
 * </pre>
 *
 * <h3>字段命名约定</h3>
 * <p>
 * YAML DSL 用 snake_case 写输入参数（{@code jdbc_url}, {@code assert_mode}），
 * Java 字段用 camelCase（{@code jdbcUrl}, {@code assertMode}）。
 * Jackson 自动处理 snake_case ↔ camelCase 映射 —— Step 子类不需要额外注解。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParameterConfig {

    /** 当前 step 的 id —— 方便参数对象在日志里定位自己。 */
    private String stepId;

    /** 当前 step 的 action 名。 */
    private String action;

    /** StepConfig 上声明的 timeout（秒）。-1 = 未声明，走 BaseStep 默认值。 */
    private int timeoutSeconds = -1;
}
