package io.tapdata.it.l3.common;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Scenario-level variable context — decoupled from JUnit lifecycle.
 * <p>
 * Carries user-declared {@code variables} plus per-step {@code outputs}.
 * Expression evaluation is delegated to {@link SafeSpelEvaluator}.
 * <p>
 * Thread-safe: uses ConcurrentHashMap and is designed for parallel step execution.
 */
public class ScenarioContext {

    /** User-declared variables + built-ins (timestamp, uuid). */
    private final ConcurrentMap<String, Object> variables = new ConcurrentHashMap<>();

    /** Raw per-step outputs for validation: stepId → (outputKey → value). */
    private final ConcurrentMap<String, ConcurrentMap<String, Object>> stepsRaw = new ConcurrentHashMap<>();

    /** Wrapped per-step outputs for SpEL: stepId → StepOutputsWrapper (has .outputs property). */
    private final ConcurrentMap<String, StepOutputsWrapper> stepsWrapped = new ConcurrentHashMap<>();

    /** Safe SpEL evaluator — shared instance per context. */
    private final SafeSpelEvaluator evaluator;

    /** Cleanup stack — populated by steps that allocate resources. Entries may carry a tag. */
    private final Deque<CleanupEntry> cleanupStack = new java.util.ArrayDeque<>();

    /** Scenario env map — exposed to SpEL as {@code #env['KEY']}. */
    private volatile Map<String, Object> env = Map.of();

    public ScenarioContext() {
        variables.put("timestamp", System.currentTimeMillis());
        variables.put("uuid", java.util.UUID.randomUUID().toString());
        // Per-run unique suffix (10 digits, e.g. 1111104231) — used by DSL to build unique
        // db/connection/task names and avoid collisions with leftovers from previous runs.
        variables.put("run_id", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMddHHmmss")));
        // Evaluator needs both: wrapped for SpEL navigation (.outputs property), raw for null-safety validation
        this.evaluator = new SafeSpelEvaluator(variables, stepsWrapped, stepsRaw);
    }

    /**
     * Register the scenario env map so SpEL can access it as {@code #env['KEY']}.
     * Called by ScenarioRunner before steps execute. Also keeps {@code #variables['env']}
     * working for legacy expressions.
     */
    public void setEnv(Map<String, String> envMap) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (envMap != null) merged.putAll(envMap);
        this.env = java.util.Collections.unmodifiableMap(merged);
        evaluator.registerEnv(this.env);
        // keep legacy access path #{ #variables['env']['KEY'] } working
        variables.put("env", new java.util.HashMap<>(merged));
    }

    /** Scenario env map (read-only view). */
    public Map<String, Object> getEnv() {
        return env;
    }

    // ==================== Variables ====================

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }

    public void removeVariable(String name) {
        variables.remove(name);
    }

    public Map<String, Object> getVariables() {
        return java.util.Collections.unmodifiableMap(variables);
    }

    // ==================== Step Outputs ====================

    public void setStepOutput(String stepId, String outputKey, Object value) {
        ConcurrentMap<String, Object> raw = stepsRaw.computeIfAbsent(stepId, k -> new ConcurrentHashMap<>());
        raw.put(outputKey, value);
        // Keep wrapper in sync — always points to the same raw map reference
        stepsWrapped.computeIfAbsent(stepId, k -> new StepOutputsWrapper(raw));
    }

    public Object getStepOutput(String stepId, String outputKey) {
        Map<String, Object> outputs = stepsRaw.get(stepId);
        return outputs != null ? outputs.get(outputKey) : null;
    }

    /** Raw outputs — used by SafeSpelEvaluator for null-safety validation. */
    public Map<String, ConcurrentMap<String, Object>> getSteps() {
        return java.util.Collections.unmodifiableMap(stepsRaw);
    }

    // ==================== Expression Evaluation ====================

    /**
     * Evaluate a string that may contain SpEL expressions {@code #{ ... }}.
     * <p>
     * If the string contains no {@code #{}}, it is returned as-is.
     * Otherwise, SafeSpelEvaluator handles resolution with null-safety.
     */
    public Object evaluateExpression(String expression) {
        if (expression == null) return null;
        if (!(expression instanceof String)) {
            return evaluator.evaluate(expression);
        }
        String s = (String) expression;
        if (!s.contains("#{")) {
            return s;
        }
        return evaluator.evaluate(s);
    }

    /**
     * Recursively evaluate SpEL expressions inside a Map's leaf values.
     * Supports nested Maps and Lists.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluateInputs(Map<String, Object> inputs) {
        if (inputs == null) return null;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            result.put(entry.getKey(), evaluateValue(entry.getValue()));
        }
        return result;
    }

    /**
     * Recursively evaluate SpEL expressions inside any value (String / Map / List).
     * Non-expression values are returned unchanged.
     * <p>
     * Exposed so control-flow blocks ({@code if} / {@code for} / {@code switch}) can
     * evaluate their conditions and iteration sources with the same semantics as step inputs.
     */
    @SuppressWarnings("unchecked")
    public Object evaluateValue(Object value) {
        if (value == null) return null;
        if (value instanceof String) {
            String s = (String) value;
            if (s.contains("#{")) return evaluateExpression(s);
            return s;
        }
        if (value instanceof Map) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                result.put((String) e.getKey(), evaluateValue(e.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            return list.stream().map(this::evaluateValue).toList();
        }
        return value;
    }

    // ==================== Cleanup Stack ====================

    /** Tagged cleanup entry — tags let later steps (e.g. undeploy_tapdata) cancel pending cleanups. */
    public record CleanupEntry(String tag, Runnable action) {}

    public void registerCleanup(Runnable action) {
        registerCleanup(null, action);
    }

    /** Register a cleanup hook with a tag (e.g. "tm" for TM resources, "k8s" for deployments). */
    public void registerCleanup(String tag, Runnable action) {
        cleanupStack.push(new CleanupEntry(tag, action));
    }

    /**
     * Remove all pending cleanup hooks carrying the given tag (they will NOT run).
     * Used when a step intentionally tears down the environment those resources belong to
     * (e.g. undeploy_tapdata removing the TM env that pending tm_* cleanups target).
     *
     * @return number of cancelled hooks
     */
    public synchronized int unregisterCleanups(String tag) {
        int removed = 0;
        java.util.Iterator<CleanupEntry> it = cleanupStack.iterator();
        while (it.hasNext()) {
            CleanupEntry e = it.next();
            if (tag != null && tag.equals(e.tag())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    public Deque<CleanupEntry> getCleanupStack() {
        return cleanupStack;
    }
}
