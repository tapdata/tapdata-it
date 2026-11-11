package io.tapdata.it.l3.common;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe SpEL evaluator — only exposes property access + Map lookup.
 * <p>
 * Dangerous operations ({@code T()}, {@code new}, arbitrary method invocations,
 * {@code getType()}) are all blocked by {@link SimpleEvaluationContext}.
 * <p>
 * Additional null-safety pre-check: before delegating to SpEL, extracts all
 * {@code #steps['STEP_ID'].outputs['KEY']} references from the expression and
 * validates each one (step exists, step has outputs map, key exists). Any missing
 * reference throws {@link SpelNullReferenceException} with diagnostic info instead
 * of letting a raw NPE propagate.
 * <p>
 * Built-in variables accessible from DSL:
 * <pre>
 * #{ #variables['timestamp'] }          // millisecond timestamp
 * #{ #variables['uuid'] }               // UUID v4 string
 * #{ #steps['step_id'].outputs['key'] } // reference another step's output
 * </pre>
 */
public class SafeSpelEvaluator {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    /** Matches #{ ... #steps['STEP_ID'].outputs['KEY'] ... } patterns. */
    private static final Pattern STEP_REF_PATTERN =
            Pattern.compile("#steps\\['([^']+)'\\]\\.outputs\\['([^']+)'\\]");

    /** Matches any #{ ... } block. */
    private static final Pattern SPEL_BLOCK_PATTERN = Pattern.compile("#\\{(.+?)\\}");

    private final EvaluationContext context;
    private final Map<String, Object> variables;
    /** Wrapped step map (stepId → StepOutputsWrapper) — used for SpEL navigation with .outputs property. */
    private final Map<String, ? extends Object> stepOutputs;
    /** Raw step outputs (stepId → outputs map) — used for null-safety validation (key existence checks). */
    private final Map<String, ? extends Map<String, Object>> stepOutputsRaw;

    /** Scenario env map — exposed as {@code #env['KEY']}. Defaults to empty; set via {@link #registerEnv}. */
    private Map<String, Object> env = Map.of();

    public SafeSpelEvaluator(Map<String, Object> variables,
                             Map<String, ? extends Object> stepOutputsWrapped,
                             Map<String, ? extends Map<String, Object>> stepOutputsRaw) {
        this.variables = variables;
        this.stepOutputs = stepOutputsWrapped;
        this.stepOutputsRaw = stepOutputsRaw;

        // SimpleEvaluationContext already denies T(), new, invoke, getType — safe by default.
        this.context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        context.setVariable("variables", variables);
        context.setVariable("steps", stepOutputsWrapped);
        // #env must always exist (empty map until setEnv) so #env['KEY'] resolves to null
        // instead of failing with "EL1007E Property or field 'env' cannot be found on null".
        context.setVariable("env", env);
    }

    /**
     * Register the scenario env map — enables {@code #env['KEY']} access.
     * Called by ScenarioContext.setEnv (via ScenarioRunner) before steps execute.
     */
    public void registerEnv(Map<String, Object> envMap) {
        context.setVariable("env", envMap != null ? envMap : Map.of());
    }

    /** Back-compat constructor — only used by tests. */
    public SafeSpelEvaluator(Map<String, Object> variables,
                             Map<String, ? extends Map<String, Object>> stepOutputs) {
        this(variables, stepOutputs, stepOutputs);
    }

    /**
     * Evaluate an expression containing one or more {@code #{ ... }} blocks.
     * <p>
     * If the expression has NO {@code #{}} blocks, returns the string unchanged.
     * Otherwise performs null-safety validation on step references first, then delegates to SpEL.
     */
    public Object evaluate(String expression) {
        if (expression == null) return null;

        // 1. If the entire expression is one #{...} block, evaluate directly
        Matcher fullBlock = Pattern.compile("^#\\{(.+)\\}$").matcher(expression.trim());
        if (fullBlock.matches()) {
            return evaluateSingle(fullBlock.group(1), expression);
        }

        // 2. Otherwise: string-interpolate multiple #{...} blocks
        Matcher blockMatcher = SPEL_BLOCK_PATTERN.matcher(expression);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (blockMatcher.find()) {
            result.append(expression, lastEnd, blockMatcher.start());
            String inner = blockMatcher.group(1);
            Object value = evaluateSingle(inner, expression);
            result.append(value != null ? value.toString() : "null");
            lastEnd = blockMatcher.end();
        }
        result.append(expression.substring(lastEnd));
        return result.toString();
    }

    private Object evaluateSingle(String spelBody, String fullExpression) {
        // Null-safety pre-check on step references
        validateStepReferences(spelBody, fullExpression);

        try {
            Expression exp = PARSER.parseExpression(spelBody);
            return exp.getValue(context);
        } catch (EvaluationException e) {
            throw new SpelEvaluationException(
                    "Expression evaluation failed for: #{" + spelBody + "}", e);
        } catch (Exception e) {
            throw new SpelEvaluationException(
                    "Unexpected error evaluating: #{" + spelBody + "}", e);
        }
    }

    /**
     * Extracts all {@code #steps['STEP'].outputs['KEY']} references from the expression
     * and validates each one — throws {@link SpelNullReferenceException} with diagnostics on failure.
     */
    private void validateStepReferences(String spelBody, String fullExpression) {
        Matcher matcher = STEP_REF_PATTERN.matcher(spelBody);
        while (matcher.find()) {
            String stepId = matcher.group(1);
            String outputKey = matcher.group(2);
            validateStepOutput(fullExpression, stepId, outputKey);
        }
    }

    private void validateStepOutput(String fullExpression, String stepId, String outputKey) {
        // Step exists?
        if (!stepOutputsRaw.containsKey(stepId)) {
            String availableSteps = stepOutputsRaw.keySet().toString();
            throw new SpelNullReferenceException(String.format(
                    "Expression evaluation failed: Step '%s' not found. "
                            + "Total registered steps: %s%n"
                            + "Expression: %s",
                    stepId, availableSteps, fullExpression));
        }

        // Step has outputs?
        Map<String, Object> outputs = stepOutputsRaw.get(stepId);
        if (outputs == null || outputs.isEmpty()) {
            throw new SpelNullReferenceException(String.format(
                    "Expression evaluation failed: Step '%s' has no outputs yet. "
                            + "It may not have been declared with 'outputs:' or hasn't executed successfully.%n"
                            + "Expression: %s",
                    stepId, fullExpression));
        }

        // Key exists?
        if (!outputs.containsKey(outputKey)) {
            String availableKeys = outputs.keySet().toString();
            throw new SpelNullReferenceException(String.format(
                    "Expression evaluation failed: Step '%s' did not produce output '%s'.%n"
                            + "Available outputs for this step: %s%n"
                            + "Expression: %s",
                    stepId, outputKey, availableKeys, fullExpression));
        }
    }

    // ==================== Exception Classes ====================

    /** Null-safety exception: thrown when a step reference cannot be resolved. */
    public static class SpelNullReferenceException extends RuntimeException {
        public SpelNullReferenceException(String message) {
            super(message);
        }
    }

    /** Wrapper for SpEL internal evaluation exceptions (not null-safety related). */
    public static class SpelEvaluationException extends RuntimeException {
        public SpelEvaluationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
