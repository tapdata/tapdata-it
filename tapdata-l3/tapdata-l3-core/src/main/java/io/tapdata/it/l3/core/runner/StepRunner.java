package io.tapdata.it.l3.core.runner;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.core.console.ConsoleUI;
import io.tapdata.it.l3.core.plugin.PluginCenter;
import io.tapdata.it.l3.core.plugin.PluginCenter.PluginLoadException;
import io.tapdata.it.l3.plugin.ActionHandler;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Step 执行器 — 负责获取 Step、SpEL 表达式求值、计时、异常分类。
 * <p>
 * 唯一依赖 {@link PluginCenter} 获取 {@link ActionHandler} 实例——
 * 不自己维护注册表、不直接 import PF4J。符合设计文档 §7.9。
 * <p>
 * 串行和并行 Step 都走本类的 {@link #run(StepConfig, ScenarioContext)}。
 */
@Component
public class StepRunner {

    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);

    private final PluginCenter pluginCenter;

    public StepRunner(PluginCenter pluginCenter) {
        this.pluginCenter = pluginCenter;
    }

    public StepResult run(StepConfig stepConfig, ScenarioContext ctx, Collection<ActionHandler> usedHandlers) {
        // 1. 通过 PluginCenter 获取 Step（自动懒加载外置 step）
        //    优先级：已内置/已加载 > DSL `use` 显式坐标 > 默认注册表 (steps-registry.yaml)
        ActionHandler handler;
        try {
            handler = pluginCenter.getOrLoadAction(stepConfig.getAction(), stepConfig.getUse());
        } catch (PluginCenter.PluginLoadException e) {
            Throwable err = new IllegalStateException(String.format(
                    "Step '%s' load failed: %s. Use `use` field or check steps-registry.yaml. Registry: %s",
                    stepConfig.getAction(), e.getMessage(), pluginCenter.getDefaultRegistry().keySet()), e);
            log.error("[Step] '{}' → {} load failed: {}", stepConfig.getId(),
                    stepConfig.getAction(), e.getMessage());
            log.debug("[Step] '{}' load failure detail", stepConfig.getId(), e);
            return StepResult.failure(stepConfig, err, 0, Map.of());
        }

        // 注册本次独立 handler 实例到 scenario 级收集 —— 用于最终统一 destroy
        if (usedHandlers != null) usedHandlers.add(handler);

        // 2. 运行时依赖自动加载 — 合并 ActionHandler.getRuntimeDependencies() + DSL extra_deps
        //    这是 extra_deps 生效的唯一触发点：每次 step 执行前都会检查并解析依赖。
        //    已加载过的依赖会被 URLClassLoader 缓存，不会重复解析。
        try {
            int added = pluginCenter.addRuntimeDependencies(
                    stepConfig.getAction(), stepConfig.getExtraDeps());
            if (added > 0) {
                ConsoleUI.event("deps", "+" + added + " jar(s) for " + stepConfig.getAction());
            }
        } catch (PluginCenter.PluginLoadException e) {
            return StepResult.failure(stepConfig, new IllegalStateException(
                    "Failed to load runtime dependencies: " + e.getMessage(), e.getCause()), 0, Map.of());
        }

        // 3. SpEL 表达式求值
        Map<String, Object> resolvedInputs = ctx.evaluateInputs(stepConfig.getInputs());
        StepConfig resolved = StepConfig.builder()
                .id(stepConfig.getId())
                .action(stepConfig.getAction())
                .inputs(resolvedInputs)
                .outputs(stepConfig.getOutputs())
                .timeoutSeconds(stepConfig.getTimeoutSeconds())
                .keepAlive(stepConfig.isKeepAlive())
                .hooks(stepConfig.getHooks())
                .extraDeps(stepConfig.getExtraDeps())
                .use(stepConfig.getUse())  // DSL 显式坐标透传
                .stepClassLoader(pluginCenter.getClassLoaderFor(stepConfig.getAction()))  // 运行时注入 step 专属 CL
                .build();

        // 4. 执行 + 计时 + 异常分类
        Instant start = Instant.now();
        long t0 = System.nanoTime();
        try {
            log.debug("[Step] Starting '{}' → {} (handler={}@{}) ...",
                    stepConfig.getId(), stepConfig.getAction(), handler.getClass().getSimpleName(),
                    Integer.toHexString(System.identityHashCode(handler)));
            handler.execute(resolved, ctx);
            long durationMs = (System.nanoTime() - t0) / 1_000_000;
            log.debug("[Step] '{}' → {} [SUCCESS] ({}ms)", stepConfig.getId(),
                    stepConfig.getAction(), durationMs);
            // expect_failure 反向断言：step 意外成功 → 判失败
            if (stepConfig.isExpectFailure()) {
                String msg = String.format(
                        "expect_failure=true but step '%s' (%s) SUCCEEDED — expected this step to fail",
                        stepConfig.getId(), stepConfig.getAction());
                log.warn("[Step] {}", msg);
                return StepResult.failure(stepConfig, new AssertionError(msg), durationMs,
                        snapshotOutputs(stepConfig.getId(), ctx));
            }
            return StepResult.success(stepConfig, durationMs, snapshotOutputs(stepConfig.getId(), ctx));
        } catch (Throwable t) {
            long durationMs = (System.nanoTime() - t0) / 1_000_000;
            log.debug("[Step] '{}' → {} [FAILED] ({}ms)", stepConfig.getId(),
                    stepConfig.getAction(), durationMs, t);
            // expect_failure: 失败即通过（可选错误消息子串断言）
            if (stepConfig.isExpectFailure()) {
                String actualMsg = t.getMessage() == null ? "" : t.getMessage();
                if (stepConfig.getExpectFailureMessage() != null && !stepConfig.getExpectFailureMessage().isBlank()
                        && !actualMsg.contains(stepConfig.getExpectFailureMessage())) {
                    String msg = String.format(
                            "expect_failure_message='%s' not matched — actual error: %s: %s",
                            stepConfig.getExpectFailureMessage(), t.getClass().getSimpleName(), actualMsg);
                    log.warn("[Step] '{}' {}", stepConfig.getId(), msg);
                    return StepResult.failure(stepConfig, new AssertionError(msg), durationMs,
                            snapshotOutputs(stepConfig.getId(), ctx));
                }
                ConsoleUI.event("expect_failure", "step '" + stepConfig.getId()
                        + "' failed as expected (" + t.getClass().getSimpleName() + ")");
                log.info("[Step] '{}' failed as expected (expect_failure=true): {}",
                        stepConfig.getId(), t.toString());
                return StepResult.success(stepConfig, durationMs,
                        withExpectedFailureMark(snapshotOutputs(stepConfig.getId(), ctx)));
            }
            return StepResult.failure(stepConfig, t, durationMs,
                    snapshotOutputs(stepConfig.getId(), ctx));
        }
    }

    /** Mark a tolerated (expect_failure) result in its output snapshot so reports show why it passed. */
    private static Map<String, Object> withExpectedFailureMark(Map<String, Object> outputs) {
        Map<String, Object> marked = new LinkedHashMap<>(outputs);
        marked.put("expected_failure", true);
        return marked;
    }

    /** 快照本 Step 注册到 ScenarioContext 的 outputs —— 供报告采集指标使用。 */
    private static Map<String, Object> snapshotOutputs(String stepId, ScenarioContext ctx) {
        if (stepId == null) return Map.of();
        Map<String, Object> outputs = ctx.getSteps().get(stepId);
        return outputs == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }
}
