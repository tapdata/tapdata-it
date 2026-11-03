package io.tapdata.it.l3.core.runner;

import io.tapdata.it.l3.common.ParallelBlock;
import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.SafeSpelEvaluator;
import io.tapdata.it.l3.common.SafeSpelEvaluator.SpelNullReferenceException;
import io.tapdata.it.l3.common.exception.StepTimeoutException;
import io.tapdata.it.l3.core.cleanup.CleanupManager;
import io.tapdata.it.l3.core.plugin.PluginCenter;
import io.tapdata.it.l3.core.plugin.PluginCenter.PluginLoadException;
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.plugin.ActionHandler;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Drives scenario execution: iterates over parsed blocks (sequential steps + parallel blocks),
 * delegates each step to {@link StepRunner}, and handles cleanly the finally block for resource teardown.
 * <p>
 * Exception handling is classified so that each failure type produces the right diagnostics:
 * <ul>
 *   <li>{@link StepTimeoutException} → timeout panel (future orTimeout)</li>
 *   <li>{@link SpelNullReferenceException} → SpEL panel (null-safety pre-check)</li>
 *   <li>Other exceptions → generic failure panel</li>
 * </ul>
 * C2 parallel execution: uses CompletableFuture + allOf for concurrent step blocks.
 */
@Component
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);
    private static final int PARALLEL_POOL_SIZE = Math.min(Runtime.getRuntime().availableProcessors(), 8);

    private final StepRunner stepRunner;
    private final CleanupManager cleanupManager;
    private final PluginCenter pluginCenter;
    private final ExecutorService parallelExecutor;

    public ScenarioRunner(StepRunner stepRunner, CleanupManager cleanupManager, PluginCenter pluginCenter) {
        this.stepRunner = stepRunner;
        this.cleanupManager = cleanupManager;
        this.pluginCenter = pluginCenter;
        this.parallelExecutor = new ThreadPoolExecutor(
                PARALLEL_POOL_SIZE, PARALLEL_POOL_SIZE,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "l3-parallel");
                    t.setDaemon(true);
                    return t;
                });
    }

    // ==================== Public API ====================

    public ScenarioResult execute(ScenarioConfig scenario) {
        Instant startTime = Instant.now();
        log.info("================================================");
        log.info("[Scenario] START '{}' (case_id={}, group={})",
                scenario.getScenario(), scenario.getCaseId(), scenario.getGroup());
        log.info("================================================");

        ScenarioContext ctx = new ScenarioContext();
        // Merge user-declared variables (override built-ins if same key)
        if (scenario.getVariables() != null) {
            scenario.getVariables().forEach(ctx::setVariable);
        }

        // 本次 scenario 所有 step 用过的 handler 独立实例 —— 执行完统一 destroy
        // 用 CopyOnWriteArrayList 是因为 parallel block 里的多线程会并发 add
        List<ActionHandler> usedHandlers = new CopyOnWriteArrayList<>();

        // ============ Preflight: 依赖预加载 + 并发校验 ============
        List<StepConfig> allSteps = collectAllSteps(scenario);
        if (!allSteps.isEmpty()) {
            // Stage 1: 统一下载所有依赖（提前暴露网络/下载问题）
            try {
                pluginCenter.preloadDependencies(allSteps);
            } catch (PluginCenter.PluginLoadException ple) {
                log.error("[Scenario] ❌ Preflight FAILED — dependency preload: {}", ple.getMessage());
                cleanupManager.triggerTeardown();
                pluginCenter.destroyAllHandlers(usedHandlers);
                return ScenarioResult.failed(scenario,
                        StepResult.failure(new StepConfig(), ple, 0),
                        Duration.between(startTime, Instant.now()).toMillis(), startTime, Instant.now());
            }

            // Stage 2: 并发执行所有 handler.validate() + 框架级校验
            PreflightReport report = runPreflightValidation(allSteps);
            if (!report.pass()) {
                log.error("[Scenario] ❌ Preflight FAILED — {} error(s):", report.totalErrors());
                report.printReport(log::error);
                cleanupManager.triggerTeardown();
                pluginCenter.destroyAllHandlers(usedHandlers);
                long failDurationMs = Duration.between(startTime, Instant.now()).toMillis();
                return ScenarioResult.failed(scenario,
                        StepResult.failure(new StepConfig(),
                                new IllegalStateException("Preflight failed: " + report.totalErrors() + " error(s) — fix configuration and retry"),
                                0),
                        failDurationMs, startTime, Instant.now());
            }
            log.info("[Scenario] ✅ Preflight passed — {} step(s) ready to execute", allSteps.size());
        } else {
            log.warn("[Scenario] No steps found in scenario — will exit immediately");
        }

        StepResult failure = null;
        long durationMs = 0;

        try {
            int totalSteps = countSteps(scenario);
            int stepIndex = 0;

            for (Object block : scenario.getBlocks()) {
                if (block instanceof StepConfig step) {
                    stepIndex++;
                    StepResult result = runWithProgress(step, stepIndex, totalSteps, ctx, usedHandlers);
                    if (result.isSuccess()) continue;
                    failure = result;
                    break;
                } else if (block instanceof ParallelBlock parallel) {
                    stepIndex++; // treat whole parallel block as one progress tick
                    StepResult result = runParallelBlock(parallel, stepIndex, totalSteps, ctx, usedHandlers);
                    if (result != null && !result.isSuccess()) {
                        failure = result;
                        break;
                    }
                } else {
                    log.warn("[Scenario] Unknown block type: {}", block.getClass());
                }
            }
        } catch (StepTimeoutException te) {
            log.error("[Scenario] Step '{}' timed out after {} seconds", te.getStepId(), te.getTimeoutSeconds(), te);
            durationMs = Duration.between(startTime, Instant.now()).toMillis();
            cleanupManager.triggerTeardown();
            return ScenarioResult.failed(scenario,
                    StepResult.failure(StepConfig.builder().id(te.getStepId()).build(), te, 0),
                    durationMs, startTime, Instant.now());
        } catch (SpelNullReferenceException sre) {
            log.error("[Scenario] SpEL null-safety failure: {}", sre.getMessage());
            durationMs = Duration.between(startTime, Instant.now()).toMillis();
            cleanupManager.triggerTeardown();
            return ScenarioResult.failed(scenario,
                    StepResult.failure(new StepConfig(), sre, 0),
                    durationMs, startTime, Instant.now());
        } catch (Exception unexpected) {
            log.error("[Scenario] Unexpected failure: {}", unexpected.getMessage(), unexpected);
            durationMs = Duration.between(startTime, Instant.now()).toMillis();
            cleanupManager.triggerTeardown();
            return ScenarioResult.failed(scenario,
                    StepResult.failure(new StepConfig(), unexpected, 0),
                    durationMs, startTime, Instant.now());
        } finally {
            // 1. ScenarioContext 里的 cleanup hooks（ctx.registerCleanup 注册的）
            cleanupManager.triggerTeardown();
            // 2. 所有已注册 ActionHandler 的 destroy() —— 整个 scenario 结束时的资源清理
            //    这里 destroy 的是本次 scenario 执行过的所有**独立** handler 实例
            pluginCenter.destroyAllHandlers(usedHandlers);
            durationMs = Duration.between(startTime, Instant.now()).toMillis();
        }

        Instant endTime = Instant.now();
        if (failure != null) {
            log.error("[Scenario] FAILED '{}' — step '{}' ({}) failed after {}ms",
                    scenario.getScenario(), failure.getStepConfig().getId(),
                    failure.getStepConfig().getAction(), durationMs);
            return ScenarioResult.failed(scenario, failure, durationMs, startTime, endTime);
        }

        log.info("[Scenario] PASSED '{}' in {}ms", scenario.getScenario(), durationMs);
        return ScenarioResult.success(scenario, durationMs, startTime, endTime);
    }

    // ==================== Sequential step execution ====================

    private StepResult runWithProgress(StepConfig step, int index, int total, ScenarioContext ctx,
                                       List<ActionHandler> usedHandlers) {
        log.info("[Step {}/{}] {} → {} ...", index, total, step.getId(), step.getAction());
        StepResult result = stepRunner.run(step, ctx, usedHandlers);
        if (!result.isSuccess()) {
            log.error("[Step {}/{}] {} → {} ... [FAILED] ({}ms)",
                    index, total, step.getId(), step.getAction(), result.getDurationMs());
        } else {
            log.info("[Step {}/{}] {} → {} ... [SUCCESS] ({}ms)",
                    index, total, step.getId(), step.getAction(), result.getDurationMs());
        }
        return result;
    }

    // ==================== Parallel block execution ====================

    private StepResult runParallelBlock(ParallelBlock block, int index, int total, ScenarioContext ctx,
                                        List<ActionHandler> usedHandlers) {
        List<CompletableFuture<StepResult>> futures = new ArrayList<>();

        for (StepConfig step : block.getSteps()) {
            CompletableFuture<StepResult> future = CompletableFuture.supplyAsync(
                    () -> stepRunner.run(step, ctx, usedHandlers), parallelExecutor);
            futures.add(future);
        }

        try {
            List<StepResult> results = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream().map(CompletableFuture::join).toList())
                    .get();

            for (StepResult r : results) {
                if (!r.isSuccess()) return r;
            }
            log.info("[Step {}/{}] parallel({} steps) ... [SUCCESS]", index, total, block.getSteps().size());
            return null; // all success
        } catch (CompletionException | ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StepTimeoutException te) throw te;
            if (cause instanceof SpelNullReferenceException sre) throw sre;
            throw new CompletionException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ie);
        }
    }

    // ==================== Utility ====================

    private int countSteps(ScenarioConfig scenario) {
        int n = 0;
        for (Object block : scenario.getBlocks()) {
            if (block instanceof StepConfig) n++;
            else if (block instanceof ParallelBlock p) n += p.getSteps().size();
        }
        return n;
    }

    // ==================== Preflight Helpers ====================

    /** 从 scenario 所有 blocks（包括 parallel 内部）收集扁平的 StepConfig 列表。 */
    static List<StepConfig> collectAllSteps(ScenarioConfig scenario) {
        List<StepConfig> result = new ArrayList<>();
        for (Object block : scenario.getBlocks()) {
            if (block instanceof StepConfig step) result.add(step);
            else if (block instanceof ParallelBlock p) result.addAll(p.getSteps());
        }
        return result;
    }

    /**
     * 并发执行所有 step 的 preflight 校验 —— handler.validate() + 框架级类型检查。
     * 所有校验结果被收集，最后一次性报告。
     */
    private PreflightReport runPreflightValidation(List<StepConfig> allSteps) {
        List<CompletableFuture<StepValidation>> futures = new ArrayList<>();
        for (StepConfig step : allSteps) {
            futures.add(CompletableFuture.supplyAsync(() -> validateOneStep(step), parallelExecutor));
        }

        List<StepValidation> validations;
        try {
            validations = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream().map(CompletableFuture::join).toList())
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[Scenario] Preflight validation timed out or failed to collect results: {}", e.getMessage());
            return PreflightReport.timeout("Preflight validation timed out (60s) — a handler.validate() may be stuck");
        }

        return new PreflightReport(validations);
    }

    /** 单个 step 的完整 preflight 校验 —— 框架级 + handler 级。 */
    private StepValidation validateOneStep(StepConfig step) {
        List<String> errors = new ArrayList<>();

        // 1. 框架级校验: action 存在性 + timeout_seconds 类型 + inputs 类型
        //    （DSL 语法校验 DslParser 已做过，这里做运行时层面的校验）
        if (!pluginCenter.hasAction(step.getAction())
                && !pluginCenter.isInDefaultRegistry(step.getAction())
                && (step.getUse() == null || step.getUse().isBlank())) {
            errors.add("[E-action] action '" + step.getAction()
                    + "' 既不在内置 steps 也不在默认注册表，且 DSL 未指定 use 坐标 —— "
                    + "请检查 action 拼写、通过 steps-registry.yaml 注册，或在 DSL 加 use: groupId:artifactId:version");
        }

        Object timeoutRaw = null;  // timeout_seconds 的类型在 parseStep 里已处理为 int，但这里 double check inputs
        Map<String, Object> inputs = step.getInputs();
        if (inputs != null) {
            for (Map.Entry<String, Object> e : inputs.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (!(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean)
                        && !(v instanceof Map) && !(v instanceof List)) {
                    errors.add("[E-type] inputs." + e.getKey() + " 类型不合法: expected String/Number/Boolean/Map/List, got "
                            + v.getClass().getSimpleName() + " (" + v + ")");
                }
            }
        }

        // 2. handler 级校验: 调 ActionHandler.validate(StepConfig)
        try {
            ActionHandler handler = pluginCenter.getAction(step.getAction());
            List<String> handlerErrors = handler.validate(step);
            if (handlerErrors != null && !handlerErrors.isEmpty()) {
                errors.addAll(handlerErrors);
            }
        } catch (IllegalArgumentException iae) {
            // action 不在 registry —— 已经在上面 action 存在性校验里覆盖了，跳过
        } catch (Exception ex) {
            errors.add("[E-handler] handler.validate() 抛异常: " + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage());
        }

        return new StepValidation(step.getId(), step.getAction(), errors);
    }

    /** 单个 step 的校验结果（纯数据）。 */
    record StepValidation(String stepId, String action, List<String> errors) {
        boolean passed() { return errors.isEmpty(); }
    }

    /** 全场景 preflight 汇总报告。 */
    static class PreflightReport {
        final List<StepValidation> validations;
        final int totalErrors;
        final String globalMessage;  // 用于 timeout 等全局失败场景

        PreflightReport(List<StepValidation> validations) {
            this.validations = validations;
            this.globalMessage = null;
            int n = 0;
            for (StepValidation v : validations) n += v.errors.size();
            this.totalErrors = n;
        }

        PreflightReport(String globalMessage) {
            this.validations = List.of();
            this.totalErrors = 1;
            this.globalMessage = globalMessage;
        }

        static PreflightReport timeout(String msg) { return new PreflightReport(msg); }

        boolean pass() {
            if (globalMessage != null) return false;
            return totalErrors == 0;
        }

        int totalErrors() { return totalErrors; }

        /** 把 report 格式化到给定的 Consumer（通常是 log.error 或 System.err::println）。 */
        void printReport(Consumer<String> out) {
            if (globalMessage != null) {
                out.accept("  ❌ " + globalMessage);
                return;
            }

            out.accept("  [Preflight] " + validations.size() + " step(s) → " + totalErrors + " error(s) total:");
            for (StepValidation v : validations) {
                if (v.passed()) continue;
                out.accept("    ❌ step '" + v.stepId() + "' (" + v.action() + ") — " + v.errors.size() + " 条错误:");
                for (int i = 0; i < v.errors.size(); i++) {
                    out.accept("        [E" + (i + 1) + "] " + v.errors.get(i));
                }
            }
            out.accept("  Fix the configuration issues above and retry. Steps that passed preflight were NOT executed.");
        }
    }
}
