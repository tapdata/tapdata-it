package io.tapdata.it.l3.plugin.step;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.exception.StepTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
/**
 * Unit test for ActionHandler lifecycle (init / before / after / destroy).
 *
 * BaseStep 在 execute() 内部编排生命周期顺序：
 *   init → before → doExecute → after
 *   init 只调一次；before/after 每次都调（哪怕 before 自己报错）。
 */
class BaseStepLifecycleTest {

    private ScenarioContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new ScenarioContext();
    }

    private StepConfig config(String id, int timeoutSeconds) {
        return StepConfig.builder()
                .id(id).action("test")
                .timeoutSeconds(timeoutSeconds)
                .inputs(Map.of()).outputs(List.of())
                .build();
    }

    @SuppressWarnings("unchecked")
    private StepConfig emptyConfig() {
        return config("unit-test", 5);
    }

    // -----------------------------------------------------------------------
    // 测试用 BaseStep 子类 — 记录所有生命周期调用顺序
    // -----------------------------------------------------------------------

    /** 成功的 doExecute */
    static class SuccessStep extends BaseStep {
        final List<String> log = new ArrayList<>();
        boolean doExecuteFail = false;

        @Override public String getActionName() { return "success_test"; }

        @Override public void init() { log.add("init"); }
        @Override public void before(StepConfig c, ScenarioContext ctx) { log.add("before:" + c.getId()); }
        @Override public void after(StepConfig c, ScenarioContext ctx, boolean success, Throwable err) {
            log.add("after:" + success + ":" + (err == null ? "ok" : err.getClass().getSimpleName()));
        }

        @Override protected StepResult doExecute(StepConfig c, ScenarioContext ctx) throws Exception {
            log.add("doExecute:" + c.getId());
            if (doExecuteFail) throw new RuntimeException("boom");
            return success(Map.of("out", "value"));
        }
    }

    /** before() 自己就炸了 — doExecute 不应该被调用 */
    static class BeforeFailsStep extends BaseStep {
        final List<String> log = new ArrayList<>();

        @Override public String getActionName() { return "before_fail_test"; }
        @Override public void init() { log.add("init"); }
        @Override public void before(StepConfig c, ScenarioContext ctx) { log.add("before"); throw new IllegalArgumentException("before-error"); }
        @Override protected StepResult doExecute(StepConfig c, ScenarioContext ctx) { log.add("doExecute"); return success(); }
        @Override public void after(StepConfig c, ScenarioContext ctx, boolean success, Throwable err) {
            log.add("after:" + success + ":" + (err == null ? "ok" : err.getClass().getSimpleName()));
        }
    }

    /** 验证 init 只调一次 */
    static class InitOnlyOnceStep extends BaseStep {
        final AtomicInteger initCount = new AtomicInteger();
        @Override public String getActionName() { return "init_once"; }
        @Override public void init() { initCount.incrementAndGet(); }
        @Override protected StepResult doExecute(StepConfig c, ScenarioContext ctx) { return success(); }
    }

    // -----------------------------------------------------------------------
    // 测试
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("成功流程 — init → before → doExecute → after(true)")
    void successLifecycleOrder() throws Exception {
        SuccessStep step = new SuccessStep();
        step.execute(emptyConfig(), ctx);

        assertEquals(List.of("init", "before:unit-test", "doExecute:unit-test", "after:true:ok"), step.log);
        // outputs 自动注册
        assertEquals("value", ctx.getStepOutput("unit-test", "out"));
    }

    @Test
    @DisplayName("失败流程 — after(false) 被调, main error 被抛出")
    void doExecuteFailsCallsAfter() {
        SuccessStep step = new SuccessStep();
        step.doExecuteFail = true;

        Exception ex = assertThrows(RuntimeException.class, () -> step.execute(emptyConfig(), ctx));
        assertEquals("boom", ex.getMessage());
        assertEquals(List.of("init", "before:unit-test", "doExecute:unit-test", "after:false:RuntimeException"), step.log);
    }

    @Test
    @DisplayName("before() 自己报错 — doExecute 跳过, after(false) 被调, error 向上抛")
    void beforeFailSkipsDoExecute() {
        BeforeFailsStep step = new BeforeFailsStep();

        Exception ex = assertThrows(IllegalArgumentException.class, () -> step.execute(emptyConfig(), ctx));
        assertEquals("before-error", ex.getMessage());
        // doExecute 不在 log 里 — 确认被跳过了
        assertFalse(step.log.contains("doExecute"), "doExecute should NOT be called when before fails");
        assertEquals(List.of("init", "before", "after:false:IllegalArgumentException"), step.log);
    }

    @Test
    @DisplayName("init() 只调一次 — 多次 execute 不重复 init")
    void initOnlyOnceAcrossInvocations() throws Exception {
        InitOnlyOnceStep step = new InitOnlyOnceStep();

        step.execute(config("a", 5), ctx);
        step.execute(config("b", 5), ctx);
        step.execute(config("c", 5), ctx);

        assertEquals(1, step.initCount.get(), "init() must be called exactly once");
    }

    @Test
    @DisplayName("多次 execute — before/after 每次都调")
    void beforeAfterCalledEveryInvocation() throws Exception {
        SuccessStep step = new SuccessStep();

        step.execute(config("a", 5), ctx);
        step.execute(config("b", 5), ctx);

        long beforeCount = step.log.stream().filter(l -> l.startsWith("before:")).count();
        long afterCount  = step.log.stream().filter(l -> l.startsWith("after:")).count();

        assertEquals(2, beforeCount, "before() called once per invocation");
        assertEquals(2, afterCount,  "after() called once per invocation");
    }

    @Test
    @DisplayName("timeout → StepTimeoutException")
    void timeoutThrowsStepTimeoutException() {
        // 构造一个永不返回的 doExecute
        BaseStep forever = new BaseStep() {
            @Override public String getActionName() { return "forever"; }
            @Override protected StepResult doExecute(StepConfig c, ScenarioContext ctx) throws Exception {
                Thread.sleep(Long.MAX_VALUE); return success();
            }
        };

        assertThrows(StepTimeoutException.class, () -> forever.execute(config("slow", 1), ctx));
    }
}
