package io.tapdata.it.l3.core.console;

import io.tapdata.it.l3.common.ScenarioBlocks;
import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.plugin.step.StepResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Human-friendly console renderer for L3 test runs.
 * <p>
 * Every line is prefixed with {@code [L3]} so the run timeline stays easy to
 * grep from a tee'd log. ANSI colour is only enabled on a real terminal
 * (or with {@code L3_COLOR=always}); redirected/CI logs remain plain text.
 */
public final class ConsoleUI {

    private static final Object LOCK = new Object();
    private static final int RULE_WIDTH = 76;
    private static final int STEP_ID_WIDTH = 28;
    private static final int ACTION_WIDTH = 24;

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";

    private static final boolean COLOR = detectColor();

    private ConsoleUI() {
    }

    // ==================== Run lifecycle ====================

    public static void banner(List<ScenarioConfig> scenarios) {
        int steps = scenarios.stream().mapToInt(ConsoleUI::countSteps).sum();
        blank();
        rule('=');
        println(color(BOLD, "TapData L3 Integration Test")
                + "  |  " + scenarios.size() + " scenario(s)  |  " + steps + " step(s)");
        println("cases: " + scenarios.stream()
                .map(ScenarioConfig::getCaseId)
                .collect(Collectors.joining(", ")));
        rule('=');
    }

    public static void scenarioStart(int index, int total, ScenarioConfig scenario) {
        blank();
        println(color(BOLD + CYAN, "[" + index + "/" + total + "] ")
                + color(BOLD, nullSafe(scenario.getCaseId()))
                + "  " + color(DIM, "(" + nullSafe(scenario.getGroup()) + ")"));
        println("      " + nullSafe(scenario.getScenario()));
    }

    public static void scenarioEnd(ScenarioResult result) {
        int executed = result.getSteps() == null ? 0 : result.getSteps().size();
        // 控制流（if/for/switch）的实际执行步数可能超过静态估算，取二者最大值避免出现 3/1
        int totalSteps = result.getScenario() == null
                ? executed
                : Math.max(countSteps(result.getScenario()), executed);
        String status = result.isSuccess() ? color(GREEN + BOLD, "PASSED") : color(RED + BOLD, "FAILED");
        println("      " + status
                + "  |  steps " + executed + "/" + totalSteps
                + "  |  " + formatDuration(result.getDurationMs()));
    }

    // ==================== Step lifecycle ====================

    public static void stepStart(int index, int total, StepConfig step) {
        println(stepLine(index, total, step.getId(), step.getAction(), color(CYAN, "RUN")));
    }

    public static void stepEnd(int index, int total, StepResult result) {
        StepConfig step = result.getStepConfig();
        String id = step == null ? "(unknown)" : step.getId();
        String action = step == null ? "(unknown)" : step.getAction();
        String status = result.isSuccess() ? color(GREEN, "PASS") : color(RED, "FAIL");
        println(stepLine(index, total, id, action, status + "  " + formatDuration(result.getDurationMs())));
        if (!result.isSuccess() && result.getError() != null) {
            detail("-> " + ShortErrors.message(result.getError()));
        }
    }

    public static void parallelStart(int index, int total, int stepCount) {
        String range = stepCount <= 1
                ? String.format(Locale.ROOT, "%2d", index)
                : String.format(Locale.ROOT, "%2d-%2d", index, index + stepCount - 1);
        String label = "parallel block (" + stepCount + (stepCount == 1 ? " step)" : " steps)");
        println(String.format(Locale.ROOT, "  [%s/%2d] %-28s %-24s %s",
                range, total, label, "", color(CYAN, "RUN")));
    }

    public static void heartbeat(int index, int total, String stepId, long elapsedMs) {
        println(stepLine(index, total, stepId, "", color(DIM, "running " + formatDuration(elapsedMs))));
    }

    // ==================== Key events ====================

    public static void event(String label, String detail) {
        println(String.format(Locale.ROOT, "  %-10s %s", label, detail));
    }

    public static void warn(String message) {
        println("  " + color(YELLOW, "WARN") + "  " + message);
    }

    public static void error(String message) {
        println("  " + color(RED, "ERROR") + " " + message);
    }

    public static void detail(String message) {
        println("             " + color(DIM, message));
    }

    // ==================== Final summary ====================

    public static void summary(List<ScenarioResult> results, List<Path> reports) {
        long passed = results.stream().filter(ScenarioResult::isSuccess).count();
        long failed = results.size() - passed;
        double passRate = results.isEmpty() ? 0.0 : passed * 100.0 / results.size();

        blank();
        rule('=');
        println(String.format(Locale.ROOT, "%s  %d passed  %d failed  %d total  |  pass rate %.1f%%",
                color(BOLD, "SUMMARY"), passed, failed, results.size(), passRate));
        for (ScenarioResult result : results) {
            ScenarioConfig scenario = result.getScenario();
            String status = result.isSuccess() ? color(GREEN, "PASS") : color(RED, "FAIL");
            println(String.format(Locale.ROOT, "  %s  %-30s %8s  %s",
                    status,
                    clip(scenario == null ? "(unknown)" : nullSafe(scenario.getCaseId()), 30),
                    formatDuration(result.getDurationMs()),
                    color(DIM, scenario == null ? "" : nullSafe(scenario.getScenario()))));
        }
        if (reports != null && !reports.isEmpty()) {
            println("  " + color(BOLD, "Reports"));
            for (Path report : reports) {
                println("    " + report.toAbsolutePath());
            }
        }
        rule('=');
    }

    // ==================== Helpers ====================

    private static String stepLine(int index, int total, String id, String action, String suffix) {
        return String.format(Locale.ROOT, "  [%2d/%2d] %-28s %-24s %s",
                index, total, clip(nullSafe(id), STEP_ID_WIDTH), clip(nullSafe(action), ACTION_WIDTH), suffix);
    }

    private static void rule(char c) {
        println(String.valueOf(c).repeat(RULE_WIDTH));
    }

    private static void blank() {
        println("");
    }

    private static void println(String line) {
        synchronized (LOCK) {
            System.out.println("[L3] " + line);
        }
    }

    private static String clip(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width - 3) + "...";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        long totalSeconds = ms / 1000;
        return String.format(Locale.ROOT, "%dm%02ds", totalSeconds / 60, totalSeconds % 60);
    }

    private static int countSteps(ScenarioConfig scenario) {
        if (scenario == null || scenario.getBlocks() == null) return 0;
        return ScenarioBlocks.countSteps(scenario.getBlocks());
    }

    private static String color(String code, String text) {
        return COLOR ? code + text + RESET : text;
    }

    private static boolean detectColor() {
        String mode = System.getProperty("l3.color", System.getenv().getOrDefault("L3_COLOR", "auto"));
        if ("always".equalsIgnoreCase(mode) || "true".equalsIgnoreCase(mode)) return true;
        if ("never".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode)) return false;
        if (System.getenv("NO_COLOR") != null) return false;
        if ("dumb".equalsIgnoreCase(System.getenv().getOrDefault("TERM", ""))) return false;
        return System.console() != null;
    }

    /** Keeps console failure lines on one readable line. */
    private static final class ShortErrors {
        private ShortErrors() {
        }

        static String message(Throwable error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) return error.getClass().getSimpleName();
            int newline = message.indexOf('\n');
            return newline >= 0 ? message.substring(0, newline) : message;
        }
    }
}
