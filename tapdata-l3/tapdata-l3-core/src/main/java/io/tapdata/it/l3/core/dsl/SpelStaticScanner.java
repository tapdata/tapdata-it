package io.tapdata.it.l3.core.dsl;

import io.tapdata.it.l3.common.ForBlock;
import io.tapdata.it.l3.common.IfBlock;
import io.tapdata.it.l3.common.ParallelBlock;
import io.tapdata.it.l3.common.ScenarioBlocks;
import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.SwitchBlock;
import io.tapdata.it.l3.common.WhileBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SpEL 静态扫描 — 在 validate 阶段（不执行任何 step）检查表达式引用的可判定错误。
 * <p>
 * 检查规则：
 * <ul>
 *   <li>{@code #steps['X']} — X 不存在于场景 → <b>error</b>（一定失败）；
 *       {@code .outputs['Y']} 中 Y 不在 X 声明的 outputs 里 → warn（outputs 可能动态注册）</li>
 *   <li>{@code #env['K']} — K 不在 scenario.env 里 → <b>error</b>（env 静态可判定）</li>
 *   <li>{@code #variables['V']} — V 不在声明 variables/env/内置/for·while 变量里 → warn
 *       （step 可能运行时动态 setVariable，不能定判）</li>
 * </ul>
 * 扫描范围：每个 step 的 inputs 字符串叶子节点 + outputs 声明 + if/while/switch 的条件表达式。
 */
public final class SpelStaticScanner {

    private static final Pattern STEP_REF = Pattern.compile("#steps\\['([^']+)'\\](\\.outputs\\['([^']+)'\\])?");
    private static final Pattern ENV_REF = Pattern.compile("#env\\['([^']+)'\\]");
    private static final Pattern VAR_REF = Pattern.compile("#variables\\['([^']+)'\\]");

    /** 内置变量（ScenarioContext 构造时注册） */
    private static final Set<String> BUILTINS = Set.of("timestamp", "uuid", "run_id");

    private SpelStaticScanner() {}

    /** 单条扫描发现：severity = "error"（一定失败）或 "warn"（可疑，需人工确认）。 */
    public record Finding(String severity, String stepId, String message) {}

    public static List<Finding> scan(ScenarioConfig scenario) {
        Set<String> stepIds = new HashSet<>();
        Set<String> forVars = new LinkedHashSet<>();
        Set<String> conditions = new LinkedHashSet<>();
        collect(scenario.getBlocks(), stepIds, forVars, conditions);

        // stepId → declared outputs（仅声明过 outputs 的 step 参与输出 key 校验）
        Map<String, Set<String>> declaredOutputs = new LinkedHashMap<>();
        for (StepConfig s : ScenarioBlocks.collectSteps(scenario.getBlocks())) {
            declaredOutputs.put(s.getId(), s.getOutputs() == null ? Set.of() : new HashSet<>(s.getOutputs()));
        }

        Set<String> knownVars = new LinkedHashSet<>(BUILTINS);
        if (scenario.getVariables() != null) knownVars.addAll(scenario.getVariables().keySet());
        if (scenario.getEnv() != null) knownVars.addAll(scenario.getEnv().keySet());
        knownVars.addAll(forVars);

        Set<String> envKeys = scenario.getEnv() == null ? Set.of() : scenario.getEnv().keySet();

        List<Finding> findings = new ArrayList<>();
        for (StepConfig step : ScenarioBlocks.collectSteps(scenario.getBlocks())) {
            Set<String> expressions = new LinkedHashSet<>();
            collectStringLeaves(step.getInputs(), expressions);
            conditions.forEach(expressions::add);
            conditions = new LinkedHashSet<>(); // conditions 只归属第一个收集到它的 step（近似即可）

            if (step.getOutputs() != null) {
                step.getOutputs().forEach(expressions::add);
            }

            for (String expr : expressions) {
                Matcher m = STEP_REF.matcher(expr);
                while (m.find()) {
                    String ref = m.group(1);
                    String outputKey = m.group(3);
                    if (!stepIds.contains(ref)) {
                        findings.add(new Finding("error", step.getId(),
                                "references unknown step '#steps['" + ref + "]' — available: " + stepIds));
                    } else if (outputKey != null && !declaredOutputs.getOrDefault(ref, Set.of()).isEmpty()
                            && !declaredOutputs.get(ref).contains(outputKey)) {
                        findings.add(new Finding("warn", step.getId(),
                                "step '" + ref + "' does not declare output '" + outputKey + "' (may be dynamic)"));
                    }
                }
                Matcher em = ENV_REF.matcher(expr);
                while (em.find()) {
                    String key = em.group(1);
                    if (!envKeys.contains(key)) {
                        findings.add(new Finding("error", step.getId(),
                                "references unknown env key '#env['" + key + "]' — available: " + envKeys));
                    }
                }
                Matcher vm = VAR_REF.matcher(expr);
                while (vm.find()) {
                    String key = vm.group(1);
                    if (!knownVars.contains(key)) {
                        findings.add(new Finding("warn", step.getId(),
                                "references undeclared variable '#variables['" + key
                                        + "]' — OK if set at runtime via setVariable"));
                    }
                }
            }
        }
        return findings;
    }

    // ==================== Block tree walk ====================

    private static void collect(List<Object> blocks, Set<String> stepIds, Set<String> forVars,
                                Set<String> conditions) {
        if (blocks == null) return;
        for (Object block : blocks) {
            if (block instanceof StepConfig step) {
                stepIds.add(step.getId());
            } else if (block instanceof ParallelBlock p) {
                for (StepConfig s : p.getSteps()) stepIds.add(s.getId());
            } else if (block instanceof IfBlock b) {
                addCondition(conditions, b.getCondition());
                collect(b.getThenBlocks(), stepIds, forVars, conditions);
                collect(b.getElseBlocks(), stepIds, forVars, conditions);
            } else if (block instanceof ForBlock b) {
                if (b.getVar() != null) forVars.add(b.getVar());
                if (b.getIndexVar() != null) forVars.add(b.getIndexVar());
                collect(b.getBlocks(), stepIds, forVars, conditions);
            } else if (block instanceof WhileBlock b) {
                addCondition(conditions, b.getCondition());
                if (b.getIndexVar() != null) forVars.add(b.getIndexVar());
                collect(b.getBlocks(), stepIds, forVars, conditions);
            } else if (block instanceof SwitchBlock b) {
                addCondition(conditions, b.getExpression());
                b.getCases().values().forEach(c -> collect(c, stepIds, forVars, conditions));
                collect(b.getDefaultBlocks(), stepIds, forVars, conditions);
            }
        }
    }

    private static void addCondition(Set<String> conditions, Object condition) {
        if (condition instanceof String s) conditions.add(s);
        else if (condition != null) conditions.add(String.valueOf(condition));
    }

    // ==================== Helpers ====================

    private static void collectStringLeaves(Object value, Set<String> out) {
        if (value instanceof String s) {
            out.add(s);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectStringLeaves(v, out));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collectStringLeaves(v, out));
        }
    }
}
