package io.tapdata.it.l3.plugin;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;

import java.util.Collections;
import java.util.List;

/**
 * Reusable test action interface — the SPI contract for ALL steps in the L3 framework.
 * <p>
 * External step libraries only need to implement this interface (zero Spring Boot dependency)
 * and compile into an independent jar — the L3 CLI will load it dynamically at runtime via
 * {@code MavenPluginResolver} (download from Maven repo) or PF4J (plugin dir scan).
 * <p>
 * <b>Lifecycle</b> (in execution order, managed by {@code BaseStep.execute()}):
 * <pre>
 *   new ActionClass()                      ← 反射实例化（必须有无参构造器）
 *       │
 *       ├─ init()                          ← 实例创建后立即调用（一次）
 *       │
 *       └─ execute(StepConfig, ScenarioContext)   ← 每个 step 调用一次
 *            ├─ before(config, ctx)        ← execute 内部，doExecute 之前
 *            ├─ doExecute(config, ctx)     ← 子类实现核心逻辑
 *            └─ after(config, ctx, result) ← doExecute 之后，无论成功失败都执行
 *                  │
 *                  └─ destroy()           ← 整个 Scenario 结束时调用（所有 action 的 destroy 都触发）
 * </pre>
 * <p>
 * 四个生命周期钩子默认实现都是空操作 — 子类按需覆盖。
 * <p>
 * The {@link #getRuntimeDependencies()} hook lets a step declare which additional Maven
 * coordinates it needs at runtime (e.g. a custom JDBC driver). PluginCenter collects these
 * and merges them with {@code extra_deps} from DSL YAML, then resolves & loads all transitive
 * deps via Maven Artifact Resolver before creating the step's classloader.
 * <p>
 * Coordinate format is {@code groupId:artifactId:version} (classic Maven triple).
 */
public interface ActionHandler {

    /**
     * Unique action name — referenced by {@code action: "xxx"} in DSL YAML.
     */
    String getActionName();

    /**
     * Execute the step's core logic.
     *
     * @param config  Step configuration (inputs / outputs / hooks / timeout / extra_deps, etc.)
     * @param context Global cross-step context (variable passing, step output references)
     * @throws Exception Failure will trigger the failure diagnostics panel.
     */
    void execute(StepConfig config, ScenarioContext context) throws Exception;

    // ==================== 生命周期钩子 (默认空实现，按需覆盖) ====================

    /**
     * 实例创建后立即调用 — 只执行一次。
     * 适合做"不需要 DSL 配置"的初始化（比如打开长期连接、缓存客户端）。
     * <p>
     * 如果需要根据 StepConfig 做初始化，请用 {@link #before}。
     */
    default void init() throws Exception { /* no-op */ }

    /**
     * 每次 doExecute 之前调用 — 可以访问 StepConfig + ScenarioContext。
     * <p>
     * 典型用法：根据 DSL inputs 创建 DbforgeClient / TmClient 等依赖对象。
     * 由于 action 实例可能被复用（Scenario 内相同 action 名可能被多次调用），
     * before 每次都会执行。
     */
    default void before(StepConfig config, ScenarioContext ctx) throws Exception { /* no-op */ }

    /**
     * 每次 doExecute 之后调用 — 无论成功还是失败都会执行。
     * 适合做资源释放、日志收尾、指标上报。
     *
     * @param success true = doExecute 正常返回, false = 抛出了异常
     * @param error   doExecute 抛出的异常（success=false 时才有值，可能为 null）
     */
    default void after(StepConfig config, ScenarioContext ctx, boolean success, Throwable error) throws Exception { /* no-op */ }

    /**
     * 整个 Scenario 结束时调用 — 清理资源（HTTP client、线程池、数据库连接等）。
     * 所有 action 实例的 destroy 都会被触发，顺序与调用顺序一致。
     */
    default void destroy() throws Exception { /* no-op */ }

    // ==================== 运行时依赖 ====================

    /**
     * Declare Maven artifacts this step needs at runtime.
     * <p>
     * Example — a Mongo assert step that requires the Mongo JDBC driver:
     * <pre>
     *   default List&lt;String&gt; getRuntimeDependencies() {
     *       return List.of("org.mongodb:mongodb-jdbc:2.1.0");
     *   }
     * </pre>
     * <p>
     * Coordinate format: {@code groupId:artifactId:version}.
     * Transitive deps are resolved automatically by Maven Artifact Resolver.
     * The default implementation returns an empty list (no extra deps needed).
     * <p>
     * DSL-level {@code extra_deps} (from {@link StepConfig}) are <b>merged</b> with
     * what this method declares — both sources contribute to the final dependency set.
     */
    default List<String> getRuntimeDependencies() {
        return Collections.emptyList();
    }
}
