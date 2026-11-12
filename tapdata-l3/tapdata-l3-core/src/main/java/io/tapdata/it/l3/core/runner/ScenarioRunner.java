package io.tapdata.it.l3.core.runner;

import io.tapdata.it.l3.common.ForBlock;
import io.tapdata.it.l3.common.IfBlock;
import io.tapdata.it.l3.common.ParallelBlock;
import io.tapdata.it.l3.common.ScenarioBlocks;
import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.SafeSpelEvaluator;
import io.tapdata.it.l3.common.SwitchBlock;
import io.tapdata.it.l3.common.WhileBlock;
import io.tapdata.it.l3.common.SafeSpelEvaluator.SpelNullReferenceException;
import io.tapdata.it.l3.common.exception.StepTimeoutException;
import io.tapdata.it.l3.core.cleanup.CleanupManager;
import io.tapdata.it.l3.core.console.ConsoleUI;
import io.tapdata.it.l3.core.plugin.PluginCenter;
import io.tapdata.it.l3.core.plugin.PluginCenter.PluginLoadException;
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.plugin.ActionHandler;
import io.tapdata.it.l3.plugin.step.StepResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final long HEARTBEAT_SECONDS = 30;

    private final StepRunner stepRunner;
    private final CleanupManager cleanupManager;
    private final PluginCenter pluginCenter;
    private final ExecutorService parallelExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

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
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "l3-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        parallelExecutor.shutdownNow();
        heartbeatScheduler.shutdownNow();
    }

    // ==================== Public API ====================

    public ScenarioResult execute(ScenarioConfig scenario) {
        Instant startTime = Instant.now();
        log.debug("[Scenario] START '{}' (case_id={}, group={})",
                scenario.getScenario(), scenario.getCaseId(), scenario.getGroup());

        ScenarioContext ctx = new ScenarioContext();

        // ============ Scenario-scoped env injection ============
        // 注入 3 处，方便不同访问模式:
        //  1. System.setProperty → ActionHandler 实现里 System.getProperty("TM_BASE_URL") 直接拿
        //  2. ctx.setVariable(k, v) → SpEL 里 #{ #variables['TM_BASE_URL'] }
        //  3. #env['TM_BASE_URL'] → SpEL context variable（ctx.setEnv → evaluator.registerEnv）
        // 注意：单 JVM 批量跑多个场景时 System properties 是 JVM 全局的 —— 场景结束后必须恢复，
        // 否则前一个场景的 env（如 HA 场景的 TM_BASE_URL=...193）会泄漏进后续场景。
        Map<String, String> envMap = scenario.getEnv() == null ? Map.of() : scenario.getEnv();
        Map<String, String> savedSystemProps = new java.util.HashMap<>();
        if (!envMap.isEmpty()) {
            ConsoleUI.event("env", envMap.size() + " variable(s) loaded");
            envMap.forEach((k, v) -> {
                savedSystemProps.put(k, System.getProperty(k)); // null = 原本没有 → finally 里 clearProperty
                System.setProperty(k, v); // 本场景 env 永远生效；JVM 启动 -D 的值在场景结束后恢复
                ctx.setVariable(k, v);
            });
        }
        // 始终注册 env（即使为空 map）→ #env['KEY'] 缺 key 时返回 null 而非 EL1007E
        ctx.setEnv(envMap);

        // Merge user-declared variables (override env + built-ins if same key — variables 是场景私有临时变量)
        if (scenario.getVariables() != null) {
            scenario.getVariables().forEach(ctx::setVariable);
        }

        // 本次 scenario 所有 step 用过的 handler 独立实例 —— 执行完统一 destroy
        // 用 CopyOnWriteArrayList 是因为 parallel block 里的多线程会并发 add
        List<ActionHandler> usedHandlers = new CopyOnWriteArrayList<>();

        // 所有已执行 Step 的结果 —— 供三格式测试报告采集耗时与指标
        List<StepResult> stepResults = new CopyOnWriteArrayList<>();

        // ============ Preflight: 依赖预加载 + 并发校验 ============
        List<StepConfig> allSteps = collectAllSteps(scenario);
        if (!allSteps.isEmpty()) {
            // Stage 1: 统一下载所有依赖（提前暴露网络/下载问题）
            ConsoleUI.event("deps", "preloading " + allSteps.size() + " step(s)...");
            long preloadStart = System.currentTimeMillis();
            try {
                pluginCenter.preloadDependencies(allSteps);
            } catch (PluginCenter.PluginLoadException ple) {
                ConsoleUI.error("dependency preload failed: " + ple.getMessage());
                log.error("[Scenario] Preflight FAILED — dependency preload: {}", ple.getMessage());
                cleanupManager.triggerTeardown();
                pluginCenter.destroyAllHandlers(usedHandlers);
                return ScenarioResult.failed(scenario,
                        StepResult.failure(new StepConfig(), ple, 0),
                        Duration.between(startTime, Instant.now()).toMillis(), startTime, Instant.now(),
                        stepResults);
            }
            ConsoleUI.event("deps", allSteps.size() + " step(s) ready ("
                    + ConsoleUI.formatDuration(System.currentTimeMillis() - preloadStart) + ")");

            // Stage 2: 并发执行所有 handler.validate() + 框架级校验
            long preflightStart = System.currentTimeMillis();
            PreflightReport report = runPreflightValidation(allSteps);
            if (!report.pass()) {
                ConsoleUI.error("preflight failed: " + report.totalErrors() + " error(s)");
                report.printReport(ConsoleUI::detail);
                log.error("[Scenario] Preflight FAILED — {} error(s):", report.totalErrors());
                cleanupManager.triggerTeardown();
                pluginCenter.destroyAllHandlers(usedHandlers);
                long failDurationMs = Duration.between(startTime, Instant.now()).toMillis();
                return ScenarioResult.failed(scenario,
                        StepResult.failure(new StepConfig(),
                                new IllegalStateException("Preflight failed: " + report.totalErrors() + " error(s) — fix configuration and retry"),
                                0),
                        failDurationMs, startTime, Instant.now(), stepResults);
            }
            ConsoleUI.event("preflight", allSteps.size() + " step(s) OK ("
                    + ConsoleUI.formatDuration(System.currentTimeMillis() - preflightStart) + ")");
        } else {
            ConsoleUI.warn("scenario has no steps — exiting immediately");
        }

        StepResult failure = null;
        long durationMs = 0;

        try {
            ExecutionTracker tracker = new ExecutionTracker(ScenarioBlocks.countSteps(scenario.getBlocks()));
            failure = executeBlocks(scenario.getBlocks(), ctx, usedHandlers, stepResults, tracker);
        } catch (StepTimeoutException te) {
            log.error("[Scenario] Step '{}' timed out after {} seconds", te.getStepId(), te.getTimeoutSeconds(), te);
            durationMs = Duration.between(startTime, Instant.now()).toMillis();
            cleanupManager.triggerTeardown();
            StepResult timeoutResult = StepResult.failure(
                    StepConfig.builder().id(te.getStepId()).build(), te, 0);
            stepResults.add(timeoutResult);
            return ScenarioResult.failed(scenario, timeoutResult,
                    durationMs, startTime, Instant.now(), stepResults);
        } catch (SpelNullReferenceException sre) {
            log.error("[Scenario] SpEL null-safety failure: {}", sre.getMessage());
            durationMs = Duration.between(startTime, Instant.now()).toMillis();
            cleanupManager.triggerTeardown();
            StepResult spelResult = StepResult.failure(new StepConfig(), sre, 0);
            stepResults.add(spelResult);
            return ScenarioResult.failed(scenario, spelResult,
                    durationMs, startTime, Instant.now(), stepResults);
        } catch (Exception unexpected) {
            log.error("[Scenario] Unexpected failure: {}", unexpected.getMessage(), unexpected);
            durationMs = Duration.between(startTime, Instant.now()).toMillis();
            cleanupManager.triggerTeardown();
            StepResult unexpectedResult = StepResult.failure(new StepConfig(), unexpected, 0);
            stepResults.add(unexpectedResult);
            return ScenarioResult.failed(scenario, unexpectedResult,
                    durationMs, startTime, Instant.now(), stepResults);
        } finally {
            // 0. 恢复本场景覆盖过的 system properties —— 单 JVM 批跑跨场景隔离的关键
            savedSystemProps.forEach((k, prev) -> {
                if (prev == null) {
                    System.clearProperty(k);
                } else {
                    System.setProperty(k, prev);
                }
            });
            // 1. ScenarioContext 里的 cleanup hooks（ctx.registerCleanup 注册的）
            transferContextCleanups(ctx);
            cleanupManager.triggerTeardown();
            // 2. 所有已注册 ActionHandler 的 destroy() —— 整个 scenario 结束时的资源清理
            //    这里 destroy 的是本次 scenario 执行过的所有**独立** handler 实例
            pluginCenter.destroyAllHandlers(usedHandlers);
            durationMs = Duration.between(startTime, Instant.now()).toMillis();
        }

        Instant endTime = Instant.now();
        if (failure != null) {
            log.debug("[Scenario] FAILED '{}' — step '{}' ({}) failed after {}ms",
                    scenario.getScenario(),
                    failure.getStepConfig() == null ? "?" : failure.getStepConfig().getId(),
                    failure.getStepConfig() == null ? "?" : failure.getStepConfig().getAction(),
                    durationMs);
            return ScenarioResult.failed(scenario, failure, durationMs, startTime, endTime, stepResults);
        }

        log.debug("[Scenario] PASSED '{}' in {}ms", scenario.getScenario(), durationMs);
        return ScenarioResult.success(scenario, durationMs, startTime, endTime, stepResults);
    }

    /**
     * Moves ScenarioContext cleanup hooks into the CleanupManager.
     * {@code removeLast()} walks bottom → top so the manager's LIFO stack
     * still runs hooks in reverse registration order.
     */
    private void transferContextCleanups(ScenarioContext ctx) {
        Deque<ScenarioContext.CleanupEntry> hooks = ctx.getCleanupStack();
        while (!hooks.isEmpty()) {
            cleanupManager.register(hooks.removeLast().action());
        }
    }

    // ==================== Sequential step execution ====================

    private StepResult runWithProgress(StepConfig step, int index, int total, ScenarioContext ctx,
                                       List<ActionHandler> usedHandlers) {
        ConsoleUI.stepStart(index, total, step);
        long startedAt = System.currentTimeMillis();
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> ConsoleUI.heartbeat(index, total, step.getId(),
                        System.currentTimeMillis() - startedAt),
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        StepResult result;
        try {
            result = stepRunner.run(step, ctx, usedHandlers);
        } finally {
            heartbeat.cancel(false);
        }
        ConsoleUI.stepEnd(index, total, result);
        return result;
    }

    // ==================== Parallel block execution ====================

    private StepResult runParallelBlock(ParallelBlock block, int index, int total, ScenarioContext ctx,
                                        List<ActionHandler> usedHandlers,
                                        List<StepResult> collectedResults) {
        int stepCount = block.getSteps().size();
        boolean singleStep = stepCount == 1;
        if (singleStep) {
            ConsoleUI.stepStart(index, total, block.getSteps().get(0));
        } else {
            ConsoleUI.parallelStart(index, total, stepCount);
        }
        String heartbeatLabel = singleStep
                ? block.getSteps().get(0).getId()
                : "parallel block (" + stepCount + " steps)";
        long startedAt = System.currentTimeMillis();
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> ConsoleUI.heartbeat(index, total, heartbeatLabel,
                        System.currentTimeMillis() - startedAt),
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

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

            collectedResults.addAll(results);
            for (int i = 0; i < results.size(); i++) {
                ConsoleUI.stepEnd(index + i, total, results.get(i));
            }
            for (StepResult r : results) {
                if (!r.isSuccess()) return r;
            }
            return null; // all success
        } catch (CompletionException | ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StepTimeoutException te) throw te;
            if (cause instanceof SpelNullReferenceException sre) throw sre;
            throw new CompletionException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ie);
        } finally {
            heartbeat.cancel(false);
        }
    }

    // ==================== Block tree execution (if / for / switch) ====================

    /** Executes a list of blocks sequentially; returns the first failure or {@code null}. */
    private StepResult executeBlocks(List<Object> blocks, ScenarioContext ctx,
                                     List<ActionHandler> usedHandlers, List<StepResult> stepResults,
                                     ExecutionTracker tracker) {
        if (blocks == null) return null;
        for (Object block : blocks) {
            StepResult failure = executeBlock(block, ctx, usedHandlers, stepResults, tracker);
            if (failure != null) return failure;
        }
        return null;
    }

    private StepResult executeBlock(Object block, ScenarioContext ctx,
                                    List<ActionHandler> usedHandlers, List<StepResult> stepResults,
                                    ExecutionTracker tracker) {
        if (block instanceof StepConfig step) {
            tracker.index++;
            StepResult result = runWithProgress(step, tracker.index, tracker.total, ctx, usedHandlers);
            stepResults.add(result);
            return result.isSuccess() ? null : result;
        }
        if (block instanceof ParallelBlock parallel) {
            int startIndex = tracker.index + 1;
            tracker.index += parallel.getSteps().size();
            StepResult result = runParallelBlock(parallel, startIndex, tracker.total, ctx,
                    usedHandlers, stepResults);
            return result != null && !result.isSuccess() ? result : null;
        }
        if (block instanceof IfBlock ifBlock) {
            return executeIfBlock(ifBlock, ctx, usedHandlers, stepResults, tracker);
        }
        if (block instanceof ForBlock forBlock) {
            return executeForBlock(forBlock, ctx, usedHandlers, stepResults, tracker);
        }
        if (block instanceof WhileBlock whileBlock) {
            return executeWhileBlock(whileBlock, ctx, usedHandlers, stepResults, tracker);
        }
        if (block instanceof SwitchBlock switchBlock) {
            return executeSwitchBlock(switchBlock, ctx, usedHandlers, stepResults, tracker);
        }
        log.warn("[Scenario] Unknown block type: {}", block == null ? "null" : block.getClass());
        return null;
    }

    private StepResult executeIfBlock(IfBlock block, ScenarioContext ctx,
                                      List<ActionHandler> usedHandlers, List<StepResult> stepResults,
                                      ExecutionTracker tracker) {
        boolean condition = toBoolean(ctx.evaluateValue(block.getCondition()));
        List<Object> thenBlocks = block.getThenBlocks();
        List<Object> elseBlocks = block.getElseBlocks();
        List<Object> chosen = condition ? thenBlocks : elseBlocks;
        List<Object> other = condition ? elseBlocks : thenBlocks;

        int chosenCount = ScenarioBlocks.countSteps(chosen);
        adjustTotal(tracker, Math.max(chosenCount, ScenarioBlocks.countSteps(other)), chosenCount);
        ConsoleUI.event("if", (condition ? "true" : "false")
                + " → " + (condition ? "then" : "else")
                + " (" + chosenCount + " step(s))");
        return executeBlocks(chosen, ctx, usedHandlers, stepResults, tracker);
    }

    private StepResult executeForBlock(ForBlock block, ScenarioContext ctx,
                                       List<ActionHandler> usedHandlers, List<StepResult> stepResults,
                                       ExecutionTracker tracker) {
        List<Object> items = resolveItems(block.getItems(), block.getVar(), ctx);
        int perIteration = ScenarioBlocks.countSteps(block.getBlocks());
        adjustTotal(tracker, perIteration, perIteration * items.size());
        ConsoleUI.event("for", block.getVar() + " × " + items.size() + " item(s)");

        String var = block.getVar();
        String indexVar = block.getIndexVar();
        Object previousValue = ctx.getVariable(var);
        boolean hadValue = ctx.hasVariable(var);
        Object previousIndex = indexVar == null ? null : ctx.getVariable(indexVar);
        boolean hadIndex = indexVar != null && ctx.hasVariable(indexVar);

        try {
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                Map<String, String> templates = new LinkedHashMap<>();
                templates.put(var, String.valueOf(item));
                ctx.setVariable(var, item);
                if (indexVar != null) {
                    templates.put(indexVar, String.valueOf(i));
                    ctx.setVariable(indexVar, i);
                }

                List<Object> resolved = resolveTemplates(block.getBlocks(), templates);
                StepResult failure = executeBlocks(resolved, ctx, usedHandlers, stepResults, tracker);
                if (failure != null) return failure;
            }
            return null;
        } finally {
            restoreVariable(ctx, var, hadValue, previousValue);
            if (indexVar != null) {
                restoreVariable(ctx, indexVar, hadIndex, previousIndex);
            }
        }
    }

    private StepResult executeWhileBlock(WhileBlock block, ScenarioContext ctx,
                                         List<ActionHandler> usedHandlers, List<StepResult> stepResults,
                                         ExecutionTracker tracker) {
        int maxIterations = block.getMaxIterations() > 0
                ? block.getMaxIterations()
                : WhileBlock.DEFAULT_MAX_ITERATIONS;
        int perIteration = ScenarioBlocks.countSteps(block.getBlocks());
        ConsoleUI.event("while", "max " + maxIterations + " iteration(s)");

        String indexVar = block.getIndexVar();
        Object previousIndex = indexVar == null ? null : ctx.getVariable(indexVar);
        boolean hadIndex = indexVar != null && ctx.hasVariable(indexVar);

        try {
            int i = 0;
            while (true) {
                if (i >= maxIterations) {
                    throw new IllegalStateException("while loop exceeded max_iterations="
                            + maxIterations + " (condition=" + block.getCondition() + ")");
                }
                if (indexVar != null) {
                    ctx.setVariable(indexVar, i);
                }
                if (!toBoolean(ctx.evaluateValue(block.getCondition()))) {
                    return null;
                }
                if (i > 0) {
                    // 初始估算只包含 1 次迭代，每多执行一轮补充一次进度
                    tracker.total += perIteration;
                }
                Map<String, String> templates = indexVar == null
                        ? Map.of()
                        : Map.of(indexVar, String.valueOf(i));
                List<Object> resolved = resolveTemplates(block.getBlocks(), templates);
                StepResult failure = executeBlocks(resolved, ctx, usedHandlers, stepResults, tracker);
                if (failure != null) return failure;
                i++;
            }
        } finally {
            if (indexVar != null) {
                restoreVariable(ctx, indexVar, hadIndex, previousIndex);
            }
        }
    }

    private StepResult executeSwitchBlock(SwitchBlock block, ScenarioContext ctx,
                                          List<ActionHandler> usedHandlers, List<StepResult> stepResults,
                                          ExecutionTracker tracker) {
        Object value = ctx.evaluateValue(block.getExpression());
        String key = value == null ? "null" : String.valueOf(value);
        List<Object> chosen = block.getCases().get(key);
        String label = chosen != null
                ? key
                : (block.getDefaultBlocks() == null || block.getDefaultBlocks().isEmpty() ? "(no match)" : "default");
        if (chosen == null) {
            chosen = block.getDefaultBlocks() == null ? List.of() : block.getDefaultBlocks();
        }

        int estimated = ScenarioBlocks.countSteps(block.getDefaultBlocks());
        for (List<Object> caseBlocks : block.getCases().values()) {
            estimated = Math.max(estimated, ScenarioBlocks.countSteps(caseBlocks));
        }
        adjustTotal(tracker, estimated, ScenarioBlocks.countSteps(chosen));

        ConsoleUI.event("switch", key + " → " + label);
        return executeBlocks(chosen, ctx, usedHandlers, stepResults, tracker);
    }

    /** Resolves {@code for.items} — literal list or SpEL expression returning a collection. */
    private List<Object> resolveItems(Object raw, String varName, ScenarioContext ctx) {
        Object evaluated = ctx.evaluateValue(raw);
        if (evaluated == null) {
            throw new IllegalStateException("for.items evaluated to null (var=" + varName + ")");
        }
        if (evaluated instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (evaluated instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (evaluated instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(result::add);
            return result;
        }
        throw new IllegalStateException("for.items must evaluate to a list/collection, got "
                + evaluated.getClass().getName() + " (var=" + varName + ")");
    }

    /** Coerces a condition value to boolean — Boolean / Number / collection / string literal. */
    private static boolean toBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0d;
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return false;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "false", "no", "n", "0", "off" -> false;
            case "true", "yes", "y", "1", "on" -> true;
            default -> true;
        };
    }

    private static void adjustTotal(ExecutionTracker tracker, int estimated, int actual) {
        tracker.total += actual - estimated;
        if (tracker.total < tracker.index) {
            tracker.total = tracker.index;
        }
    }

    private static void restoreVariable(ScenarioContext ctx, String name, boolean existed, Object previous) {
        if (existed) {
            ctx.setVariable(name, previous);
        } else {
            ctx.removeVariable(name);
        }
    }

    // ==================== Template substitution ====================

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)}");

    /**
     * Deep-copies a block tree, replacing {@code ${var}} placeholders in step ids,
     * inputs, outputs and hooks. Used to generate per-iteration step ids inside {@code for}.
     */
    private List<Object> resolveTemplates(List<Object> blocks, Map<String, String> variables) {
        if (blocks == null) return List.of();
        List<Object> resolved = new ArrayList<>(blocks.size());
        for (Object block : blocks) {
            resolved.add(resolveTemplateBlock(block, variables));
        }
        return resolved;
    }

    private Object resolveTemplateBlock(Object block, Map<String, String> variables) {
        if (block instanceof StepConfig step) {
            return copyStep(step, variables);
        }
        if (block instanceof ParallelBlock parallel) {
            List<StepConfig> steps = new ArrayList<>(parallel.getSteps().size());
            for (StepConfig step : parallel.getSteps()) {
                steps.add(copyStep(step, variables));
            }
            return ParallelBlock.builder().steps(steps).build();
        }
        if (block instanceof IfBlock ifBlock) {
            return IfBlock.builder()
                    .condition(substituteValue(ifBlock.getCondition(), variables))
                    .thenBlocks(resolveTemplates(ifBlock.getThenBlocks(), variables))
                    .elseBlocks(resolveTemplates(ifBlock.getElseBlocks(), variables))
                    .build();
        }
        if (block instanceof ForBlock forBlock) {
            return ForBlock.builder()
                    .var(forBlock.getVar())
                    .indexVar(forBlock.getIndexVar())
                    .items(substituteValue(forBlock.getItems(), variables))
                    .blocks(resolveTemplates(forBlock.getBlocks(), variables))
                    .build();
        }
        if (block instanceof WhileBlock whileBlock) {
            return WhileBlock.builder()
                    .condition(substituteValue(whileBlock.getCondition(), variables))
                    .indexVar(whileBlock.getIndexVar())
                    .maxIterations(whileBlock.getMaxIterations())
                    .blocks(resolveTemplates(whileBlock.getBlocks(), variables))
                    .build();
        }
        if (block instanceof SwitchBlock switchBlock) {
            Map<String, List<Object>> cases = new LinkedHashMap<>();
            switchBlock.getCases().forEach((key, caseBlocks) ->
                    cases.put(key, resolveTemplates(caseBlocks, variables)));
            return SwitchBlock.builder()
                    .expression(substituteValue(switchBlock.getExpression(), variables))
                    .cases(cases)
                    .defaultBlocks(resolveTemplates(switchBlock.getDefaultBlocks(), variables))
                    .build();
        }
        return block;
    }

    @SuppressWarnings("unchecked")
    private StepConfig copyStep(StepConfig step, Map<String, String> variables) {
        return StepConfig.builder()
                .id(substituteString(step.getId(), variables))
                .action(step.getAction())
                .inputs((Map<String, Object>) substituteValue(step.getInputs(), variables))
                .outputs((List<String>) substituteValue(step.getOutputs(), variables))
                .timeoutSeconds(step.getTimeoutSeconds())
                .keepAlive(step.isKeepAlive())
                .expectFailure(step.isExpectFailure())
                .expectFailureMessage(step.getExpectFailureMessage())
                .hooks((Map<String, Object>) substituteValue(step.getHooks(), variables))
                .extraDeps(step.getExtraDeps())
                .use(step.getUse())
                .stepClassLoader(step.getStepClassLoader())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Object substituteValue(Object value, Map<String, String> variables) {
        if (value == null) return null;
        if (value instanceof String s) return substituteString(s, variables);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), substituteValue(entry.getValue(), variables));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(substituteValue(item, variables));
            }
            return result;
        }
        return value;
    }

    private String substituteString(String value, Map<String, String> variables) {
        if (value == null || value.isEmpty() || variables.isEmpty()) return value;
        Matcher matcher = TEMPLATE_PATTERN.matcher(value);
        StringBuilder builder = new StringBuilder();
        boolean replaced = false;
        while (matcher.find()) {
            String replacement = variables.get(matcher.group(1));
            if (replacement == null) {
                matcher.appendReplacement(builder, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
                replaced = true;
            }
        }
        matcher.appendTail(builder);
        return replaced ? builder.toString() : value;
    }

    /** Mutable progress counters shared across nested control-flow blocks. */
    private static final class ExecutionTracker {
        private int index;
        private int total;

        private ExecutionTracker(int total) {
            this.total = total;
        }
    }

    // ==================== Preflight Helpers ====================

    /** 从 scenario 所有 blocks（包括 parallel / if / for / switch 内部）收集扁平的 StepConfig 列表。 */
    static List<StepConfig> collectAllSteps(ScenarioConfig scenario) {
        return ScenarioBlocks.collectSteps(scenario.getBlocks());
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
