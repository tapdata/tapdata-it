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
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

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

        StepResult failure = null;
        long durationMs = 0;

        try {
            int totalSteps = countSteps(scenario);
            int stepIndex = 0;

            for (Object block : scenario.getBlocks()) {
                if (block instanceof StepConfig step) {
                    stepIndex++;
                    StepResult result = runWithProgress(step, stepIndex, totalSteps, ctx);
                    if (result.isSuccess()) continue;
                    failure = result;
                    break;
                } else if (block instanceof ParallelBlock parallel) {
                    stepIndex++; // treat whole parallel block as one progress tick
                    StepResult result = runParallelBlock(parallel, stepIndex, totalSteps, ctx);
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
            pluginCenter.destroyAllHandlers();
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

    private StepResult runWithProgress(StepConfig step, int index, int total, ScenarioContext ctx) {
        log.info("[Step {}/{}] {} → {} ...", index, total, step.getId(), step.getAction());
        StepResult result = stepRunner.run(step, ctx);
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

    private StepResult runParallelBlock(ParallelBlock block, int index, int total, ScenarioContext ctx) {
        List<CompletableFuture<StepResult>> futures = new ArrayList<>();

        for (StepConfig step : block.getSteps()) {
            CompletableFuture<StepResult> future = CompletableFuture.supplyAsync(
                    () -> stepRunner.run(step, ctx), parallelExecutor);
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
}
