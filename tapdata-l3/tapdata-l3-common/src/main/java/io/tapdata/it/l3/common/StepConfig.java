package io.tapdata.it.l3.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DSL Step config — the in-memory representation of a YAML step block.
 * <p>
 * {@code action} is the only required field. Optional fields are populated during parsing.
 * <p>
 * {@code hooks} — lifecycle hooks map, reserved for beforeExecute / afterExecute / onError
 * callbacks (SpEL expressions or action references). Defined by design doc §4.5 DSL syntax table.
 * Shape is intentionally generic ({@code Map<String, Object>}) so the hook execution engine
 * (in BaseStep or StepRunner) can evolve without breaking this contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepConfig {

    /** Jackson ObjectMapper — snake_case ↔ camelCase 自动映射，忽略未知字段。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * ParameterConfig 实例缓存 — Class → 已解析好的参数对象。
     * 同一个 StepConfig 生命周期内，对同一 Class 的多次 getInputParameterConfig 调用返回同一个实例。
     * 标记为 transient 避免 YAML 序列化。
     */
    private transient Map<Class<? extends ParameterConfig>, ParameterConfig> parameterConfigCache;

    /** Unique step id within a scenario. Used for cross-step output references. */
    private String id;

    /** Action name (e.g. "dbforge_create_database", "tm_api_create_datasource"). */
    private String action;

    /** Input parameters — values may contain SpEL expressions {@code #{ ... }}. */
    private Map<String, Object> inputs;

    /** Output keys declared by this step (used by downstream steps). */
    private List<String> outputs;

    /** Hard timeout in seconds. {@code <= 0} means "no explicit timeout declared" —
     *  parsers enforce it for assert_* / wait_* actions. */
    private int timeoutSeconds;

    /** Optional cleanup hook: keep resources alive even if step succeeded (for debugging). */
    private boolean keepAlive;

    /**
     * expect_failure: true — 该 step 失败视为通过（用于负向用例，如验证超时被抛出、非法参数被拒绝）。
     * 若 step 反而成功了，则整个 step 判失败。可选搭配 {@link #expectFailureMessage} 做消息子串断言。
     */
    private boolean expectFailure;

    /** expect_failure 场景下可选的错误消息子串断言 — 实际错误消息必须包含该子串，否则仍判失败。 */
    private String expectFailureMessage;

    /** Lifecycle hooks (beforeExecute / afterExecute / onError) — reserved for future use.
     *  Defined in design doc §4.5 DSL syntax table but execution engine not yet implemented. */
    private Map<String, Object> hooks;

    /**
     * DSL 里声明的 extra_deps —— 额外 Maven 依赖坐标列表（{@code groupId:artifactId:version}）。
     * 运行时由 PluginCenter 解析下载后注入到 step 专属 URLClassLoader。
     * 这是动态加载 JDBC 驱动等运行时依赖的核心机制。
     */
    private List<String> extraDeps;

    /**
     * 显式指定该 Step 实现的 Maven 坐标 — 格式 {@code groupId:artifactId:version}。
     * <p>
     * 优先级：StepConfig.use > CLI 内置默认注册表 (steps-registry.yaml) > 内置 step (fat jar 里的 @Component)。
     * 当 action 在 registry 或 fat jar 里找不到时，PluginCenter 会根据这个坐标从 Maven 仓库下载 jar + 传递依赖。
     */
    private String use;

    /**
     * 运行时注入的 step 专属 ClassLoader — 由 StepRunner 在 addRuntimeDependencies 之后设置。
     * 这个 CL 里包含了宿主 CL + 所有 extra_deps 下载的 jar。
     * <p>
     * 标记 transient 避免 YAML 序列化 / SpEL 解析器处理。
     * 仅在运行时（StepRunner → BaseStep → AssertJdbcDataAction）链路中传递。
     */
    private transient ClassLoader stepClassLoader;

    // ==================== ParameterConfig 便捷方法 ====================

    /**
     * 将 {@code inputs} Map 自动转换到 Step 专属 ParameterConfig 对象。
     * <p>
     * 核心价值：Step 内部可以用类型安全的 bean 代替繁琐的
     * {@code config.getInputs().get("jdbc_url")} + 类型强转 + required helper。
     *
     * <h4>转换规则</h4>
     * <ul>
     *   <li>YAML snake_case → Java camelCase 自动映射（jackson-databind SNAKE_CASE 策略）</li>
     *   <li>参数对象上 {@code stepId}、{@code action}、{@code timeoutSeconds} 三个通用字段由本方法自动填充</li>
     *   <li>未知字段忽略（FAIL_ON_UNKNOWN_PROPERTIES = false）—— YAML 里多写的参数不会报错</li>
     *   <li>缺少必填字段不会自动校验，由 Step 子类自己在 doExecute 里按需检查</li>
     * </ul>
     *
     * <h4>缓存语义</h4>
     * <p>同一个 StepConfig 实例内，对同一个 {@code parameterClass} 的多次调用返回**同一个对象**。
     * 如果 inputs 被 SpEL 二次求值后更新了（比如输出引用从 null 变成实际值），需要调 {@link #clearParameterConfigCache()} 重新解析。
     *
     * @param parameterClass Step 专属的 ParameterConfig 子类（必须有无参构造器 + Lombok @Data）
     * @return 已填充好字段的参数对象
     * @throws IllegalArgumentException 当 inputs 解析失败或 parameterClass 不是 ParameterConfig 子类时抛出
     */
    @SuppressWarnings("unchecked")
    public <T extends ParameterConfig> T getInputParameterConfig(Class<T> parameterClass) {
        if (parameterClass == null || !ParameterConfig.class.isAssignableFrom(parameterClass)) {
            throw new IllegalArgumentException(
                    parameterClass + " must be a subclass of ParameterConfig");
        }

        // 懒初始化缓存（StepConfig 是 @Builder，cache 初始为 null）
        if (parameterConfigCache == null) {
            parameterConfigCache = new ConcurrentHashMap<>();
        }

        ParameterConfig cached = parameterConfigCache.get(parameterClass);
        if (cached != null) {
            return (T) cached;
        }

        try {
            T instance;
            if (inputs == null || inputs.isEmpty()) {
                // 没有 inputs → 反射无参构造器创建实例
                instance = parameterClass.getDeclaredConstructor().newInstance();
            } else {
                instance = OBJECT_MAPPER.convertValue(inputs, parameterClass);
            }

            // 填充基类通用字段
            instance.setStepId(id);
            instance.setAction(action);
            if (timeoutSeconds > 0) {
                instance.setTimeoutSeconds(timeoutSeconds);
            }

            parameterConfigCache.put(parameterClass, instance);
            return instance;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to convert inputs to " + parameterClass.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** 清除 parameterConfig 缓存 —— inputs 被 SpEL 二次求值后需要重新解析时调用。 */
    public void clearParameterConfigCache() {
        if (parameterConfigCache != null) {
            parameterConfigCache.clear();
        }
    }
}
