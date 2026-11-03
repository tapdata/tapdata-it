package io.tapdata.it.l3.core.plugin;

import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.ActionHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * 插件中心：tapdata-l3-core 对外暴露的**唯一 Step 获取门面**。
 * <p>
 * 所有需要获取 Step 实例的代码（StepRunner、ScenarioRunner、CLI describe 命令等）
 * 都通过本类的 {@link #getAction(String)} 方法查询——不应直接引用 PF4J、Maven Resolver
 * 或自己维护注册表。
 * <p>
 * **三类 Step 来源**（按优先级 — 后注册的覆盖先注册的）：
 * <ol>
 *   <li><b>内置 Step</b> — Spring Boot 自动扫描到的 {@code @Component} 实现，通过构造器注入
 *       {@code List<ActionHandler>} 统一注册。Dbforge/TM/Assert-JDBC step 家族都属于这类。</li>
 *   <li><b>外置 JAR Step（Maven 坐标加载）</b> — 运行时通过 {@link #loadPlugin(String)} 指定
 *       Maven 坐标，内部用 {@link MavenPluginResolver} 下载 jar + 全部传递依赖到本地缓存，
 *       创建独立 {@link URLClassLoader}，反射实例化 {@link ActionHandler} 实现类并注册。</li>
 *   <li><b>PF4J 插件目录扫描（legacy）</b> — 兼容设计文档 §7 描述的 PF4J plugin.properties 插件包
 *       （pf4j-maven-plugin 产物），自动扫描 pluginsDir 下的 {@code plugin.properties} 并注册
 *       其中 {@code @Extension} 标注的 ActionHandler。</li>
 * </ol>
 * <p>
 * **额外依赖解析**（Step.getRuntimeDependencies() + DSL extra_deps）：
 * 当 Step 执行时（或首次被场景请求时），PluginCenter 合并两处声明的 Maven 坐标，
 * 通过 {@link MavenPluginResolver} 解析后追加到 Step 所属 URLClassLoader（反射调 addURL）。
 * 内置 Step 直接拿到宿主 URLClassLoader，外置 JAR Step 拿到它自己的插件 classloader。
 *
 * @see <a href="../../../../docs/tapdata_l3-design.md">设计文档 §7 PluginCenter + PF4J</a>
 */
@Component
public class PluginCenter {

    private static final Logger log = LoggerFactory.getLogger(PluginCenter.class);

    /**
     * 统一注册表：actionName → ActionHandlerPrototype。
     * <p>
     * Prototype 模式 —— 只存 (Class, ClassLoader)，**不存实例**。每次 `getOrLoadAction` / `getAction`
     * 都通过 prototype 反射 `tryInstantiate` 返回**新实例**。
     * <p>
     * 这样 DSL 里同一个 action 被多个 step 引用时，每个 step 都拿到独立的 handler 实例，
     * 不会出现跨 step 的状态污染（连接池、临时文件句柄、计数器等）。
     *
     * @see #tryInstantiateInCL
     */
    private final Map<String, ActionHandlerPrototype> registry = new ConcurrentHashMap<>();

    /**
     * 内部 prototype 记录 — 存 (Class, ClassLoader, runtimeDeps) 元信息，让每次 getOrLoadAction
     * 都能 new 新实例。runtimeDeps 在 registerInternal 时用临时实例调 getRuntimeDependencies() 拿到。
     * 这里用 record 是因为它天然不可变，ConcurrentHashMap 里存 record 安全。
     */
    private record ActionHandlerPrototype(
            Class<? extends ActionHandler> clazz,
            ClassLoader classLoader,
            List<String> runtimeDeps   // handler 内置声明的 runtime deps（不可变副本）
    ) {}

    /** actionName → URLClassLoader，记录每个已注册 handler 所在的 classloader。兼容旧 API {@link #getClassLoaderFor}。 */
    private final Map<String, URLClassLoader> classLoaders = new ConcurrentHashMap<>();

    /**
     * Maven coordinate → URLClassLoader 缓存。
     * 同一个坐标的插件被多次 {@link #loadPlugin(String)} 时，**复用第一次创建的 URLClassLoader**。
     * 原因：一个插件 jar 可能包含多个 Action（如 dbforge step jar 里有 3 个 Action），
     * 它们应该共享同一个 ClassLoader，且同一坐标被不同场景引用时不应该重复下载 + 重复构建 ClassLoader。
     *
     * <p>缓存 key = 完整 Maven 坐标 {@code groupId:artifactId:version}。
     * 内置 Step 和 PF4J legacy 插件不进这个缓存（它们的 CL 由 Spring / PF4J 自己管理）。
     */
    private final Map<String, URLClassLoader> coordinateCache = new ConcurrentHashMap<>();

    /**
     * per-coordinate 加载锁 —— 确保同一个 Maven coordinate 在并发 loadPlugin 时只被一个线程下载+扫描一次。
     * 其他线程等持锁线程完成后复用 coordinateCache 里的结果。
     * <p>
     * 锁对象本身是短生命周期的：entry 在持锁线程完成 loadPlugin 后从 Map 里 remove，
     * 避免 Map 无限增长。
     */
    private final Map<String, Object> loadPluginLocks = new ConcurrentHashMap<>();

    /**
     * actionName → 该 action 已加载的 deps 坐标集合。
     * 用于 addRuntimeDependencies 快速判断"这些 deps 是否已经在 CL 里了"，
     * 避免同一 step 多次带相同 extra_deps 时重复 resolve + 重建 URLClassLoader。
     * <p>每次 reloadPlugin / shutdown 时同步清理。
     */
    private final Map<String, Set<String>> loadedDepsPerAction = new ConcurrentHashMap<>();

    /**
     * CLI 内置默认注册表 — actionName → Maven coordinate。
     * 从 classpath:steps-registry.yaml 加载（由构建脚本 / 打包前扫描 step 模块自动生成）。
     * <p>
     * 当 DSL step 没有显式 {@code use} 字段、且 action 未在 fat jar 内置注册时，
     * PluginCenter 会查这个注册表找坐标 → 自动 loadPlugin 下载 + 注册。
     */
    private final Map<String, String> defaultRegistry = new LinkedHashMap<>();

    /** Classpath 上默认注册表文件名 — 约定 {@code steps-registry.yaml}。 */
    public static final String REGISTRY_CLASSPATH = "steps-registry.yaml";

    /** PF4J 核心（legacy — 兼容 plugin.properties 插件包）。 */
    private final PluginManager pluginManager;

    /** Maven 依赖解析器 — JAR 插件加载 + 传递依赖 + 额外依赖解析。 */
    private final MavenPluginResolver mavenResolver;

    /** 配置。 */
    private final PluginCenterProperties properties;

    /**
     * 下载线程池 —— 用于 getOrLoadAction / loadPlugin 的并发 JAR 下载。
     * 大小 = properties.getDownloadConcurrency()，与 MavenPluginResolver.downloadPool 独立但大小相同。
     */
    private final ExecutorService actionLoadPool;

    public PluginCenter(PluginCenterProperties properties) {
        this.properties = properties;
        this.mavenResolver = new MavenPluginResolver(
                properties.getLocalRepoDir(), properties.getRemoteRepos(),
                properties.getDownloadConcurrency());

        // action 下载并发线程池 —— 与 MavenPluginResolver 独立但大小相同
        int c = Math.max(1, properties.getDownloadConcurrency());
        this.actionLoadPool = Executors.newFixedThreadPool(c, r -> {
            Thread t = new Thread(r, "l3-action-load");
            t.setDaemon(true);
            return t;
        });
        log.info("[PluginCenter] actionLoadPool created (concurrency={})", c);

        Path pluginsDir = Path.of(properties.getPluginsDir());
        try {
            java.nio.file.Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            log.warn("[PluginCenter] Failed to create plugins dir {}: {}", pluginsDir, e.getMessage());
        }

        this.pluginManager = new DefaultPluginManager(pluginsDir);
    }

    // ==================== 对外 API ====================

    /**
     * 按 action 名称获取 Step 实例 — **仅查已注册的内置 / 已加载的 step**。
     * <p>
     * 不会触发从 Maven 下载，只查 {@link #registry}。用于 describe 命令、诊断等"只读"场景。
     * 运行时 StepRunner 应使用 {@link #getOrLoadAction(String, String)} 来同时覆盖外置自动加载场景。
     *
     * @throws IllegalArgumentException 如果 action 未注册
     */
    public ActionHandler getAction(String actionName) {
        ActionHandlerPrototype proto = registry.get(actionName);
        if (proto == null) {
            throw new IllegalArgumentException(String.format(
                    "Step not found: '%s'. Available: %s | Default registry: %s",
                    actionName, registry.keySet(), defaultRegistry.keySet()));
        }
        ActionHandler handler = tryInstantiateInCL(proto);
        if (handler == null) {
            throw new IllegalStateException(String.format(
                    "Step '%s' registered but failed to instantiate (no public no-arg constructor or CL issue)",
                    actionName));
        }
        return handler;
    }

    /**
     * **核心运行时入口**：获取或懒加载一个 step。
     * <p>
     * 优先级（DSL use 和默认注册表都**强制覆盖**已注册的同名 action，以允许外置版本替换内置实现）：
     * <ol>
     *   <li>DSL 显式 {@code use} 坐标 → loadPlugin（覆盖已有）</li>
     *   <li>默认注册表 ({@code steps-registry.yaml}) → loadPlugin（覆盖已有）</li>
     *   <li>内置 / 已加载（兜底） → 直接返回</li>
     *   <li>都没有 → 抛异常</li>
     * </ol>
     *
     * @param actionName    DSL 里的 action 名
     * @param explicitCoord DSL 里 {@code use} 字段的坐标（可能为 null）
     */
    public ActionHandler getOrLoadAction(String actionName, String explicitCoord) throws PluginLoadException {
        // 1. 解析坐标：显式 use 优先 > 默认注册表
        String coordinate = null;
        if (explicitCoord != null && !explicitCoord.isBlank()) {
            coordinate = explicitCoord;
        } else if (defaultRegistry.containsKey(actionName)) {
            coordinate = defaultRegistry.get(actionName);
        }

        // 2. 有坐标 → loadPlugin（coordinateCache 命中则复用 CL 重新注册，新坐标则下载）
        //    registerInternal 内部 registry.put 会覆盖同名已有 action
        if (coordinate != null) {
            log.info("[PluginCenter] Loading step '{}' from Maven coord {} (explicit={}, registry={})",
                    actionName, coordinate, explicitCoord != null,
                    explicitCoord == null && defaultRegistry.containsKey(actionName));
            loadPlugin(coordinate);

            ActionHandlerPrototype proto = registry.get(actionName);
            if (proto == null) {
                throw new PluginLoadException(
                        "Jar " + coordinate + " loaded but still no action '" + actionName + "' registered");
            }
            ActionHandler handler = tryInstantiateInCL(proto);
            if (handler == null) {
                throw new PluginLoadException(
                        "Action '" + actionName + "' registered but failed to instantiate from CL");
            }
            return handler;
        }

        // 3. 最后回落到内置 / 已加载 — 每次 new 新实例
        ActionHandlerPrototype proto = registry.get(actionName);
        if (proto != null) {
            ActionHandler handler = tryInstantiateInCL(proto);
            if (handler != null) return handler;
            throw new PluginLoadException(
                    "Action '" + actionName + "' registered but failed to instantiate from CL");
        }

        throw new PluginLoadException(String.format(
                "Step '%s' 未找到。既不是内置 step，也没在 DSL 里指定 use，默认注册表也没有。"
                        + "可用内置 step: %s | 注册表: %s",
                actionName, registry.keySet(), defaultRegistry.keySet()));
    }

    /** 查询是否已注册某 action（内置 or 已懒加载）。 */
    public boolean hasAction(String actionName) {
        return registry.containsKey(actionName);
    }

    /** 查询某 action 是否在默认注册表里（即使还没被加载）。 */
    public boolean isInDefaultRegistry(String actionName) {
        return defaultRegistry.containsKey(actionName);
    }

    /** 获取默认注册表的**只读副本** — 供 describe 命令展示。 */
    public Map<String, String> getDefaultRegistry() {
        return Map.copyOf(defaultRegistry);
    }

    /** 列出所有已注册 Step — 返回 actionName → handler Class（prototype 元信息）。 */
    public Map<String, Class<? extends ActionHandler>> getAllActions() {
        Map<String, Class<? extends ActionHandler>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ActionHandlerPrototype> e : registry.entrySet()) {
            result.put(e.getKey(), e.getValue().clazz());
        }
        return result;
    }

    // ==================== 依赖预加载 Preflight ====================

    /**
     * 【新增】统一预加载所有 step 的 Maven 依赖（jar + runtime deps + 传递依赖），打印进度，
     * 避免运行到一半才因网络问题或 artifact 缺失失败。
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>收集所有 unique action + 每个 action 的 merged extraDeps + 显式 {@code use} 坐标</li>
     *   <li>对每个 action 触发 {@link #getOrLoadAction}（下载 jar + 注册 handler）</li>
     *   <li>合并所有 handler.getRuntimeDependencies() + DSL extraDeps → 一次性 {@link MavenPluginResolver#resolveWithDependencies}（批量下载传递依赖）</li>
     *   <li>对每个已加载 action 调 {@link #addRuntimeDependencies}，重建 classloader 注入全部 deps</li>
     * </ol>
     *
     * @param steps 本次要执行的所有 StepConfig（会从里面收集 action / extraDeps / use）
     * @throws PluginLoadException 任何一个 action 或 dep 下载失败即中断（fail-fast 语义）
     */
    public void preloadDependencies(Collection<StepConfig> steps) throws PluginLoadException {
        if (steps == null || steps.isEmpty()) {
            log.info("[Preload] No steps to preload.");
            return;
        }

        // 1. 收集 unique action + 每个 action 的 merged extraDeps + explicitCoord
        Map<String, PreloadStepInfo> toLoad = new LinkedHashMap<>();
        for (StepConfig s : steps) {
            if (s == null || s.getAction() == null || s.getAction().isBlank()) continue;
            PreloadStepInfo info = toLoad.computeIfAbsent(s.getAction(),
                    a -> new PreloadStepInfo(a, s.getUse()));
            if (s.getExtraDeps() != null) info.extraDeps.addAll(s.getExtraDeps());
        }

        List<String> errors = new ArrayList<>();
        int jarFreshDownload = 0, jarCached = 0;

        // 2. 并发触发 getOrLoadAction（每个 action 一个 CompletableFuture）
        log.info("[Preload] Preloading {} unique step action(s) with {} threads...",
                toLoad.size(), properties.getDownloadConcurrency());

        List<CompletableFuture<Void>> actionFutures = new ArrayList<>();
        for (PreloadStepInfo info : toLoad.values()) {
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                boolean wasLoaded = registry.containsKey(info.action);
                boolean inRegistry = isInDefaultRegistry(info.action);
                log.info("[Preload] ▶ action={} (builtin={}, registry={}, use={})",
                        info.action, wasLoaded, inRegistry,
                        info.explicitCoord != null ? "yes" : "no");
                try {
                    ActionHandler handler = getOrLoadAction(info.action, info.explicitCoord);
                    if (!wasLoaded) info.freshDownload = true;
                    else info.freshDownload = false;
                    info.handler = handler;
                    log.info("[Preload]   ✅ {} → {} (jar ready)", info.action,
                            handler.getClass().getSimpleName());
                } catch (PluginLoadException e) {
                    synchronized (errors) {
                        errors.add(String.format("action '%s': %s", info.action, e.getMessage()));
                    }
                    log.error("[Preload]   ❌ {} → load failed: {}", info.action, e.getMessage());
                }
            }, actionLoadPool);
            actionFutures.add(f);
        }

        // 等待所有 action 完成（任意一个失败不影响其他 — 都跑完再统一报告）
        try {
            CompletableFuture.allOf(actionFutures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ignored) {
            // 已经在 future 内部收集了错误，这里只需要等所有 future 完成
        }

        for (PreloadStepInfo info : toLoad.values()) {
            if (info.handler != null) {
                if (info.freshDownload) jarFreshDownload++;
                else jarCached++;
            }
        }

        // 3. 合并所有 runtime deps → 一次性批量 resolve（含传递依赖）
        Set<String> allRuntimeDeps = new LinkedHashSet<>();
        for (PreloadStepInfo info : toLoad.values()) {
            if (info.handler != null) {
                allRuntimeDeps.addAll(info.handler.getRuntimeDependencies());
                allRuntimeDeps.addAll(info.extraDeps);
            }
        }

        int runtimeJarCount = 0;
        if (!allRuntimeDeps.isEmpty()) {
            log.info("[Preload] Resolving {} unique runtime dep coord(s) (including transitive): {}",
                    allRuntimeDeps.size(), allRuntimeDeps);
            try {
                List<java.nio.file.Path> jars = mavenResolver.resolveWithDependencies(List.copyOf(allRuntimeDeps));
                runtimeJarCount = jars.size();
                for (java.nio.file.Path p : jars) {
                    log.info("[Preload]   📦 {}", p.getFileName());
                }
            } catch (MavenPluginResolver.ResolutionException e) {
                errors.add("runtime-deps: " + e.getMessage());
                log.error("[Preload]   ❌ runtime deps resolution failed: {}", e.getMessage());
            }
        } else {
            log.info("[Preload] No runtime dependencies declared.");
        }

        // 4. 对每个已成功加载的 action 重建 classloader（注入全部 runtime deps）
        for (PreloadStepInfo info : toLoad.values()) {
            if (info.handler == null) continue;
            try {
                addRuntimeDependencies(info.action, List.copyOf(info.extraDeps));
            } catch (PluginLoadException e) {
                errors.add(String.format("action '%s' runtime deps: %s", info.action, e.getMessage()));
            }
        }

        // 5. 汇总报告
        log.info("================================================================");
        log.info("[Preload] Summary:");
        log.info("  📥 Fresh JAR downloads : {}", jarFreshDownload);
        log.info("  ♻️  Cached JARs (builtin): {}", jarCached);
        log.info("  📦 Runtime dep JARs    : {} ({} unique coords)", runtimeJarCount, allRuntimeDeps.size());
        log.info("  ❌ Errors              : {}", errors.size());
        log.info("================================================================");

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("[Preload] ").append(errors.size())
                    .append(" error(s) during dependency preload:\n");
            for (int i = 0; i < errors.size(); i++) {
                sb.append("  [E").append(i + 1).append("] ").append(errors.get(i)).append('\n');
            }
            throw new PluginLoadException(sb.toString().trim());
        }
    }

    /** Preload 内部辅助 — 聚合每个 action 所有 step 的信息。 */
    private static class PreloadStepInfo {
        final String action;
        final String explicitCoord;
        final Set<String> extraDeps = new LinkedHashSet<>();
        ActionHandler handler;
        boolean freshDownload;  // true = 本次 preload 新下载, false = 之前已缓存/内置

        PreloadStepInfo(String action, String explicitCoord) {
            this.action = action;
            this.explicitCoord = explicitCoord;
        }
    }

    /**
     * 对**传入的 handler 实例集合**逐个调 destroy() —— ScenarioRunner 在 scenario 结束时收集所有
     * step 用过的 handler 独立实例，统一销毁。
     * <p>
     * 不再遍历 registry（registry 现在存 prototype 不是实例）。每个 scenario 结束时的 handler 都是
     * getOrLoadAction 反射 new 的独立实例，各自有各自的 initDone 状态。
     *
     * @param handlers 本次 scenario 执行过的所有 handler 实例（去重后）
     */
    public void destroyAllHandlers(Collection<ActionHandler> handlers) {
        if (handlers == null) return;
        Set<ActionHandler> seen = new HashSet<>();
        for (ActionHandler h : handlers) {
            if (h == null || !seen.add(h)) continue;
            try {
                h.destroy();
            } catch (Exception ex) {
                log.warn("[PluginCenter] destroy() failed for instance of '{}': {}",
                        h.getActionName(), ex.getMessage());
            }
        }
    }

    /**
     * 运行时动态加载一个 Maven 坐标的 JAR Step — 扫描 jar 内**全部** ActionHandler 并注册。
     * <p>
     * 同一个坐标的多次调用会复用第一次创建的 URLClassLoader（{@link #coordinateCache}），
     * 只会发现并注册上次可能漏掉的新 ActionHandler。
     *
     * @param coordinate 格式 {@code groupId:artifactId:version}
     * @return 第一个成功注册的 ActionHandler（调用方可按需再从 registry 拿其他已注册的）
     */
    public ActionHandler loadPlugin(String coordinate) throws PluginLoadException {
        // --- Fast path: CL 缓存命中 → 直接扫描注册（无锁，ConcurrentHashMap 本身线程安全）---
        if (coordinateCache.containsKey(coordinate)) {
            URLClassLoader cl = coordinateCache.get(coordinate);
            log.info("[PluginCenter] Coordinate {} already cached, scanning handlers...", coordinate);
            List<ActionHandler> handlers = findAllAndInstantiate(cl);
            if (handlers.isEmpty()) {
                log.warn("[PluginCenter] Cached CL for {} has no usable ActionHandler, evicting cache", coordinate);
                try { cl.close(); } catch (Exception ignored) { }
                coordinateCache.remove(coordinate);
                // Fall through — 进入慢速路径（会拿 coordinate 锁）
            } else {
                for (ActionHandler h : handlers) {
                    registerInternal(h, cl, "maven-cached:" + coordinate);
                }
                return handlers.get(0);
            }
        }

        // --- Slow path: 拿 per-coordinate 锁，避免同一个坐标被多线程重复下载 + 扫描 ---
        Object lock = loadPluginLocks.computeIfAbsent(coordinate, k -> new Object());
        synchronized (lock) {
            try {
                // double-check: 持锁后再看一次缓存（可能在等锁期间被别的线程完成了）
                if (coordinateCache.containsKey(coordinate)) {
                    URLClassLoader cl = coordinateCache.get(coordinate);
                    List<ActionHandler> handlers = findAllAndInstantiate(cl);
                    if (!handlers.isEmpty()) {
                        for (ActionHandler h : handlers) {
                            registerInternal(h, cl, "maven-cached:" + coordinate);
                        }
                        return handlers.get(0);
                    }
                    // 缓存条目仍然空 handler — 说明之前的 CL 有问题，清掉重建
                    coordinateCache.remove(coordinate);
                }

                log.info("[PluginCenter] Loading JAR plugin from Maven coord: {}", coordinate);
                try {
                    Path jarPath = mavenResolver.resolveArtifact(coordinate);
                    URL[] urls = new URL[] { jarPath.toUri().toURL() };

                    URLClassLoader cl = new PluginClassLoader(urls, Thread.currentThread().getContextClassLoader());
                    coordinateCache.put(coordinate, cl);

                    List<ActionHandler> handlers = findAllAndInstantiate(cl);
                    if (handlers.isEmpty()) {
                        coordinateCache.remove(coordinate);
                        try { cl.close(); } catch (Exception ignored) { }
                        throw new PluginLoadException(
                                "No ActionHandler implementation found in plugin jar: " + coordinate
                                        + " (no META-INF/services entry, no concrete ActionHandler class with no-arg constructor)");
                    }

                    for (ActionHandler h : handlers) {
                        registerInternal(h, cl, "maven:" + coordinate);
                    }
                    log.info("[PluginCenter] Loaded {} handler(s) from {} (direct jar, parent CL provides transitive deps)",
                            handlers.size(), coordinate);
                    return handlers.get(0);
                } catch (MavenPluginResolver.ResolutionException e) {
                    throw new PluginLoadException("Failed to resolve plugin from Maven: " + coordinate, e);
                } catch (java.net.MalformedURLException e) {
                    throw new PluginLoadException("Failed to build URL for plugin jar: " + coordinate, e);
                }
            } finally {
                // 释放锁对象 —— 避免 Map 无限增长
                loadPluginLocks.remove(coordinate, lock);
            }
        }
    }

    /**
     * 将额外依赖解析并注入到指定 action 的 classloader。
     * <p>
     * 实现策略（避免 Java 17+ 模块系统禁止反射调 URLClassLoader.addURL 的问题）：
     * 每次调用时都**创建一个新的 PluginClassLoader**，合并：
     * <ol>
     *   <li>旧 classloader 的所有 URL（包括最初插件 jar + 之前已经追加的 deps）</li>
     *   <li>本次新增 deps 的所有 URL（resolveWithDependencies 下载的）</li>
     * </ol>
     * 旧 CL 被 close + 从 classLoaders 映射里替换（同一个 actionName → 新 CL）。
     * 内置 step（没有 CL）创建的新 CL 只有本次 deps URLs。
     * <p>
     * JVM 会自动 GC 掉没有被引用的旧 CL + 对应的旧类定义。
     */
    public int addRuntimeDependencies(String actionName, List<String> extraCoords) throws PluginLoadException {
        ActionHandlerPrototype proto = registry.get(actionName);
        if (proto == null) {
            throw new PluginLoadException("Action not found: " + actionName);
        }

        // 合并 Step 自身声明（注册时存进 prototype 的 runtimeDeps）+ DSL 传入
        Set<String> merged = new LinkedHashSet<>(proto.runtimeDeps());
        if (extraCoords != null) merged.addAll(extraCoords);
        if (merged.isEmpty()) return 0;

        // === 缓存命中检查：如果这个 action 已经加载过完全相同的 deps，直接跳过 ===
        Set<String> prevLoaded = loadedDepsPerAction.get(actionName);
        if (prevLoaded != null && prevLoaded.containsAll(merged)) {
            log.debug("[PluginCenter] Step '{}' already has all {} dep(s) loaded — skip", actionName, merged.size());
            return 0;
        }

        // 1. 逐个解析新增 deps —— 用 resolveArtifact 直接拿 jar（本地命中即返回，极速）。
        //    对 JDBC driver 这类没有 compile-scope 传递依赖的 artifact 足够。
        //    如需完整传递依赖图（resolveWithDependencies，慢 + 需远程 POM 校验），
        //    请在 DSL YAML 里显式声明所有需要的 transitive 坐标。
        List<Path> newDeps = new ArrayList<>();
        for (String coord : merged) {
            try {
                Path jar = mavenResolver.resolveArtifact(coord);
                newDeps.add(jar);
            } catch (MavenPluginResolver.ResolutionException e) {
                throw new PluginLoadException("Failed to resolve dep: " + coord, e);
            }
        }

        // 2. 合并 URLS：旧 CL 的 URL + 新 deps URL
        URLClassLoader oldCL = classLoaders.get(actionName);
        List<URL> urls = new ArrayList<>();
        if (oldCL != null) {
            urls.addAll(Arrays.asList(oldCL.getURLs()));  // 旧 CL 所有 URL
        }
        for (Path p : newDeps) {
            try {
                URL u = p.toUri().toURL();
                if (!urls.contains(u)) urls.add(u);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        // 3. 创建新 CL（parent 复用旧 CL 的 parent）
        ClassLoader parent = oldCL != null ? oldCL.getParent()
                : Thread.currentThread().getContextClassLoader();
        PluginClassLoader newCL = new PluginClassLoader(urls.toArray(new URL[0]), parent);

        // 4. 替换：更新 classLoaders 映射，关闭旧 CL（如果有）
        classLoaders.put(actionName, newCL);
        if (oldCL != null) {
            try { oldCL.close(); } catch (Exception ignored) { /* Java 模块系统可能拒绝 */ }
        }

        // 5. 更新 deps 缓存
        loadedDepsPerAction.put(actionName, Set.copyOf(merged));

        log.info("[PluginCenter] Step '{}' → created new CL with {} URL(s) ({}/{} jars from new deps)",
                actionName, urls.size(), newDeps.size(), merged.size());
        return newDeps.size();
    }

    /** 热重载 — 停掉旧 classloader，清理缓存，重新 loadPlugin。 */
    public ActionHandler reloadPlugin(String coordinate) throws PluginLoadException {
        // 1. unregister 所有挂在旧 CL 上的 actionName
        URLClassLoader oldCL = coordinateCache.get(coordinate);
        if (oldCL != null) {
            List<String> actionsToRemove = classLoaders.entrySet().stream()
                    .filter(e -> e.getValue() == oldCL)
                    .map(Map.Entry::getKey)
                    .toList();
            actionsToRemove.forEach(registry::remove);
            actionsToRemove.forEach(loadedDepsPerAction::remove);  // 同步清理 deps 缓存
            try { oldCL.close(); } catch (Exception ignored) { /* IOException + ReflectiveOperationException */ }
        }
        // 2. 清缓存
        coordinateCache.remove(coordinate);
        // 3. 重新 load（会重新下载 + 构建 CL + 入缓存）
        return loadPlugin(coordinate);
    }

    /** 当前 Maven 插件 CL 缓存数量 — 诊断/监控用。 */
    public int cachedClassLoaderCount() {
        return coordinateCache.size();
    }

    /** 判断一个坐标是否已缓存。 */
    public boolean isCached(String coordinate) {
        return coordinateCache.containsKey(coordinate);
    }

    /**
     * 获取指定 action 所属的 URLClassLoader。
     * <p>
     * 内置 step 默认返回宿主 classloader；
     * 经 addRuntimeDependencies 追加过 deps 的 step 返回重建后的 URLClassLoader；
     * Maven 坐标加载的外置插件返回 coordinateCache 里的 CL。
     *
     * @return step 的 classloader，null 如果 action 未注册
     */
    public ClassLoader getClassLoaderFor(String actionName) {
        return classLoaders.get(actionName);
    }

    // ==================== 生命周期 ====================

    /**
     * 扫描 classpath 上所有 ActionHandler 实现 — 不用 Spring @Component。
     * <p>
     * 用 Spring 的 {@link ClassPathScanningCandidateComponentProvider}（spring-core，CLI 必有）
     * 扫描 io.tapdata.it.l3 包树下所有实现了 ActionHandler 的类，
     * 然后反射调用无参构造器实例化并注册。
     * <p>
     * 这样 Step 实现零 Spring 依赖 — 既可以被 fat jar 内置扫描，也可以被 MavenPluginResolver 外置加载。
     */
    private int registerBuiltInHandlers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ActionHandler.class));

        Set<Class<?>> seen = new HashSet<>();
        for (var bd : scanner.findCandidateComponents("io.tapdata.it.l3")) {
            try {
                String className = bd.getBeanClassName();
                if (className == null) continue;
                Class<?> clazz = Class.forName(className);
                if (!ActionHandler.class.isAssignableFrom(clazz) || !seen.add(clazz)) continue;
                if (java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())
                        || clazz.isInterface()) continue;

                // 反射实例化（必须有无参构造器）
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (!(instance instanceof ActionHandler handler)) continue;

                // registerInternal 会跳过已注册的（外置插件可能已扫过）
                ClassLoader ctxCL = Thread.currentThread().getContextClassLoader();
                URLClassLoader cl = ctxCL instanceof URLClassLoader ucl ? ucl : null;
                boolean registered = registerInternal(handler, cl, "builtin");
                if (registered) {
                    log.debug("[PluginCenter] Built-in: {} ({})", handler.getActionName(), clazz.getSimpleName());
                }
            } catch (NoSuchMethodException e) {
                log.warn("[PluginCenter] Skip {} — no public no-arg constructor", bd.getBeanClassName());
            } catch (Exception e) {
                log.warn("[PluginCenter] Skip {} — {}", bd.getBeanClassName(), e.getMessage());
            }
        }
        return registry.size();
    }

    @PostConstruct
    public void init() {
        log.info("[PluginCenter] Initializing (pluginsDir={}, localRepo={}, remoteRepos={})",
                properties.getPluginsDir(), properties.getLocalRepoDir(), properties.getRemoteRepos());

        // 1. 注册内置 Step —— 通过 classpath 扫描 ActionHandler 实现（零 Spring 注解依赖）
        int builtInCount = registerBuiltInHandlers();
        log.info("[PluginCenter] Registered {} built-in step handler(s) (classpath scan)", builtInCount);

        // 2. PF4J legacy — 扫描 plugin.properties 插件包
        pluginManager.loadPlugins();
        pluginManager.startPlugins();
        List<PluginWrapper> installed = pluginManager.getPlugins();
        log.info("[PluginCenter] Found {} PF4J plugin(s) in {}", installed.size(), properties.getPluginsDir());

        int pf4jCount = 0;
        for (PluginWrapper wrapper : installed) {
            pf4jCount += registerExtensionsFromPlugin(wrapper.getPluginId());
        }

        // 3. 加载默认注册表 — classpath:steps-registry.yaml
        loadDefaultRegistry();

        log.info("[PluginCenter] Init complete: {} registered ({} built-in, {} PF4J, {} registry entries)",
                registry.size(), builtInCount, pf4jCount, defaultRegistry.size());
        if (!defaultRegistry.isEmpty()) {
            log.debug("[PluginCenter] Default registry: {}", defaultRegistry);
        }
    }

    /**
     * 从 classpath 加载默认注册表 {@code steps-registry.yaml}。
     * 文件不存在不报错（允许 CLI 没有任何外置 step），仅 WARN 一次。
     * <p>
     * YAML 格式：{@code action_name: "groupId:artifactId:version"}。
     */
    private void loadDefaultRegistry() {
        ClassPathResource resource = new ClassPathResource(REGISTRY_CLASSPATH);
        if (!resource.exists()) {
            log.info("[PluginCenter] Default registry '{}' not found on classpath — no external steps auto-load mapping",
                    REGISTRY_CLASSPATH);
            return;
        }
        try (InputStream is = resource.getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> yaml = new Yaml().load(is);
            if (yaml != null) {
                yaml.forEach((action, coord) -> defaultRegistry.put(action, coord.toString()));
            }
            log.info("[PluginCenter] Loaded {} entry(ies) from default registry '{}'",
                    defaultRegistry.size(), REGISTRY_CLASSPATH);
        } catch (Exception e) {
            log.warn("[PluginCenter] Failed to parse default registry '{}': {}", REGISTRY_CLASSPATH, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[PluginCenter] Shutting down...");

        // 1. PF4J legacy
        pluginManager.stopPlugins();
        pluginManager.unloadPlugins();

        // 2. 关闭 Maven coordinate 缓存里的所有 URLClassLoader
        for (Map.Entry<String, URLClassLoader> e : coordinateCache.entrySet()) {
            try {
                e.getValue().close();
                log.debug("[PluginCenter] Closed CL for {}", e.getKey());
            } catch (Exception ex) {
                log.debug("[PluginCenter] CL close failed for {}: {}", e.getKey(), ex.getMessage());
            }
        }
        coordinateCache.clear();

        // 3. 关闭 addRuntimeDependencies 创建的独立 CL（没有在 coordinateCache 里的）
        for (URLClassLoader cl : classLoaders.values()) {
            try { cl.close(); } catch (Exception e) { /* ignore */ }
        }
        classLoaders.clear();

        // Maven Resolver session 不需要显式关闭 —— 但下载线程池要关
        actionLoadPool.shutdown();
        try { actionLoadPool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        mavenResolver.shutdown();
        loadedDepsPerAction.clear();
        log.info("[PluginCenter] Shutdown complete (coordinateCache + loadedDepsPerAction cleared)");
    }

    // ==================== 内部方法 ====================

    /** 从 PF4J 插件中提取 @Extension 标注的 ActionHandler 并注册。 */
    private int registerExtensionsFromPlugin(String pluginId) {
        List<ActionHandler> handlers = pluginManager.getExtensions(ActionHandler.class, pluginId);
        for (ActionHandler handler : handlers) {
            PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
            URLClassLoader cl = wrapper != null ? (URLClassLoader) wrapper.getPluginClassLoader() : null;
            registerInternal(handler, cl, "pf4j:" + pluginId);
        }
        return handlers.size();
    }

    /** 统一注册入口：同名冲突时后注册覆盖先注册。 */
    /**
     * 内部注册 — 把 action 的 prototype (Class + ClassLoader) 加到 registry。
     * <p>
     * 注意：入参 {@code handler} 只用来拿 actionName + Class 对象 —— **不存这个实例**。
     * 真正执行 step 时由 {@link #getOrLoadAction} / {@link #getAction} 反射 new 新实例。
     * 这样每个 DSL step 都有独立的 handler 实例。
     *
     * @return true = 新注册成功, false = 覆盖了已有实现
     */
    private boolean registerInternal(ActionHandler handler, URLClassLoader cl, String source) {
        String actionName = handler.getActionName();
        Class<? extends ActionHandler> clazz = handler.getClass();
        ClassLoader handlerCL = cl != null ? cl : clazz.getClassLoader();

        // 注册时顺手拿一下内置 runtime deps —— 用临时实例（handler 参数）调一次就好，存进 prototype
        List<String> runtimeDeps = handler.getRuntimeDependencies() != null
                ? List.copyOf(handler.getRuntimeDependencies())
                : List.of();

        ActionHandlerPrototype proto = new ActionHandlerPrototype(clazz, handlerCL, runtimeDeps);
        ActionHandlerPrototype existing = registry.put(actionName, proto);
        if (cl != null) classLoaders.put(actionName, cl);

        if (existing != null) {
            log.warn("[PluginCenter] Step '{}' from '{}' overrides existing from '{}'",
                    actionName, source, existing.clazz().getSimpleName());
            return false;
        }
        log.debug("[PluginCenter] Registered step '{}' → {} (from {}, runtimeDeps={}) — prototype ready",
                actionName, clazz.getSimpleName(), source, runtimeDeps);
        return true;
    }

    private void unregister(String actionName) {
        registry.remove(actionName);
        classLoaders.remove(actionName);
    }

    /**
     * 在 URLClassLoader 内发现**全部** ActionHandler 实现并实例化。
     * 优先 SPI（META-INF/services），fallback 扫描全部 Class。
     * 只返回能通过 tryInstantiate 的（有 public 无参构造器）。
     */
    private List<ActionHandler> findAllAndInstantiate(URLClassLoader cl) throws PluginLoadException {
        Set<String> seenNames = new HashSet<>();
        List<ActionHandler> result = new ArrayList<>();

        try {
            // 方式 1：SPI
            Enumeration<URL> spiEntries = cl.getResources(
                    "META-INF/services/io.tapdata.it.l3.plugin.ActionHandler");
            while (spiEntries.hasMoreElements()) {
                URL url = spiEntries.nextElement();
                try (var is = url.openStream();
                     var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#") || !seenNames.add(line)) continue;
                        Class<?> clazz = cl.loadClass(line);
                        if (ActionHandler.class.isAssignableFrom(clazz)) {
                            ActionHandler h = tryInstantiate(clazz);
                            if (h != null) result.add(h);
                        }
                    }
                }
            }

            // 方式 2：fallback — 扫描所有 jar 里的 class
            for (URL url : cl.getURLs()) {
                if (!url.toString().endsWith(".jar")) continue;
                try (var zf = new java.util.zip.ZipFile(java.nio.file.Paths.get(url.toURI()).toFile())) {
                    Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        String entryName = entries.nextElement().getName();
                        if (!entryName.endsWith(".class") || entryName.contains("$")) continue;
                        String className = entryName.replace("/", ".").replace(".class", "");
                        if (!seenNames.add(className)) continue;
                        try {
                            Class<?> clazz = cl.loadClass(className);
                            if (ActionHandler.class.isAssignableFrom(clazz)
                                    && !clazz.isInterface()
                                    && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                                ActionHandler h = tryInstantiate(clazz);
                                if (h != null) result.add(h);
                            }
                        } catch (NoClassDefFoundError | ClassNotFoundException ignored) { }
                    }
                }
            }
        } catch (Exception e) {
            throw new PluginLoadException("Failed to scan ActionHandler from classloader", e);
        }
        return result;
    }

    /**
     * 尝试反射实例化 ActionHandler。
     * 优先无参构造器，fallback 找带 {@code @Autowired} 的单参数构造器（从 Spring ApplicationContext 获取 Bean）。
     * 对于没有无参构造且无法通过 Spring 获取 Bean的类，返回 null（跳过）。
     */
    private ActionHandler tryInstantiate(Class<?> clazz) {
        try {
            clazz.getDeclaredConstructor();  // 先验证
            return (ActionHandler) clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            // 没有无参构造——可能有 @Autowired 构造器。外置插件场景下我们没有 Spring Context，
            // 所以只能跳过这个实现（spring-context provided 依赖的外置 step 模块应该在独立 jar 里）。
            return null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * 用 prototype 指定的 ClassLoader 反射 new ActionHandler 实例 —— 核心改动。
     * <p>
     * 外置 jar handler 必须在**它自己的 URLClassLoader** 里被实例化，否则会从宿主 fat jar 拿到
     * 同名类（SPI 依赖版本不一致时 ClassCastException）。内置 handler 用 Spring context CL 即可。
     */
    private ActionHandler tryInstantiateInCL(ActionHandlerPrototype proto) {
        try {
            ClassLoader cl = proto.classLoader();
            if (cl == null) cl = Thread.currentThread().getContextClassLoader();
            Class<?> actual = cl == proto.clazz().getClassLoader()
                    ? proto.clazz()
                    : Class.forName(proto.clazz().getName(), true, cl);
            actual.getDeclaredConstructor();  // 先验证无参构造器
            Object instance = actual.getDeclaredConstructor().newInstance();
            return (ActionHandler) instance;
        } catch (NoSuchMethodException e) {
            log.warn("[PluginCenter] {} — no public no-arg constructor, cannot instantiate per-step",
                    proto.clazz().getSimpleName());
            return null;
        } catch (ReflectiveOperationException e) {
            log.warn("[PluginCenter] {} — instantiation failed: {}", proto.clazz().getSimpleName(),
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return null;
        }
    }

    /** PluginCenter 运行时异常 — RuntimeException 子类，避免污染上层调用方签名。 */
    public static class PluginLoadException extends Exception {
        public PluginLoadException(String msg) { super(msg); }
        public PluginLoadException(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * 自定义 URLClassLoader — Self-First 查找顺序（插件自己的类 + 第三方依赖优先，
     * 宿主 SPI 类通过 parent fallback 加载）。
     * 设计文档 §7.8 类加载隔离策略要求 Self-First。
     */
    public static class PluginClassLoader extends URLClassLoader {
        static {
            ClassLoader.registerAsParallelCapable();
        }

        public PluginClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                // 1. 先查 parent —— 宿主已有这个类（如内置 step、Spring Boot、已加载的 SPI 接口），
                //    就必须用 parent 的（否则 LinkageError 重复类定义 + Spring 依赖注入失效）。
                //    这比设计文档 §7.8 严格 Self-First 更安全，因为真正 Self-First 的收益（
                //    插件私有第三方依赖不被宿主版本污染）只在"parent 找不到"时才需要。
                try {
                    Class<?> c = super.loadClass(name, false);  // 先查 parent（不 resolve，避免过早初始化）
                    if (resolve) resolveClass(c);
                    return c;
                } catch (ClassNotFoundException ignored) { /* 宿主没这个类 → Self-First */ }

                // 2. Self-First：插件独有的类（宿主上不存在）从自己的 URL 加载
                Class<?> c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
