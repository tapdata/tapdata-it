package io.tapdata.it.l3.core.runner;

import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.core.cleanup.CleanupManager;
import io.tapdata.it.l3.core.dsl.DslParser;
import io.tapdata.it.l3.core.plugin.PluginCenter;
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.plugin.ActionHandler;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ScenarioRunner 控制流执行测试 — 验证 if / for / switch 的运行时语义：
 * 分支选择、循环变量与 ${} 模板替换、嵌套控制流、失败中断。
 * <p>
 * StepRunner 与 PluginCenter 使用 Mockito 打桩，只关注控制流调度本身。
 */
class ScenarioRunnerControlFlowTest {

    private final DslParser parser = new DslParser();
    private final List<StepConfig> executed = new CopyOnWriteArrayList<>();

    private StepRunner stepRunner;
    private ScenarioRunner runner;

    @BeforeEach
    void setUp() {
        PluginCenter pluginCenter = mock(PluginCenter.class);
        stepRunner = mock(StepRunner.class);

        ActionHandler handler = mock(ActionHandler.class);
        when(handler.validate(any())).thenReturn(List.of());
        when(pluginCenter.hasAction(anyString())).thenReturn(true);
        when(pluginCenter.getAction(anyString())).thenReturn(handler);

        when(stepRunner.run(any(StepConfig.class), any(ScenarioContext.class), anyCollection()))
                .thenAnswer(invocation -> {
                    StepConfig step = invocation.getArgument(0);
                    executed.add(step);
                    return StepResult.success(step, 1);
                });

        runner = new ScenarioRunner(stepRunner, new CleanupManager(), pluginCenter);
    }

    @AfterEach
    void tearDown() {
        runner.shutdown();
    }

    @Test
    @DisplayName("if — 条件为 true 只执行 then 分支")
    void ifExecutesThenBranch() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-IF-TRUE
                variables:
                  enable_cdc: true
                steps:
                  - if:
                      condition: "#{ #variables['enable_cdc'] }"
                      then:
                        - id: then_step
                          action: echo
                      else:
                        - id: else_step
                          action: echo
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("then_step"), executedIds());
    }

    @Test
    @DisplayName("if — 条件为 false 只执行 else 分支")
    void ifExecutesElseBranch() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-IF-FALSE
                variables:
                  enable_cdc: false
                steps:
                  - if:
                      condition: "#{ #variables['enable_cdc'] }"
                      then:
                        - id: then_step
                          action: echo
                      else:
                        - id: else_step
                          action: echo
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("else_step"), executedIds());
    }

    @Test
    @DisplayName("if — else 省略且条件为 false 时不执行任何 step")
    void ifWithoutElseAndFalseCondition() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-IF-NOELSE
                steps:
                  - if:
                      condition: "false"
                      then:
                        - id: then_step
                          action: echo
                """));

        assertTrue(result.isSuccess());
        assertTrue(executedIds().isEmpty());
        assertTrue(result.getSteps().isEmpty());
    }

    @Test
    @DisplayName("for — 按 items 迭代，并替换 ${var} / ${index_var} 模板")
    void forSubstitutesTemplates() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-FOR
                steps:
                  - for:
                      var: idx
                      index_var: i
                      items: [1, 2, 3]
                      steps:
                        - id: "step_${idx}"
                          action: echo
                          inputs:
                            idx: "${idx}"
                            index: "${i}"
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("step_1", "step_2", "step_3"), executedIds());

        assertEquals("1", executed.get(0).getInputs().get("idx"));
        assertEquals("0", executed.get(0).getInputs().get("index"));
        assertEquals("3", executed.get(2).getInputs().get("idx"));
        assertEquals("2", executed.get(2).getInputs().get("index"));
    }

    @Test
    @DisplayName("for — items 支持 SpEL 表达式返回的集合")
    void forItemsFromSpel() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-FOR-SPEL
                variables:
                  targets: [a, b]
                steps:
                  - for:
                      var: target
                      items: "#{ #variables['targets'] }"
                      steps:
                        - id: "step_${target}"
                          action: echo
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("step_a", "step_b"), executedIds());
    }

    @Test
    @DisplayName("嵌套控制流 — for 内 if 按循环变量走不同分支")
    void nestedForWithIf() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-NESTED
                steps:
                  - for:
                      var: idx
                      items: [1, 2, 3]
                      steps:
                        - if:
                            condition: "#{ #variables['idx'] == 2 }"
                            then:
                              - id: "even_${idx}"
                                action: echo
                            else:
                              - id: "odd_${idx}"
                                action: echo
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("odd_1", "even_2", "odd_3"), executedIds());
    }

    @Test
    @DisplayName("switch — 命中 case 只执行对应分支")
    void switchMatchesCase() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-SWITCH
                variables:
                  db_type: mysql
                steps:
                  - switch:
                      expression: "#{ #variables['db_type'] }"
                      cases:
                        mysql:
                          - id: mysql_step
                            action: echo
                        postgresql:
                          - id: pg_step
                            action: echo
                      default:
                        - id: default_step
                          action: echo
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("mysql_step"), executedIds());
    }

    @Test
    @DisplayName("switch — 无命中时执行 default")
    void switchFallsBackToDefault() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-SWITCH-DEFAULT
                variables:
                  db_type: oracle
                steps:
                  - switch:
                      expression: "#{ #variables['db_type'] }"
                      cases:
                        mysql:
                          - id: mysql_step
                            action: echo
                      default:
                        - id: default_step
                          action: echo
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("default_step"), executedIds());
    }

    @Test
    @DisplayName("for — 中间步骤失败后立即中断，不再执行后续迭代")
    void forStopsOnFailure() {
        when(stepRunner.run(any(StepConfig.class), any(ScenarioContext.class), anyCollection()))
                .thenAnswer(invocation -> {
                    StepConfig step = invocation.getArgument(0);
                    executed.add(step);
                    if ("step_2".equals(step.getId())) {
                        return StepResult.failure(step, new IllegalStateException("boom"), 1);
                    }
                    return StepResult.success(step, 1);
                });

        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-FOR-FAIL
                steps:
                  - for:
                      var: idx
                      items: [1, 2, 3]
                      steps:
                        - id: "step_${idx}"
                          action: echo
                """));

        assertFalse(result.isSuccess());
        assertEquals(List.of("step_1", "step_2"), executedIds());
        assertNotNull(result.getFailedStep());
        assertEquals("step_2", result.getFailedStep().getStepConfig().getId());
    }

    @Test
    @DisplayName("while — 每次迭代重新求值条件，index_var 支持模板替换")
    void whileExecutesUntilConditionFalse() {
        AtomicInteger counter = new AtomicInteger();
        when(stepRunner.run(any(StepConfig.class), any(ScenarioContext.class), anyCollection()))
                .thenAnswer(invocation -> {
                    StepConfig step = invocation.getArgument(0);
                    ScenarioContext ctx = invocation.getArgument(1);
                    executed.add(step);
                    ctx.setVariable("counter", counter.incrementAndGet());
                    return StepResult.success(step, 1);
                });

        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-WHILE
                variables:
                  counter: 0
                steps:
                  - while:
                      condition: "#{ #variables['counter'] < 3 }"
                      index_var: i
                      steps:
                        - id: "poll_${i}"
                          action: echo
                          inputs:
                            attempt: "${i}"
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("poll_0", "poll_1", "poll_2"), executedIds());
        assertEquals("0", executed.get(0).getInputs().get("attempt"));
        assertEquals("2", executed.get(2).getInputs().get("attempt"));
    }

    @Test
    @DisplayName("while — 初始条件为 false 时一次也不执行")
    void whileSkipsWhenConditionFalseInitially() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-WHILE-SKIP
                variables:
                  attempts: 5
                steps:
                  - while:
                      condition: "#{ #variables['attempts'] < 3 }"
                      steps:
                        - id: never_run
                          action: echo
                """));

        assertTrue(result.isSuccess());
        assertTrue(executedIds().isEmpty());
    }

    @Test
    @DisplayName("while — 超过 max_iterations 直接失败，避免死循环")
    void whileStopsAtMaxIterations() {
        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-WHILE-MAX
                steps:
                  - while:
                      condition: "true"
                      max_iterations: 2
                      steps:
                        - id: forever
                          action: echo
                """));

        assertFalse(result.isSuccess());
        assertEquals(2, executed.size());
        assertNotNull(result.getFailedStep());
        assertNotNull(result.getFailedStep().getError());
        assertTrue(result.getFailedStep().getError().getMessage().contains("max_iterations"));
    }

    @Test
    @DisplayName("嵌套 — for → switch → while → if 四层混合嵌套")
    void nestedAllControlFlow() {
        AtomicInteger counter = new AtomicInteger();
        when(stepRunner.run(any(StepConfig.class), any(ScenarioContext.class), anyCollection()))
                .thenAnswer(invocation -> {
                    StepConfig step = invocation.getArgument(0);
                    ScenarioContext ctx = invocation.getArgument(1);
                    executed.add(step);
                    ctx.setVariable("counter", counter.incrementAndGet());
                    return StepResult.success(step, 1);
                });

        ScenarioResult result = runner.execute(parse("""
                case_id: L3-CTRL-NEST-ALL
                variables:
                  modes: [a, b]
                  counter: 0
                steps:
                  - for:
                      var: mode
                      items: "#{ #variables['modes'] }"
                      steps:
                        - switch:
                            expression: "#{ #variables['mode'] }"
                            cases:
                              a:
                                - while:
                                    condition: "#{ #variables['counter'] < 2 }"
                                    index_var: w
                                    steps:
                                      - if:
                                          condition: "#{ #variables['w'] == 1 }"
                                          then:
                                            - id: "a_hit_${mode}_${w}"
                                              action: echo
                                          else:
                                            - id: "a_miss_${mode}_${w}"
                                              action: echo
                              b:
                                - id: b_step
                                  action: echo
                """));

        assertTrue(result.isSuccess());
        assertEquals(List.of("a_miss_a_0", "a_hit_a_1", "b_step"), executedIds());
    }

    private List<String> executedIds() {
        return executed.stream().map(StepConfig::getId).toList();
    }

    private ScenarioConfig parse(String yamlContent) {
        try {
            Path temp = Files.createTempFile("l3-ctrl-test-", ".yaml");
            Files.writeString(temp, yamlContent);
            try {
                return parser.parse(temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
