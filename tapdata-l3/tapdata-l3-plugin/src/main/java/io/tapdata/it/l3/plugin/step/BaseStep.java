package io.tapdata.it.l3.plugin.step;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.exception.StepTimeoutException;
import io.tapdata.it.l3.plugin.ActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for all L3 steps — built-in and external plugin steps alike.
 * <p>
 * Lives in {@code tapdata-l3-plugin} (the SPI module with zero Spring Boot dependency)
 * so external step libraries only need this one dependency to implement full step semantics.
 * <p>
 * Provides three key capabilities out of the box:
 * <ol>
 *   <li><b>Lifecycle orchestration</b> — {@code init()} / {@code before()} / {@code after()} / {@code destroy()}
 *       hooks called automatically at the right times.</li>
 *   <li><b>Async execution with hard timeout</b> — {@link #execute(StepConfig, ScenarioContext)}
 *       wraps subclass's {@link #doExecute(StepConfig, ScenarioContext)} in a {@link CompletableFuture}
 *       with {@code orTimeout(timeout_seconds)} hard deadline.</li>
 *   <li><b>Automatic output registration</b> — any key from {@link StepResult#getOutputs()} is written
 *       to {@link ScenarioContext} after successful completion.</li>
 * </ol>
 * <p>
 * <b>Lifecycle order</b>:
 * <pre>
 *   action = new XxxAction()                    ← 反射实例化（必须有无参构造器）
 *   action.init()                               ← 一次
 *   for each step invocation:
 *     action.before(config, ctx)                ← 每次
 *     action.doExecute(config, ctx)             ← 子类实现
 *     action.after(config, ctx, success, err)   ← 每次（无论成功失败）
 *   // Scenario 结束
 *   action.destroy()                            ← 清理资源
 * </pre>
 * <p>
 * Subclasses override {@link #doExecute} with their sync core logic.
 * Do NOT override {@link #execute} — that's the async wrapper that handles lifecycle + timeout.
 */
public abstract class BaseStep implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(BaseStep.class);

    /** Default timeout if none declared by subclass: 60 seconds. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * Per-step async executor. Each step gets its own thread pool to avoid cross-step contention.
     * Core size = availableProcessors, max = 4× for I/O-bound work.
     */
    private static final ExecutorService ASYNC_EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().availableProcessors() * 4,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "l3-step-async");
                t.setDaemon(true);
                return t;
            });

    /**
     * init() 是否已执行 — 一个 ActionHandler 实例的 init 只执行一次，
     * 之后可以被多个 step invocation 复用（每次都走 before→doExecute→after）。
     */
    private final AtomicBoolean initDone = new AtomicBoolean(false);

    /** Subclass implements the sync core logic here. */
    protected abstract StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception;

    /**
     * Async wrapper — wraps doExecute() in CompletableFuture + orTimeout + lifecycle hooks.
     * <p>
     * DO NOT override this method in subclasses.
     */
    @Override
    public final void execute(StepConfig config, ScenarioContext ctx) throws Exception {
        // 0. init() — 只执行一次
        if (initDone.compareAndSet(false, true)) {
            try {
                init();
            } catch (Exception e) {
                log.error("[BaseStep] init() failed for '{}' ({}): {}",
                        config.getId(), getActionName(), e.getMessage());
                throw e;
            }
        }

        int timeoutSeconds = config.getTimeoutSeconds() > 0
                ? config.getTimeoutSeconds()
                : DEFAULT_TIMEOUT_SECONDS;

        log.debug("[BaseStep] '{}' ({}) starting with {}s timeout",
                config.getId(), getActionName(), timeoutSeconds);

        ClassLoader stepCL = config.getStepClassLoader();

        // 1. before() — 同步执行（在 main 线程），可以访问 DSL 配置初始化依赖
        boolean beforeOk = true;
        Throwable beforeErr = null;
        try {
            before(config, ctx);
        } catch (Exception e) {
            beforeOk = false;
            beforeErr = e;
            log.error("[BaseStep] before() failed for '{}' ({}): {}",
                    config.getId(), getActionName(), e.getMessage());
        }

        StepResult result = null;
        Throwable executeErr = beforeErr;

        if (beforeOk) {
            // 2. doExecute() — 异步执行
            CompletableFuture<StepResult> future = CompletableFuture.supplyAsync(
                    () -> {
                        Thread current = Thread.currentThread();
                        ClassLoader prevCL = current.getContextClassLoader();
                        if (stepCL != null) current.setContextClassLoader(stepCL);
                        try {
                            return doExecute(config, ctx);
                        } catch (Exception e) {
                            if (e instanceof RuntimeException re) throw re;
                            throw new CompletionException(e);
                        } finally {
                            if (stepCL != null) current.setContextClassLoader(prevCL);
                        }
                    }, ASYNC_EXECUTOR);

            try {
                result = future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).join();
                // 3a. doExecute 返回 failure(...) 同样视为失败 —— 否则 StepRunner 会把
                //     未抛异常的 failure 结果当成 SUCCESS（exitCode/断言失败被静默吞掉）
                if (result != null && !result.isSuccess()) {
                    executeErr = result.getError() != null
                            ? result.getError()
                            : new RuntimeException("Step '" + config.getId() + "' returned failure result");
                } else if (result != null && result.getOutputs() != null) {
                    // 成功返回 — 自动注册 outputs
                    for (Map.Entry<String, Object> entry : result.getOutputs().entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            ctx.setStepOutput(config.getId(), entry.getKey(), entry.getValue());
                        }
                    }
                }
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof TimeoutException te) {
                    executeErr = new StepTimeoutException(config.getId(), timeoutSeconds, te);
                } else if (cause instanceof StepTimeoutException ste) {
                    executeErr = ste;
                } else if (cause instanceof Exception ex) {
                    executeErr = ex;
                } else {
                    executeErr = new RuntimeException(cause);
                }
            }
        }

        // 3b. after() — 无论成功失败都执行（资源清理、日志收尾）
        boolean success = executeErr == null;
        try {
            after(config, ctx, success, executeErr);
        } catch (Exception afterErr) {
            // after 自己抛的异常不覆盖主异常，只是 WARN
            log.warn("[BaseStep] after() threw for '{}' ({}) — ignoring: {}",
                    config.getId(), getActionName(), afterErr.getMessage());
        }

        // 4. 向上抛异常（如果有）
        if (executeErr != null) {
            if (executeErr instanceof Exception ex) throw ex;
            throw new RuntimeException(executeErr);
        }
    }

    // Convenience: successful StepResult with optional outputs
    protected StepResult success() {
        return StepResult.builder().success(true).durationMs(0).build();
    }

    protected StepResult success(Map<String, Object> outputs) {
        return StepResult.builder().success(true).outputs(outputs).durationMs(0).build();
    }

    protected StepResult failure(Throwable error) {
        return StepResult.builder().success(false).error(error).durationMs(0).build();
    }
}
