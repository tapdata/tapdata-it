package io.tapdata.it.l3.cli;

import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.core.L3CoreApplication;
import io.tapdata.it.l3.core.dsl.DslParser;
import io.tapdata.it.l3.core.plugin.PluginCenter;
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.core.runner.ScenarioRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TapData L3 CLI — Picocli command-line wrapper around the Spring Boot core.
 * <p>
 * This is the entry point packaged into the executable Fat Jar.
 * Subcommands:
 * <ul>
 *   <li>{@code run} — execute scenario YAML files from a directory</li>
 *   <li>{@code describe} — list all registered step handlers</li>
 *   <li>{@code validate} — parse YAML files without executing (DSL syntax check)</li>
 * </ul>
 */
@Command(
        name = "tapdata-l3",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "TapData L3 Integration Test Framework CLI"
)
public class L3CliApplication implements Runnable {

    @Option(names = {"--debug"}, description = "Enable DEBUG logging")
    boolean debug;

    @Option(names = {"--log-file"}, description = "Append logs to this file path")
    Path logFile;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // ==================== run subcommand ====================

    @Command(name = "run", description = "Execute L3 scenario YAML files")
    int run(
            @Option(names = {"--dir"}, required = true, description = "Directory containing .yaml case files")
            String dir,
            @Option(names = {"--group"}, description = "Filter scenarios by group (functional|destructive|stability)")
            String group,
            @Option(names = {"--case-ids"}, description = "Comma-separated case_id filter (e.g. L3-F-01,L3-F-02)")
            String caseIds,
            @Option(names = {"--output"}, description = "Output directory for reports and logs")
            String outputDir,
            @Option(names = {"--nexus-repo"}, description = "Nexus repository URL for plugin download")
            String nexusRepo
    ) {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(L3CoreApplication.class)) {
            DslParser parser = ctx.getBean(DslParser.class);
            ScenarioRunner runner = ctx.getBean(ScenarioRunner.class);

            Path dirPath = Paths.get(dir);
            // Parse all yaml files in directory — files with parse errors are skipped safely
            List<ScenarioConfig> scenarios = parser.parseDirectorySafe(dirPath);

            // Filter by group and case_ids
            if (group != null) {
                scenarios = scenarios.stream()
                        .filter(s -> group.equalsIgnoreCase(s.getGroup()))
                        .toList();
            }
            if (caseIds != null) {
                List<String> ids = List.of(caseIds.split(","));
                scenarios = scenarios.stream()
                        .filter(s -> ids.contains(s.getCaseId()))
                        .toList();
            }

            if (scenarios.isEmpty()) {
                System.out.println("[L3] No scenarios to run after filtering.");
                return 0;
            }

            System.out.printf("[L3] Will execute %d scenario(s): %s%n",
                    scenarios.size(),
                    scenarios.stream().map(s -> s.getCaseId() + " (" + s.getScenario() + ")")
                            .collect(Collectors.joining(", ")));

            List<ScenarioResult> results = new ArrayList<>();
            int passed = 0, failed = 0;

            for (ScenarioConfig scenario : scenarios) {
                ScenarioResult result = runner.execute(scenario);
                results.add(result);
                if (result.isSuccess()) passed++; else failed++;
            }

            // Summary
            System.out.println();
            System.out.println("================================================");
            System.out.printf("[L3] Execution Summary: %d passed, %d failed, %d total%n",
                    passed, failed, scenarios.size());
            System.out.println("================================================");

            return failed == 0 ? 0 : 1;
        } catch (Exception e) {
            System.err.println("[L3] Fatal error: " + e.getMessage());
            e.printStackTrace();
            return 2;
        }
    }

    // ==================== describe subcommand ====================

    @Command(name = "describe", description = "List all registered step handlers")
    int describe(
            @Option(names = {"--action"}, description = "Describe a specific action")
            String action
    ) {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(L3CoreApplication.class, args)) {
            PluginCenter pluginCenter = ctx.getBean(PluginCenter.class);
            Map<String, ?> handlers = pluginCenter.getAllActions();

            System.out.println("Registered L3 step handlers:");
            System.out.println("─────────────────────────────────────────────");
            handlers.forEach((name, handler) -> {
                System.out.printf("  %-40s %s%n", name, handler.getClass().getName());
            });
            return 0;
        } catch (Exception e) {
            System.err.println("[L3] Fatal error: " + e.getMessage());
            return 2;
        }
    }

    // ==================== validate subcommand ====================

    @Command(name = "validate", description = "Validate DSL YAML files without executing")
    int validate(
            @Option(names = {"--dir"}, required = true, description = "Directory containing .yaml case files")
            String dir
    ) {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(L3CoreApplication.class, args)) {
            DslParser parser = ctx.getBean(DslParser.class);

            Path dirPath = Paths.get(dir);
            List<ScenarioConfig> scenarios = parser.parseDirectory(dirPath);

            System.out.printf("[L3] Validated %d scenario(s) successfully:%n", scenarios.size());
            for (ScenarioConfig s : scenarios) {
                System.out.printf("  ✅ %s: %s (group=%s, blocks=%d)%n",
                        s.getCaseId(), s.getScenario(), s.getGroup(), s.getBlocks().size());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("[L3] Validation failed: " + e.getMessage());
            return 2;
        }
    }

    // ==================== Default (no subcommand) ====================

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    // Capture args for inner @Command methods
    private static String[] args;

    public static void main(String[] argv) {
        args = argv;
        CommandLine.run(new L3CliApplication(), argv);
    }
}
