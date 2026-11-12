package io.tapdata.it.l3.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for walking the recursive block tree ({@link StepConfig}, {@link ParallelBlock},
 * {@link IfBlock}, {@link ForBlock}, {@link SwitchBlock}).
 * <p>
 * Used by the runner, console renderer and CLI validate command so that all three
 * understand the same control-flow structure.
 */
public final class ScenarioBlocks {

    private ScenarioBlocks() {
    }

    /**
     * Collects every {@link StepConfig} in the block tree — including steps inside
     * nested control-flow branches and loops.
     * <p>
     * Used for dependency preloading and preflight validation, so intentionally walks
     * <b>all</b> branches even if only one will execute at runtime.
     */
    public static List<StepConfig> collectSteps(List<Object> blocks) {
        List<StepConfig> result = new ArrayList<>();
        collectSteps(blocks, result);
        return result;
    }

    private static void collectSteps(List<Object> blocks, List<StepConfig> result) {
        if (blocks == null) return;
        for (Object block : blocks) {
            if (block instanceof StepConfig step) {
                result.add(step);
            } else if (block instanceof ParallelBlock parallel) {
                result.addAll(parallel.getSteps());
            } else if (block instanceof IfBlock ifBlock) {
                collectSteps(ifBlock.getThenBlocks(), result);
                collectSteps(ifBlock.getElseBlocks(), result);
            } else if (block instanceof ForBlock forBlock) {
                collectSteps(forBlock.getBlocks(), result);
            } else if (block instanceof WhileBlock whileBlock) {
                collectSteps(whileBlock.getBlocks(), result);
            } else if (block instanceof SwitchBlock switchBlock) {
                if (switchBlock.getCases() != null) {
                    switchBlock.getCases().values().forEach(caseBlocks -> collectSteps(caseBlocks, result));
                }
                collectSteps(switchBlock.getDefaultBlocks(), result);
            }
        }
    }

    /**
     * Estimated number of steps in the block tree — used for progress display only.
     * <p>
     * Control-flow branches are counted as their largest possible branch
     * ({@code max(then, else)} for {@code if}; largest case/default for {@code switch};
     * one iteration for {@code for}). The runner adjusts the total at runtime once
     * the actual branch / iteration count is known.
     */
    public static int countSteps(List<Object> blocks) {
        if (blocks == null) return 0;
        int count = 0;
        for (Object block : blocks) {
            if (block instanceof StepConfig) {
                count++;
            } else if (block instanceof ParallelBlock parallel) {
                count += parallel.getSteps().size();
            } else if (block instanceof IfBlock ifBlock) {
                count += Math.max(countSteps(ifBlock.getThenBlocks()), countSteps(ifBlock.getElseBlocks()));
            } else if (block instanceof ForBlock forBlock) {
                count += countSteps(forBlock.getBlocks());
            } else if (block instanceof WhileBlock whileBlock) {
                count += countSteps(whileBlock.getBlocks());
            } else if (block instanceof SwitchBlock switchBlock) {
                int max = countSteps(switchBlock.getDefaultBlocks());
                if (switchBlock.getCases() != null) {
                    for (List<Object> caseBlocks : switchBlock.getCases().values()) {
                        max = Math.max(max, countSteps(caseBlocks));
                    }
                }
                count += max;
            }
        }
        return count;
    }
}
