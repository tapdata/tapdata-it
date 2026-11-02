package io.tapdata.it.l3.core.dsl;

import io.tapdata.it.l3.common.ParallelBlock;
import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.exception.DslParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSL YAML → ScenarioConfig parser.
 * <p>
 * Handles three step-level DSL constructs:
 * <ul>
 *   <li>Sequential step: {@code - id: ... action: ... inputs: ...}</li>
 *   <li>Parallel block: {@code - parallel: [ ... ]}</li>
 * </ul>
 * Foreach (C2) and other control structures are added incrementally.
 * <p>
 * Performs the following validations during parsing:
 * <ol>
 *   <li>Required fields present (case_id, at least one step)</li>
 *   <li>Step ids are unique within a scenario</li>
 *   <li>Parallel block independence — steps inside same block must NOT cross-reference outputs</li>
 * </ol>
 */
@Component
public class DslParser {

    private static final Logger log = LoggerFactory.getLogger(DslParser.class);
    private static final Set<String> TIMEOUT_REQUIRED_ACTIONS = Set.of(
            "tm_api_wait_task_status",
            "assert_jdbc_data", "assert_jdbc_row_count", "assert_jdbc_data_consistency",
            "assert_mongo_data", "assert_mongo_count", "assert_mongo_consistency",
            "system_wait_recovery"
    );

    /** Matches #steps['STEP_ID'].outputs['KEY'] patterns — used for cross-step reference validation. */
    private static final Pattern STEP_REF_PATTERN =
            Pattern.compile("#steps\\['([^']+)'\\]\\.outputs\\['([^']+)'\\]");

    private final Yaml yaml = new Yaml();

    // ==================== Public API ====================

    public ScenarioConfig parse(Path yamlFile) {
        log.info("[DSL] Parsing {}", yamlFile);
        try (InputStream is = Files.newInputStream(yamlFile)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yaml.load(is);
            return parseRoot(root, yamlFile.getFileName().toString());
        } catch (IOException e) {
            throw new DslParseException("Failed to read DSL file: " + yamlFile, e);
        }
    }

    public List<ScenarioConfig> parseDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new DslParseException("Not a directory: " + dir);
        }
        List<ScenarioConfig> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .toList();
            if (files.isEmpty()) {
                log.warn("[DSL] No .yaml files found in {}", dir);
            }
            for (Path f : files) {
                result.add(parse(f));
            }
        } catch (IOException e) {
            throw new DslParseException("Failed to scan directory: " + dir, e);
        }
        return result;
    }

    /**
     * Safe variant — files that fail to parse are skipped with a warning log instead of aborting.
     * Useful for CLI run command where a directory may contain intentionally broken test cases.
     */
    public List<ScenarioConfig> parseDirectorySafe(Path dir) {
        List<ScenarioConfig> result = new ArrayList<>();
        try {
            result.addAll(parseDirectory(dir));
        } catch (DslParseException ignored) {
            // Fall through — use per-file safe parsing below
        }
        // Fallback: try each file individually
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .toList();
            for (Path f : files) {
                try {
                    result.add(parse(f));
                } catch (DslParseException e) {
                    log.warn("[DSL] Skipping {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[DSL] Failed to scan directory {}: {}", dir, e.getMessage());
        }
        return result;
    }

    // ==================== Root parsing ====================

    @SuppressWarnings("unchecked")
    private ScenarioConfig parseRoot(Map<String, Object> root, String source) {
        if (root == null) {
            throw new DslParseException("Empty or null YAML content in " + source);
        }

        String caseId = requiredString(root, "case_id", source);
        String scenario = optionalString(root, "scenario", caseId);
        String group = optionalString(root, "group", "functional");
        String description = optionalString(root, "description", "");

        Map<String, Object> variables = (Map<String, Object>) root.get("variables");
        if (variables == null) variables = new LinkedHashMap<>();

        Object stepsRaw = root.get("steps");
        if (!(stepsRaw instanceof List<?> stepList) || stepList.isEmpty()) {
            throw new DslParseException(String.format(
                    "Scenario '%s' (%s): 'steps' must be a non-empty list", caseId, source));
        }

        List<Object> blocks = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (Object item : stepList) {
            if (item instanceof Map<?, ?> itemMap) {
                // Detect parallel block vs sequential step
                if (itemMap.containsKey("parallel")) {
                    ParallelBlock block = parseParallelBlock(itemMap, caseId, seenIds);
                    blocks.add(block);
                } else {
                    StepConfig step = parseStep((Map<String, Object>) itemMap, caseId, seenIds);
                    blocks.add(step);
                }
            } else {
                throw new DslParseException(String.format(
                        "Scenario '%s': step item must be a YAML mapping, got: %s", caseId, item));
            }
        }

        return ScenarioConfig.builder()
                .caseId(caseId)
                .scenario(scenario)
                .group(group)
                .description(description)
                .variables(variables)
                .blocks(blocks)
                .build();
    }

    // ==================== Step parsing ====================

    private StepConfig parseStep(Map<String, Object> map, String caseId, Set<String> seenIds) {
        String id = requiredString(map, "id", caseId);
        String action = requiredString(map, "action", caseId);

        // Uniqueness check
        if (!seenIds.add(id)) {
            throw new DslParseException(String.format(
                    "Scenario '%s': step id '%s' is duplicated", caseId, id));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) map.get("inputs");
        if (inputs == null) inputs = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        List<String> outputs = (List<String>) map.get("outputs");
        if (outputs == null) outputs = List.of();

        int timeoutSeconds = 0;
        Object timeoutRaw = map.get("timeout_seconds");
        if (timeoutRaw instanceof Number n) timeoutSeconds = n.intValue();

        boolean keepAlive = false;
        Object keepAliveRaw = map.get("keep_alive");
        if (keepAliveRaw instanceof Boolean b) keepAlive = b;

        // Lifecycle hooks — reserved for future execution engine
        @SuppressWarnings("unchecked")
        Map<String, Object> hooks = (Map<String, Object>) map.get("hooks");

        // extra_deps — user-specified Maven coords for runtime driver loading
        List<String> extraDeps = List.of();
        Object depsRaw = map.get("extra_deps");
        if (depsRaw instanceof List<?> depsList) {
            extraDeps = depsList.stream().map(Object::toString).toList();
        }

        // use — 显式指定 step 实现的 Maven 坐标（覆盖默认注册表版本）
        String use = optionalString(map, "use", null);

        // Validate: timeout_seconds required for assert_* and wait_* actions
        if (TIMEOUT_REQUIRED_ACTIONS.contains(action) && timeoutSeconds <= 0) {
            throw new DslParseException(String.format(
                    "Invalid DSL: Step '%s' (action=%s) requires 'timeout_seconds' parameter",
                    id, action));
        }

        return StepConfig.builder()
                .id(id)
                .action(action)
                .inputs(inputs)
                .outputs(outputs)
                .timeoutSeconds(timeoutSeconds)
                .keepAlive(keepAlive)
                .hooks(hooks)
                .extraDeps(extraDeps)
                .use(use)
                .build();
    }

    // ==================== Parallel block parsing ====================

    @SuppressWarnings("unchecked")
    private ParallelBlock parseParallelBlock(Map<?, ?> itemMap, String caseId, Set<String> seenIds) {
        Object parallelRaw = itemMap.get("parallel");
        if (!(parallelRaw instanceof List<?> rawSteps)) {
            throw new DslParseException(String.format(
                    "Scenario '%s': 'parallel:' must be followed by a YAML list", caseId));
        }

        List<StepConfig> steps = new ArrayList<>();
        Set<String> parallelStepIds = new HashSet<>();

        for (Object item : rawSteps) {
            if (!(item instanceof Map<?, ?> sm)) {
                throw new DslParseException(String.format(
                        "Scenario '%s': step inside parallel must be a YAML mapping", caseId));
            }
            StepConfig step = parseStep((Map<String, Object>) sm, caseId, seenIds);
            steps.add(step);
            parallelStepIds.add(step.getId());
        }

        // === Parallel independence hard rule ===
        // No step inside this parallel block may reference outputs of another step
        // that is ALSO inside this block (cross-step dependency → must be sequential).
        for (StepConfig step : steps) {
            Set<String> referenced = extractStepReferences(step.getInputs());
            referenced.retainAll(parallelStepIds);
            if (!referenced.isEmpty()) {
                throw new DslParseException(String.format(
                        "Invalid DSL: Step '%s' inside parallel block references "
                                + "outputs of Step(s) %s which are also inside the same parallel block. "
                                + "Parallel steps must be independent. Use sequential steps instead.",
                        step.getId(), referenced));
            }
        }

        return ParallelBlock.builder().steps(steps).build();
    }

    // ==================== Cross-step reference extraction ====================

    /**
     * Walks all leaf values in the inputs map and extracts every {@code #steps['X'].outputs['Y']}
     * reference. Returns the set of step ids referenced.
     */
    Set<String> extractStepReferences(Map<String, Object> inputs) {
        Set<String> referenced = new HashSet<>();
        if (inputs == null) return referenced;
        collectRefs(inputs, referenced);
        return referenced;
    }

    @SuppressWarnings("unchecked")
    private void collectRefs(Object value, Set<String> referenced) {
        if (value == null) return;
        if (value instanceof String s) {
            Matcher m = STEP_REF_PATTERN.matcher(s);
            while (m.find()) {
                referenced.add(m.group(1));
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) collectRefs(v, referenced);
        } else if (value instanceof List<?> list) {
            for (Object v : list) collectRefs(v, referenced);
        }
    }

    // ==================== Utilities ====================

    private String requiredString(Map<String, Object> map, String key, String context) {
        Object v = map.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new DslParseException(String.format(
                    "Scenario '%s' (or step): required field '%s' is missing", context, key));
        }
        return v.toString();
    }

    private String optionalString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultValue;
    }
}
