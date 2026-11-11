package io.tapdata.it.l3.cli;

import io.tapdata.it.l3.common.ScenarioBlocks;
import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.core.L3CoreApplication;
import io.tapdata.it.l3.core.dsl.DslParser;
import io.tapdata.it.l3.core.plugin.PluginCenter;
import io.tapdata.it.l3.core.console.ConsoleUI;
import io.tapdata.it.l3.core.plugin.PluginCenter.PluginLoadException;
import io.tapdata.it.l3.core.report.JsonReportGenerator;
import io.tapdata.it.l3.core.report.JUnitXmlReportGenerator;
import io.tapdata.it.l3.core.report.HtmlReportGenerator;
import io.tapdata.it.l3.core.report.ReportWriter;
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.core.runner.ScenarioRunner;
import io.tapdata.it.l3.plugin.ActionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

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
 *
 * <h3>Maven 仓库配置优先级（高→低）</h3>
 * <ol>
 *   <li>CLI 子命令显式传参 —— {@code --remote-repos} / {@code --local-repo-dir} / {@code --plugins-dir}</li>
 *   <li>全局选项（任意子命令前）—— {@code --l3.plugin.remote-repos=...} 等</li>
 *   <li>JVM System Property —— {@code -Dl3.plugin.remote-repos=...}</li>
 *   <li>{@code ~/.tap-l3/application.properties} / 项目目录 {@code application.properties} / YAML</li>
 *   <li>内置默认 —— 本地仓库 {@code ~/.m2/repository}，远端仓库只含 Maven Central</li>
 * </ol>
 *
 * <h3>多仓库示例</h3>
 * <pre>
 *   # 单个 Nexus 私服（逗号分隔 = CLI 层面的 list 传值）
 *   java -jar l3-cli.jar run --dir cases \
 *     --nexus-repo=https://nexus.internal.com/repository/maven-public
 *
 *   # 多个远端仓库 —— 先 Nexus 后 Maven Central 兜底
 *   java -jar l3-cli.jar run --dir cases \
 *     --remote-repos=https://nexus.internal.com/repository/public,https://repo.maven.apache.org/maven2/
 *
 *   # 也可以直接用 Spring Boot 属性名（绕过 Picocli，直接进 Spring Environment）
 *   java -jar l3-cli.jar run --dir cases \
 *     --l3.plugin.remote-repos=https://a.com,https://b.com \
 *     --l3.plugin.local-repo-dir=/tmp/l3-m2
 * </pre>
 */
@Command(
        name = "tapdata-l3",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "TapData L3 Integration Test Framework CLI"
)
public class L3CliApplication implements Runnable {

    // ===== 全局 Maven / Runtime 选项（通过 @Mixin 注入到每个子命令方法） =====
    // Picocli 内部 @Command 方法不会继承父类 @Option —— 用 Mixin 显式注入。

    /** Maven 全局选项 —— 每个子命令都可以声明这些选项。 */
    static class MavenRepoOptions {
        @Option(names = {"--remote-repos"},
                description = "Comma-separated list of remote Maven repo URLs for step/download resolution. "
                        + "Override default (Maven Central). Prefer --nexus-repo for single-repo shorthand.")
        String remoteRepos;

        @Option(names = {"--nexus-repo"},
                description = "Single Nexus/私服 URL for MavenPluginResolver 下载（会追加到 Maven Central 之前）。"
                        + "Shortcut for --remote-repos。")
        String nexusRepo;

        @Option(names = {"--local-repo-dir"},
                description = "Local Maven repository path for artifact caching (default ~/.m2/repository).")
        String localRepoDir;

        @Option(names = {"--plugins-dir"},
                description = "PF4J plugins directory (default ~/.tap-l3/plugins).")
        String pluginsDir;

        @Option(names = {"--debug"}, description = "Enable DEBUG logging")
        boolean debug;

        @Option(names = {"--log-file"}, description = "Append logs to this file path")
        Path logFile;
    }

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // ==================== run subcommand ====================

    @Command(name = "run", description = "Execute L3 scenario YAML files")
    int run(
            @Mixin MavenRepoOptions mavenOpts,
            @Option(names = {"--dir"}, required = true, description = "Directory containing .yaml case files")
            String dir,
            @Option(names = {"--group"}, description = "Filter scenarios by group (functional|destructive|stability)")
            String group,
            @Option(names = {"--case-ids"}, description = "Comma-separated case_id filter (e.g. L3-F-01,L3-F-02)")
            String caseIds,
            @Option(names = {"--output"}, description = "Output directory for reports and logs")
            String outputDir
    ) {
        String[] springArgs = buildSpringBootArgs(mavenOpts);
        try (ConfigurableApplicationContext ctx = SpringApplication.run(L3CoreApplication.class, springArgs)) {
            DslParser parser = ctx.getBean(DslParser.class);
            ScenarioRunner runner = ctx.getBean(ScenarioRunner.class);

            Path dirPath = Paths.get(dir);
            List<ScenarioConfig> scenarios = parser.parseDirectorySafe(dirPath);

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
                ConsoleUI.warn("no scenarios to run after filtering");
                return 0;
            }

            ConsoleUI.banner(scenarios);

            List<ScenarioResult> results = new ArrayList<>();
            int scenarioIndex = 0;
            for (ScenarioConfig scenario : scenarios) {
                ConsoleUI.scenarioStart(++scenarioIndex, scenarios.size(), scenario);
                ScenarioResult result = runner.execute(scenario);
                ConsoleUI.scenarioEnd(result);
                results.add(result);
            }

            // ---- 三格式测试报告（JSON / JUnit XML / HTML）----
            Path reportDir = outputDir != null && !outputDir.isBlank()
                    ? Paths.get(outputDir)
                    : Paths.get("reports");
            List<Path> reportFiles = new ArrayList<>();
            try {
                ReportWriter.writeAll(results, reportDir);
                reportFiles.add(reportDir.resolve(JsonReportGenerator.FILE_NAME));
                reportFiles.add(reportDir.resolve(JUnitXmlReportGenerator.FILE_NAME));
                reportFiles.add(reportDir.resolve(HtmlReportGenerator.FILE_NAME));
            } catch (Exception reportError) {
                ConsoleUI.warn("report generation failed: " + reportError.getMessage());
            }

            ConsoleUI.summary(results, reportFiles);
            return results.stream().allMatch(ScenarioResult::isSuccess) ? 0 : 1;
        } catch (Exception e) {
            System.err.println("[L3] Fatal error: " + e.getMessage());
            return 2;
        }
    }

    // ==================== describe subcommand ====================

    @Command(name = "describe", description = "List all registered step handlers")
    int describe(
            @Mixin MavenRepoOptions mavenOpts,
            @Option(names = {"--action"}, description = "Describe a specific action")
            String action
    ) {
        String[] springArgs = buildSpringBootArgs(mavenOpts);
        try (ConfigurableApplicationContext ctx = SpringApplication.run(L3CoreApplication.class, springArgs)) {
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

    @Command(name = "validate", description = "Validate DSL YAML files — syntax + dependency preload + handler config — without executing steps")
    int validate(
            @Mixin MavenRepoOptions mavenOpts,
            @Option(names = {"--dir"}, required = true, description = "Directory containing .yaml case files")
            String dir
    ) {
        String[] springArgs = buildSpringBootArgs(mavenOpts);
        try (ConfigurableApplicationContext ctx = SpringApplication.run(L3CoreApplication.class, springArgs)) {
            DslParser parser = ctx.getBean(DslParser.class);
            PluginCenter pluginCenter = ctx.getBean(PluginCenter.class);

            Path dirPath = Paths.get(dir);

            // === Stage 1: DSL 语法校验 ===
            List<ScenarioConfig> scenarios = parser.parseDirectorySafe(dirPath);
            if (scenarios.isEmpty()) {
                System.err.println("[L3] ❌ No valid scenario files found in " + dir);
                return 1;
            }
            System.out.printf("\n[L3] Parsed %d scenario(s), starting full validation...%n%n", scenarios.size());

            // === Stage 2: 对每个 scenario 做依赖预加载 + handler.validate() + 框架级校验 ===
            int totalErrors = 0;
            int totalSteps = 0;
            int passed = 0;
            int scenarioIdx = 0;
            int parallelism = Math.min(Runtime.getRuntime().availableProcessors(), 8);
            ExecutorService validatePool = new ThreadPoolExecutor(
                    parallelism, parallelism, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    r -> { Thread t = new Thread(r, "l3-validate"); t.setDaemon(true); return t; });

            try {
                for (ScenarioConfig scenario : scenarios) {
                    scenarioIdx++;
                    List<StepConfig> allSteps = collectAllStepsLocal(scenario);
                    totalSteps += allSteps.size();

                    System.out.printf("─── Scenario %d/%d: %s (%s) ───%n",
                            scenarioIdx, scenarios.size(), scenario.getCaseId(), scenario.getScenario());

                    // 2a. 依赖预加载（统一下载所有 jar + runtime deps + 传递依赖）
                    boolean preloadOk = true;
                    try {
                        pluginCenter.preloadDependencies(allSteps);
                    } catch (PluginLoadException ple) {
                        System.err.println("\n  ❌ Preload FAILED: " + ple.getMessage());
                        totalErrors++;
                        preloadOk = false;
                    }

                    if (!preloadOk) {
                        // 依赖加载失败，跳过当前 scenario 的后续校验，继续下一个
                        continue;
                    }

                    // 2b. 并发 handler.validate() + 框架级类型校验
                    List<CompletableFuture<StepValidateResult>> futures = new ArrayList<>();
                    for (StepConfig step : allSteps) {
                        futures.add(CompletableFuture.supplyAsync(
                                () -> validateOneStepLocal(pluginCenter, step), validatePool));
                    }

                    List<StepValidateResult> results;
                    try {
                        results = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                .thenApply(v -> futures.stream().map(CompletableFuture::join).toList())
                                .get(60, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        System.err.println("  ❌ Validation timed out: " + e.getMessage());
                        totalErrors += allSteps.size();
                        continue;
                    }

                    // 2c. 打印校验结果
                    int scenarioErrors = 0;
                    for (StepValidateResult r : results) {
                        if (r.errors.isEmpty()) {
                            System.out.printf("  ✅ step '%s' (%s) — OK%n", r.stepId, r.action);
                        } else {
                            scenarioErrors++;
                            System.out.printf("  ❌ step '%s' (%s) — %d error(s):%n",
                                    r.stepId, r.action, r.errors.size());
                            for (int i = 0; i < r.errors.size(); i++) {
                                System.out.printf("      [E%d] %s%n", i + 1, r.errors.get(i));
                            }
                        }
                    }

                    totalErrors += scenarioErrors;
                    if (scenarioErrors == 0) passed++;
                    System.out.println();
                }
            } finally {
                validatePool.shutdown();
            }

            // === 最终汇总 ===
            System.out.println("================================================================");
            System.out.printf("[L3] Validation Summary: %d scenario(s), %d step(s) → %d error(s)%n",
                    scenarios.size(), totalSteps, totalErrors);
            if (totalErrors == 0) {
                System.out.printf("  ✅ All %d scenario(s) passed validation — safe to run.%n", passed);
                System.out.println("================================================================");
                return 0;
            } else {
                System.out.printf("  ❌ %d scenario(s) have errors — fix configuration before running.%n",
                        scenarios.size() - passed);
                System.out.println("================================================================");
                return 1;
            }
        } catch (Exception e) {
            System.err.println("[L3] Validation fatal error: " + e.getMessage());
            return 2;
        }
    }

    // ==================== validate 子命令辅助方法（CLI 本地版，避免依赖 ScenarioRunner private 方法） ====================

    /** 从 scenario 所有 blocks 收集扁平的 StepConfig 列表（含 if / for / switch 嵌套分支）。 */
    private static List<StepConfig> collectAllStepsLocal(ScenarioConfig scenario) {
        return ScenarioBlocks.collectSteps(scenario.getBlocks());
    }

    /** CLI 版单 step preflight 校验 —— 框架级 + handler 级。 */
    private static StepValidateResult validateOneStepLocal(PluginCenter pluginCenter, StepConfig step) {
        List<String> errors = new ArrayList<>();

        // 框架级: action 存在性
        if (!pluginCenter.hasAction(step.getAction())
                && !pluginCenter.isInDefaultRegistry(step.getAction())
                && (step.getUse() == null || step.getUse().isBlank())) {
            errors.add("[E-action] action '" + step.getAction()
                    + "' 既不在内置 steps 也不在默认注册表，且 DSL 未指定 use 坐标 —— "
                    + "请检查 action 拼写、通过 steps-registry.yaml 注册，或在 DSL 加 use: groupId:artifactId:version");
        }

        // 框架级: inputs 值类型
        Map<String, Object> inputs = step.getInputs();
        if (inputs != null) {
            for (Map.Entry<String, Object> e : inputs.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (!(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean)
                        && !(v instanceof Map) && !(v instanceof List)) {
                    errors.add("[E-type] inputs." + e.getKey() + " 类型不合法: expected String/Number/Boolean/Map/List, got "
                            + v.getClass().getSimpleName() + " (" + v + ")");
                }
            }
        }

        // handler 级: 调 ActionHandler.validate()
        try {
            ActionHandler handler = pluginCenter.getAction(step.getAction());
            List<String> handlerErrors = handler.validate(step);
            if (handlerErrors != null && !handlerErrors.isEmpty()) {
                errors.addAll(handlerErrors);
            }
        } catch (IllegalArgumentException ignored) {
            // action 不在 registry —— 上面 action 存在性校验已覆盖
        } catch (Exception ex) {
            errors.add("[E-handler] handler.validate() 抛异常: " + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage());
        }

        return new StepValidateResult(step.getId(), step.getAction(), errors);
    }

    /** CLI 版单 step 校验结果。 */
    private static class StepValidateResult {
        final String stepId;
        final String action;
        final List<String> errors;
        StepValidateResult(String stepId, String action, List<String> errors) {
            this.stepId = stepId;
            this.action = action;
            this.errors = errors;
        }
    }

    // ==================== Default (no subcommand) ====================

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    // ==================== Spring Boot 属性桥接 ====================

    /**
     * 把 Picocli 捕获的全局选项（--remote-repos / --nexus-repo / --local-repo-dir / --plugins-dir / --debug）
     * 转换成 Spring Boot 能识别的 {@code --property=value} 形式，追加到 SpringApplication.run() 的 args。
     * <p>
     * 同时追加从 main() 预处理挑出来的 Spring 属性（如 {@code --l3.plugin.remote-repos=...}）——
     * 这些参数绕过 Picocli 直接透传给 Spring Boot。
     * <p>
     * 优先级：
     * <ol>
     *   <li>{@code --remote-repos}（显式逗号分隔）—— 覆盖一切</li>
     *   <li>{@code --nexus-repo} —— 追加到 Maven Central 之前</li>
     *   <li>{@code --l3.plugin.remote-repos=...} —— 由 main() 预处理透传，优先级同上但走 Spring 绑定</li>
     *   <li>不设置 —— Spring Boot 走 application.properties / 默认值</li>
     * </ol>
     */
    private String[] buildSpringBootArgs(MavenRepoOptions opts) {
        List<String> result = new ArrayList<>();

        // 1. debug
        if (opts.debug) result.add("--debug=true");

        // 2. Maven 仓库配置 —— 核心
        if (opts.remoteRepos != null && !opts.remoteRepos.isBlank()) {
            // 用户显式传了 --remote-repos → 直接用（逗号分隔）
            result.add("--l3.plugin.remote-repos=" + opts.remoteRepos.trim());
        } else if (opts.nexusRepo != null && !opts.nexusRepo.isBlank()) {
            // 只传了 --nexus-repo → 追加到默认 Central 之前
            result.add("--l3.plugin.remote-repos=" + opts.nexusRepo.trim()
                    + ",https://repo.maven.apache.org/maven2/");
        }

        if (opts.localRepoDir != null && !opts.localRepoDir.isBlank()) {
            result.add("--l3.plugin.local-repo-dir=" + opts.localRepoDir.trim());
        }
        if (opts.pluginsDir != null && !opts.pluginsDir.isBlank()) {
            result.add("--l3.plugin.plugins-dir=" + opts.pluginsDir.trim());
        }

        // 3. 追加 main() 预处理挑出来的 Spring 属性形式参数（绕过 Picocli 的那部分）
        //    这些参数已经是 --key=value 形式，直接追加
        result.addAll(springBootPassthroughArgs);

        return result.toArray(new String[0]);
    }

    /**
     * main() 预处理时挑出来的 Spring 属性形式参数（{@code --l3.plugin.*} / {@code --debug} 等）。
     * 这些参数绕过 Picocli 直接透传给 Spring Boot ApplicationEnvironment。
     */
    private static List<String> springBootPassthroughArgs = List.of();

    public static void main(String[] argv) {
        // 0. 自动检测 HTTP_PROXY / https_proxy 环境变量 —— Java 不会自动读，需要手动设置
        //    这是内网服务器常见配置，curl 能自动走代理但 Java 不能。
        for (String env : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
            String v = System.getenv(env);
            if (v == null || v.isBlank()) continue;
            try {
                // 格式: http://host:port 或 host:port
                String url = v.contains("://") ? v : "http://" + v;
                URL u = new URL(url);
                String host = u.getHost();
                int port = u.getPort();
                if (host != null && port > 0) {
                    if (env.toLowerCase().contains("https")) {
                        System.setProperty("https.proxyHost", host);
                        System.setProperty("https.proxyPort", String.valueOf(port));
                    } else {
                        System.setProperty("http.proxyHost", host);
                        System.setProperty("http.proxyPort", String.valueOf(port));
                    }
                }
            } catch (Exception ignored) { }
        }
        // no_proxy → java.net.useSystemProxies 或 socks 代理
        String noProxy = System.getenv("NO_PROXY");
        if (noProxy != null && !noProxy.isBlank()) {
            System.setProperty("http.nonProxyHosts", noProxy.replace(',', '|'));
        }

        // 1. 预处理：把 Spring 属性形式挑出来，不传给 Picocli。
        // Picocli 不认识 --l3.plugin.remote-repos=... 这种长属性名会报 "Unknown option"，
        // 所以无论这些参数出现在什么位置（全局层 or 子命令之后），
        // 一律摘出来做 passthrough，直接给 Spring Boot ApplicationEnvironment。
        List<String> passthrough = new ArrayList<>();
        List<String> picocliArgs = new ArrayList<>();

        for (String a : argv) {
            // 无论位置在哪，以下参数直接透传给 Spring Boot：
            //   - -Dxxx=yyy 形式的 JVM system property
            //   - --l3.plugin.* 属性（所有 l3.plugin 前缀的 Spring Boot 属性）
            //   - -D 开头的任何 JVM 参数
            if (a.startsWith("-D") || a.startsWith("--l3.plugin.")) {
                passthrough.add(a);
            } else {
                picocliArgs.add(a);
            }
        }

        springBootPassthroughArgs = List.copyOf(passthrough);

        int exitCode = new CommandLine(new L3CliApplication())
                .execute(picocliArgs.toArray(new String[0]));
        // 注意：不能用 CommandLine.run() —— run() 不会把子命令的 return int 转成进程 exit code。
        // execute() 会返回 exit code（0=OK, 1=业务错误/校验失败, 2=致命异常）。
        System.exit(exitCode);
    }
}
