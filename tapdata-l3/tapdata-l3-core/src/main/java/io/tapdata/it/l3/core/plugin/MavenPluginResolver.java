package io.tapdata.it.l3.core.plugin;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Maven Plugin Resolver — 从 Maven 仓库下载 JAR + 全部传递依赖到本地缓存。
 * <p>
 * 采用官方推荐的 {@code maven-resolver-supplier}（org.eclipse.aether.supplier.RepositorySystemSupplier）——
 * 无需 Eclipse Sisu DI，一行代码 bootstrap {@link RepositorySystem}，已内置 connector-basic + transport-http。
 * <p>
 * 与设计文档 §7.5 的差异：
 * <ul>
 *   <li>设计文档假设产物是 PF4J zip，这里直接解析 jar + 传递依赖 → 输出 List{@code <Path>}（文件路径）</li>
 *   <li>不要求 pf4j-maven-plugin — 任何推到 Maven 仓库的普通 jar 都能被加载</li>
 *   <li>内置 Maven Central 作为默认远端仓库，可通过 nexusRepo 配置覆盖</li>
 * </ul>
 * <p>
 * 用法：
 * <pre>
 *   MavenPluginResolver resolver = new MavenPluginResolver(localRepo, List.of(central, nexus));
 *   List&lt;Path&gt; jars = resolver.resolve("com.example:shell-step:1.0.0");  // jar + transitive deps
 *   URLClassLoader cl = new URLClassLoader(paths.toURLArray(), parent);
 *   Class&lt;?&gt; handlerClass = cl.loadClass("com.example.step.ShellAction");
 * </pre>
 */
public class MavenPluginResolver {

    private static final Logger log = LoggerFactory.getLogger(MavenPluginResolver.class);

    private final RepositorySystem repositorySystem;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> remoteRepos;

    /**
     * 构造 — 使用 Maven 默认本地仓库（~/.m2/repository）+ Maven Central。
     */
    public MavenPluginResolver() {
        this(System.getProperty("user.home") + "/.m2/repository", null);
    }

    /**
     * @param localRepoDir 本地 Maven 仓库路径（缓存目录）
     * @param nexusUrl     可选的 Nexus 私服地址（为 null 时只用 Maven Central）
     */
    public MavenPluginResolver(String localRepoDir, String nexusUrl) {
        Path localRepo = Path.of(localRepoDir);
        try {
            Files.createDirectories(localRepo);
        } catch (IOException e) {
            log.warn("[MavenPluginResolver] Failed to create local repo dir {}: {}", localRepo, e.getMessage());
        }

        // 清理本地 Maven 仓库里所有 `_remote.repositories` metadata 文件。
        // 这些文件记录了 artifact 从哪个 remote repo 下载的，当 session 配置的 remote repo ID
        // 与 metadata 记录的不匹配时，Maven Resolver 会慢速（每个 pom ~15 秒）验证远端可用性。
        // 清理后 Maven Resolver 直接信任本地缓存，完全跳过验证。
        // 这是安全的 —— _remote.repositories 只是 download metadata，不影响 artifact jar/pom 本身。
        try (var walk = Files.walk(localRepo)) {
            walk.filter(p -> p.getFileName().toString().equals("_remote.repositories"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                    });
            log.info("[MavenPluginResolver] Cleaned _remote.repositories metadata from local repo");
        } catch (Exception ignored) {
            log.debug("[MavenPluginResolver] Could not clean _remote.repositories (permissions or walk error)");
        }

        // 1. Bootstrap RepositorySystem — maven-resolver-supplier 一行搞定
        this.repositorySystem = new RepositorySystemSupplier().get();

        // 2. Build session with local repo + JVM system properties
        //    (profile activation like junit's `jdk9` needs java.version / os.name etc.)
        this.session = new DefaultRepositorySystemSession();
        session.setLocalRepositoryManager(
                repositorySystem.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));

        // 关键！Maven Resolver 需要 JVM 系统属性来正确激活 pom 里的 profile（如 junit:jdk9、byte-buddy:jdk11+）
        Properties sysProps = new Properties();
        sysProps.setProperty("java.version", System.getProperty("java.version"));
        sysProps.setProperty("java.specification.version", System.getProperty("java.specification.version", ""));
        sysProps.setProperty("maven.compiler.source", System.getProperty("java.specification.version", "17"));
        sysProps.setProperty("maven.compiler.target", System.getProperty("java.specification.version", "17"));
        sysProps.setProperty("os.name", System.getProperty("os.name"));
        sysProps.setProperty("os.arch", System.getProperty("os.arch"));
        sysProps.setProperty("os.version", System.getProperty("os.version"));
        session.setSystemProperties(sysProps);

        // 用户属性（pom.properties 替换）— 设一个默认值避免 ${project.basedir} 等占位符解析异常
        session.setUserProperties(new Properties());

        // 本地仓库已有的 artifact 不强制远端验证（避免 "cached from a remote repository ID that is
        // unavailable" 导致的慢重试）。真正缺的 artifact 还是会从远端下载。
        session.setUpdatePolicy("never");
        // 忽略 pom 里 <repositories> 声明的额外仓库 —— 只用我们显式配置的 central + nexus
        session.setIgnoreArtifactDescriptorRepositories(true);

        // 3. Configure remote repos (Central + optional Nexus)
        this.remoteRepos = new ArrayList<>();
        this.remoteRepos.add(new RemoteRepository.Builder("central", "default",
                "https://repo.maven.apache.org/maven2/").build());
        if (nexusUrl != null && !nexusUrl.isBlank()) {
            this.remoteRepos.add(new RemoteRepository.Builder("nexus", "default", nexusUrl).build());
        }

        log.info("[MavenPluginResolver] Initialized (localRepo={}, remoteRepos={})",
                localRepo, remoteRepos.stream().map(RemoteRepository::getId).toList());
    }

    // ==================== 核心 API ====================

    /**
     * 解析单个坐标的 artifact（jar）。只拉这个 jar，不解析传递依赖。
     *
     * @param coordinate 格式 {@code groupId:artifactId:version}
     * @return jar 文件路径（可能已存在于本地仓库，也可能是新下载的）
     */
    public Path resolveArtifact(String coordinate) throws ResolutionException {
        Artifact artifact = parseCoordinate(coordinate);
        try {
            ArtifactRequest request = new ArtifactRequest(artifact, remoteRepos, null);
            ArtifactResult result = repositorySystem.resolveArtifact(session, request);
            Path jarPath = result.getArtifact().getFile().toPath();
            log.debug("[MavenPluginResolver] Resolved artifact {} → {}", coordinate, jarPath);
            return jarPath;
        } catch (Exception e) {
            throw new ResolutionException("Failed to resolve artifact: " + coordinate, e);
        }
    }

    /**
     * 解析坐标及其**全部传递依赖**（scope=compile），去重后返回所有 jar 路径。
     * <p>
     * 这是插件加载的主入口 — PluginCenter 用它来拿到构建 URLClassLoader 所需的全部 jar 文件。
     *
     * @param coordinates 一个或多个 {@code groupId:artifactId:version} 坐标
     * @return 去重后的 jar 文件路径列表，顺序：请求的 artifact 在前，传递依赖在后
     */
    public List<Path> resolveWithDependencies(String... coordinates) throws ResolutionException {
        if (coordinates == null || coordinates.length == 0) return List.of();
        return resolveWithDependencies(List.of(coordinates));
    }

    public List<Path> resolveWithDependencies(List<String> coordinates) throws ResolutionException {
        Set<String> seen = new HashSet<>();  // 坐标级去重（groupId:artifactId:version）
        List<Path> result = new ArrayList<>();

        // 先解析直接 artifacts（保证它们出现在列表前面）
        for (String coord : coordinates) {
            Artifact artifact = parseCoordinate(coord);
            if (!seen.add(coord)) continue;  // 跳过重复坐标
            try {
                ArtifactResult ar = repositorySystem.resolveArtifact(
                        session, new ArtifactRequest(artifact, remoteRepos, null));
                result.add(ar.getArtifact().getFile().toPath());
            } catch (Exception e) {
                throw new ResolutionException("Failed to resolve artifact: " + coord, e);
            }
        }

        // collect + resolve 传递依赖
        // 注意：Maven Resolver 对本地已有的 pom 做 "verifying that is downloadable from" 慢速远端验证
        // 是已知问题（本地 metadata _remote.repositories 记录的 repo ID 与 session 配置不匹配）。
        // 我们已经：① 设了 setUpdatePolicy("never") ② setIgnoreArtifactDescriptorRepositories(true)
        // ③ 构造时清理 _remote.repositories。但 collect 阶段仍可能因 profile activation（需 Java 版本属性）
        //    或 parent pom 缺失而失败。
        // 失败时 fallback 到直接 jar —— 生产场景下 step 模块传递依赖（Jackson/SLF4J 等）宿主 fat jar 已有，
        // extra_deps 动态下载的 artifact（如 sqlite-jdbc）本身也没有 compile scope 传递依赖，够用。
        try {
            CollectRequest collectRequest = new CollectRequest();
            for (String coord : coordinates) {
                collectRequest.addDependency(new Dependency(parseCoordinate(coord), "compile"));
            }
            collectRequest.setRepositories(remoteRepos);

            CollectResult collectResult = repositorySystem.collectDependencies(session, collectRequest);

            DependencyRequest dependencyRequest = new DependencyRequest();
            dependencyRequest.setCollectRequest(collectRequest);
            repositorySystem.resolveDependencies(session, dependencyRequest);

            // 遍历所有 resolved 依赖节点（跳过顶层本身 —— 已经加过了）
            collectResult.getRoot().getChildren().forEach(child -> collectAndResolveJars(child, result, seen));
        } catch (Exception e) {
            log.warn("[MavenPluginResolver] Dependency graph resolution failed ({}), falling back to direct jars only",
                    e.getMessage());
            log.debug("[MavenPluginResolver] Full stacktrace:", e);
        }

        log.info("[MavenPluginResolver] Resolved {} artifact(s) + {} transitive dep(s) = {} jar(s) total",
                coordinates.size(), seen.size() - coordinates.size(), result.size());
        return result;
    }

    /**
     * 只从本地 Maven 仓库直接拿坐标对应的 jar，**不解析传递依赖**。
     * <p>
     * 用于本地已安装的模块（mvn install 后），此时 artifact 一定在本地仓库里，
     * 但 Maven Artifact Resolver 可能因为 parent BOM 链不完整或 profile activation 问题
     * 无法构建完整的依赖图。
     * <p>
     * 返回的路径格式：{localRepo}/{groupId.replace('.', '/')}/{artifactId}/{version}/{artifactId}-{version}.jar
     */
    public Path resolveLocalOnly(String coordinate) throws ResolutionException {
        Artifact artifact = parseCoordinate(coordinate);
        Path file = Path.of(session.getLocalRepositoryManager().getPathForLocalArtifact(artifact));
        if (!Files.exists(file)) {
            throw new ResolutionException("Artifact not in local repo: " + coordinate + " → " + file);
        }
        log.debug("[MavenPluginResolver] resolveLocalOnly: {} → {}", coordinate, file);
        return file;
    }

    /**
     * 检查坐标对应的 jar 是否已经在本地缓存中（不触发网络请求）。
     */
    public boolean isCached(String coordinate) {
        try {
            Artifact artifact = parseCoordinate(coordinate);
            Path file = Path.of(session.getLocalRepositoryManager().getPathForLocalArtifact(artifact));
            return Files.exists(file);
        } catch (ResolutionException e) {
            return false;
        }
    }

    // ==================== 内部 ====================

    /**
     * 递归遍历依赖节点树 —— 先从本地仓库拿 jar，找不到就手动 resolveArtifact 下载。
     * <p>
     * 这样即使 resolveDependencies 整体失败（如 scope mediation / BOM chain 问题），
     * 我们也能靠 collectDependencies 已经拿到的节点信息把每个 jar 单独拉下来。
     */
    private void collectAndResolveJars(DependencyNode node, List<Path> result, Set<String> seen) {
        Artifact a = node.getArtifact();
        String coord = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion();
        if (!seen.add(coord)) {
            // 已添加过 —— 但还是要继续遍历 children（不同路径可能指向同一个 artifact）
        } else {
            Path file = Path.of(session.getLocalRepositoryManager().getPathForLocalArtifact(a));
            if (Files.exists(file)) {
                result.add(file);
            } else {
                // 本地仓库没有 → 手动 resolve 下载
                try {
                    ArtifactResult ar = repositorySystem.resolveArtifact(
                            session, new ArtifactRequest(a, remoteRepos, null));
                    result.add(ar.getArtifact().getFile().toPath());
                } catch (Exception resolveEx) {
                    log.debug("[MavenPluginResolver] Skip {} — resolve failed: {}", coord, resolveEx.getMessage());
                }
            }
        }
        for (DependencyNode child : node.getChildren()) {
            collectAndResolveJars(child, result, seen);
        }
    }

    /**
     * 将 {@code groupId:artifactId:version} 解析为 {@link DefaultArtifact}。
     * 支持可选的 classifier / packaging（暂不暴露，按默认 jar 处理）。
     */
    static Artifact parseCoordinate(String coordinate) throws ResolutionException {
        if (coordinate == null) throw new ResolutionException("Coordinate is null");
        String[] parts = coordinate.split(":");
        if (parts.length < 3) {
            throw new ResolutionException("Bad coordinate (expected groupId:artifactId:version): " + coordinate);
        }
        if (parts.length == 3) {
            return new DefaultArtifact(parts[0], parts[1], null, "jar", parts[2]);
        }
        // 4-part: groupId:artifactId:packaging:version
        return new DefaultArtifact(parts[0], parts[1], null, parts[2], parts[3]);
    }

    /** 解析过程中出现的运行时异常 — 用 Exception 包装 Maven Resolver 的异常链。 */
    public static class ResolutionException extends Exception {
        public ResolutionException(String msg) { super(msg); }
        public ResolutionException(String msg, Throwable cause) { super(msg, cause); }
    }
}
