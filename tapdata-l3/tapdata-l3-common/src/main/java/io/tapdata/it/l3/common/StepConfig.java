package io.tapdata.it.l3.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

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

    /** Lifecycle hooks (beforeExecute / afterExecute / onError) — reserved for future use.
     *  Defined in design doc §4.5 DSL syntax table but execution engine not yet implemented. */
    private Map<String, Object> hooks;

    /**
     * User-specified extra Maven dependencies — resolved + loaded into the step's classloader.
     * Coordinate format: {@code groupId:artifactId:version}.
     * Example: {@code ["com.mysql:mysql-connector-j:8.3.0", "org.mongodb:mongodb-jdbc:2.1.0"]}.
     * <p>
     * These are <b>merged</b> with what the step's {@code ActionHandler.getRuntimeDependencies()}
     * declares — both sources contribute to the final dependency set.
     */
    private List<String> extraDeps;

    /**
     * 显式指定该 Step 实现的 Maven 坐标 — 格式 {@code groupId:artifactId:version}。
     * <p>
     * 优先级：StepConfig.use > CLI 内置默认注册表 (steps-registry.yaml) > 内置 step (fat jar 里的 @Component)。
     * 当 action 在 registry 或 fat jar 里找不到时，PluginCenter 会根据这个坐标从 Maven 仓库下载 jar + 传递依赖。
     * <p>
     * 典型用法：
     * <pre>
     * # 强制使用某个版本（覆盖默认注册表）
     * - id: create_db
     *   action: dbforge_create_database
     *   use: "io.tapdata.it.l3:tapdata-l3-step-dbforge:1.1.0"   # 显式 1.1.0
     *
     * # 不写 use — 走默认注册表 → 1.0.0-SNAPSHOT
     * - id: create_db
     *   action: dbforge_create_database
     * </pre>
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
}
