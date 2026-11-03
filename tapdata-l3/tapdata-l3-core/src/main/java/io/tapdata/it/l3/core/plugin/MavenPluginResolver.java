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

import javax.net.ssl.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
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
    private final Path localRepoDir;

    /**
     * 下载并发线程池 —— 用于 resolveWithDependencies / resolveArtifact 的并发下载。
     * 固定大小线程池（core = max），所有 download 方法共享，生命周期由 shutdown() 管理。
     * <p>
     * Maven RepositorySystem + RepositorySystemSession 是**线程安全**的 —— session 内部状态不可变，
     * 所以多个线程可以并发调 repositorySystem.resolveArtifact(session, ...) 安全。
     */
    private final ExecutorService downloadPool;
    private final int downloadConcurrency;
    private volatile boolean shutdown = false;

    /**
     * 构造 — 使用默认本地仓库（~/.m2/repository）+ 默认 Maven Central。
     */
    public MavenPluginResolver() {
        this(System.getProperty("user.home") + "/.m2/repository",
                List.of("https://repo.maven.apache.org/maven2/"));
    }

    /**
     * @param localRepoDir   本地 Maven 仓库路径（artifact 缓存目录）
     * @param remoteRepoUrls 远端 Maven 仓库 URL 列表 —— 顺序决定 lookup 优先级。
     *                       如果为 null / 空，会自动补上 Maven Central 作为兜底。
     *                       默认下载并发 = 4。
     */
    public MavenPluginResolver(String localRepoDir, List<String> remoteRepoUrls) {
        this(localRepoDir, remoteRepoUrls, 4);
    }

    /**
     * @param localRepoDir      本地 Maven 仓库路径
     * @param remoteRepoUrls    远端 Maven 仓库 URL 列表 —— 为 null/空自动补 Maven Central
     * @param downloadConcurrency 下载依赖的并发线程数，必须 >=1（过小会自动 clamp 到 1）
     */
    public MavenPluginResolver(String localRepoDir, List<String> remoteRepoUrls, int downloadConcurrency) {
        this.localRepoDir = Path.of(localRepoDir);
        Path localRepo = this.localRepoDir;
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

        // 0. 修复自签名 / 企业内部 Nexus SSL 证书不信任问题 —— 让 Aether HttpTransporter 信任所有证书。
        // maven-resolver-supplier 的 Apache HttpClient transport 不遵循 JVM 默认 trustStore 属性，
        // 所以直接设置全局默认 SSLContext + HostnameVerifier 来覆盖。
        // 注意：这是一个 internal testing tool，安全性可接受（不建议生产环境这样做）。
        try {
            TrustManager[] allTrust = { new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String s) { }
                public void checkServerTrusted(X509Certificate[] c, String s) { }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, allTrust, new java.security.SecureRandom());
            SSLContext.setDefault(ctx);
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            // Aether / Apache HttpClient 5 可能不读上面的，但 System.setProperty 是备用
            System.setProperty("maven.resolver.transport.http.ssl", "insecure");
            log.info("[MavenPluginResolver] SSL context set to trust all certificates (internal Nexus compatibility)");
        } catch (Exception e) {
            log.warn("[MavenPluginResolver] Failed to set SSL context: {}", e.getMessage());
        }

        // 1. Bootstrap RepositorySystem — maven-resolver-supplier 一行搞定
        this.repositorySystem = new RepositorySystemSupplier().get();

        // 2. Build session with local repo + JVM system properties
        this.session = new DefaultRepositorySystemSession();
        session.setLocalRepositoryManager(
                repositorySystem.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));

        // Maven Resolver 需要 JVM 系统属性来正确激活 pom 里的 profile（如 junit:jdk9、byte-buddy:jdk11+）
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
        // 注意：不能设 "never" —— SNAPSHOT 版本解析需要远程 maven-metadata.xml
        // 来确定 timestamped 实际文件名（Nexus 不支持 SNAPSHOT 直接路径）。
        session.setUpdatePolicy("daily");
        // 忽略 pom 里 <repositories> 声明的额外仓库 —— 只用我们显式配置的这些
        session.setIgnoreArtifactDescriptorRepositories(true);

        // 3. Build remote repos — URL 规范化 + 自动 discover 子仓库 + 去重 + 兜底 Maven Central
        this.remoteRepos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (remoteRepoUrls != null) {
            for (String rawUrl : remoteRepoUrls) {
                for (String url : normalizeRepoUrl(rawUrl)) {
                    if (url == null) continue;
                    String trimmed = url.trim();
                    if (trimmed.isBlank() || !seen.add(trimmed)) continue;
                    // 用 URL 里的 host:port + 仓库名做 repo ID
                    String id = "repo-" + trimmed.replaceAll("[^a-zA-Z0-9.-]", "_");
                    if (id.length() > 40) id = id.substring(0, 40);
                    this.remoteRepos.add(new RemoteRepository.Builder(id, "default", trimmed).build());
                }
            }
        }
        // 兜底：没有任何仓库就加 Maven Central
        if (this.remoteRepos.isEmpty()) {
            this.remoteRepos.add(new RemoteRepository.Builder("central", "default",
                    "https://repo.maven.apache.org/maven2/").build());
        }

        // Clamp + 创建下载线程池
        int c = Math.max(1, downloadConcurrency);
        this.downloadConcurrency = c;
        this.downloadPool = Executors.newFixedThreadPool(c, r -> {
            Thread t = new Thread(r, "mvn-download");
            t.setDaemon(true);
            return t;
        });

        log.info("[MavenPluginResolver] Initialized (localRepo={}, remoteRepos={}, downloadConcurrency={})",
                localRepo,
                remoteRepos.stream().map(r -> r.getId() + ":" + r.getUrl()).toList(),
                c);
    }

    /**
     * 关闭下载线程池 —— PluginCenter.shutdown() 调用。
     * 不关闭的话 daemon 线程会随着 JVM 退出自动清理，但显式 shutdown 更规范。
     */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        downloadPool.shutdown();
        try {
            if (!downloadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                downloadPool.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            downloadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[MavenPluginResolver] downloadPool shut down");
    }

    /**
     * 把用户传入的 Maven 仓库 URL 规范化。
     * <p>
     * 支持以下输入形式，自动展开为完整仓库 URL：
     * <ul>
     *   <li>{@code https://nexus.tapdata.net} ← Nexus base URL，自动展开为 snapshots + public</li>
     *   <li>{@code https://nexus.tapdata.net/} ← 同上（带尾斜杠）</li>
     *   <li>{@code https://nexus.tapdata.net/repository/maven-public/} ← 完整路径，直接保留</li>
     *   <li>{@code https://nexus.tapdata.net/repository/maven-snapshots/} ← 同上</li>
     *   <li>{@code https://artifactory.company.com/artifactory/maven-virtual/} ← Artifactory 完整路径，直接保留</li>
     *   <li>{@code https://repo.maven.apache.org/maven2/} ← 已经是 Central 完整路径</li>
     * </ul>
     * <p>
     * 展开规则：
     * <ul>
     *   <li>如果 URL 路径里包含 {@code /repository/}（Nexus）或 {@code /artifactory/}（Artifactory）或 {@code /maven2/}（Central）—— 视为完整路径，直接返回</li>
     *   <li>否则视为 Nexus base URL —— 追加 {@code /repository/maven-snapshots/} + {@code /repository/maven-public/}（public 通常代理了 releases + central）</li>
     * </ul>
     */
    private static List<String> normalizeRepoUrl(String rawUrl) {
        if (rawUrl == null) return List.of();
        String url = rawUrl.trim();
        if (url.isBlank()) return List.of();

        // 已经是完整仓库路径 —— 直接返回
        if (url.contains("/repository/") || url.contains("/artifactory/") || url.contains("/maven2")) {
            return List.of(url.endsWith("/") ? url : url + "/");
        }

        // Nexus base URL → 自动 discover：snapshots + public (public 通常代理 releases + central)
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return List.of(
                base + "/repository/maven-snapshots/",
                base + "/repository/maven-public/"
        );
    }

    // ==================== 核心 API ====================

    /**
     * 解析单个坐标的 artifact（jar）。只拉这个 jar，不解析传递依赖。
     * <p>
     * SNAPSHOT 版本手动解析 —— 从 Nexus / 私服下载 maven-metadata.xml 获取 timestamp + buildNumber，
     * 替换成 timestamped 实际版本后再 resolveArtifact。这是因为某些私服（如 Nexus）不支持
     * Maven 标准的 SNAPSHOT 直接路径（只存 timestamped 文件名）。
     *
     * @param coordinate 格式 {@code groupId:artifactId:version}
     * @return jar 文件路径（可能已存在于本地仓库，也可能是新下载的）
     */
    public Path resolveArtifact(String coordinate) throws ResolutionException {
        Artifact artifact = parseCoordinate(coordinate);

        // SNAPSHOT 版本手动从 metadata 解析 timestamped 实际版本
        if (artifact.getVersion().endsWith("-SNAPSHOT")) {
            String resolvedVersion = resolveSnapshotVersion(artifact);
            if (resolvedVersion != null) {
                artifact = artifact.setVersion(resolvedVersion);
                log.info("[MavenPluginResolver] SNAPSHOT {} → resolved to {}", coordinate, resolvedVersion);
            }
        }

        // 先手动下载 jar + pom 到本地 Maven 仓库 —— 绕过 Aether HttpClient 的代理/SSL 问题
        // （Aether 不读 JVM https.proxy* 系统属性，内部 Apache HttpClient 5 有独立配置）
        // 如果手动下载成功或本地已有，直接返回路径，跳过 Maven Resolver（它还会尝试网络）
        Path locallyResolved = tryManualDownload(artifact);
        if (locallyResolved != null) {
            log.info("[MavenPluginResolver] Using locally available jar: {}", locallyResolved);
            return locallyResolved;
        }

        // 最后兜底：让 Maven Resolver 自己 resolve（可能失败，但保证不丢任何能 resolve 的情况）
        try {
            ArtifactRequest request = new ArtifactRequest(artifact, remoteRepos, null);
            ArtifactResult result = repositorySystem.resolveArtifact(session, request);
            Path jarPath = result.getArtifact().getFile().toPath();
            log.info("[MavenPluginResolver] Resolved artifact {} → {}", coordinate, jarPath);
            return jarPath;
        } catch (Exception e) {
            throw new ResolutionException("Failed to resolve artifact: " + coordinate, e);
        }
    }

    /** 尝试手动下载，成功或本地已存在时返回 jar 路径，否则返回 null。 */
    private Path tryManualDownload(Artifact artifact) {
        // 先检查本地 Maven 仓库是否已经有了（包括可能的 timestamped 变体）
        Path localJar = findLocalJar(artifact);
        if (localJar != null) {
            log.debug("[MavenPluginResolver] Found existing jar locally: {}", localJar);
            return localJar;
        }

        // 手动下载到本地仓库
        manuallyDownloadArtifactToLocal(artifact);
        return findLocalJar(artifact);
    }

    /** 查找本地仓库中是否已有对应 artifact 的 jar（处理 SNAPSHOT timestamped 变体）。 */
    private Path findLocalJar(Artifact artifact) {
        String groupPath = artifact.getGroupId().replace('.', '/');
        String version = artifact.getVersion();
        String artifactId = artifact.getArtifactId();
        String artifactPath = groupPath + "/" + artifactId;

        // 可能的本地目录名：
        // 1. timestamped SNAPSHOT (e.g., 1.0.0-20261103.090048-1)
        // 2. 原始 SNAPSHOT (e.g., 1.0.0-SNAPSHOT)
        // 3. release 版本 (e.g., 1.0.0)
        List<String> possibleDirs = new ArrayList<>();
        possibleDirs.add(version);
        possibleDirs.add(version + "-SNAPSHOT");
        possibleDirs.add(version.replaceAll("-\\d{8}\\.\\d{6}-\\d+$", "") + "-SNAPSHOT");

        for (String dir : possibleDirs) {
            Path jarPath = localRepoDir.resolve(artifactPath + "/" + dir + "/" + artifactId + "-" + version + ".jar");
            if (Files.exists(jarPath)) return jarPath;
            // 也试试原始 SNAPSHOT 文件名（不带 timestamp）
            Path snapPath = localRepoDir.resolve(artifactPath + "/" + dir + "/" + artifactId + "-"
                    + (dir.contains("-SNAPSHOT") ? dir : version) + ".jar");
            if (Files.exists(snapPath)) return snapPath;
        }

        // 遍历扫描 artifact 目录下所有子目录
        try (var walk = Files.walk(localRepoDir.resolve(artifactPath), 2)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                    .findFirst().orElse(null);
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * 手动用 HttpsURLConnection（带代理 + trust-all SSL）下载 artifact 的 jar 和 pom
     * 到本地 Maven 仓库的正确路径。
     * <p>
     * 注意 SNAPSHOT 版本的路径差异：
     * <ul>
     *   <li>远端 Nexus: {@code .../1.0.0-SNAPSHOT/artifact-1.0.0-20261103.090048-1.jar}（目录是 SNAPSHOT 版本，文件名是 timestamped）</li>
     *   <li>本地仓库: {@code .../1.0.0-20261103.090048-1/artifact-1.0.0-20261103.090048-1.jar}（目录和文件名都是 timestamped）</li>
     * </ul>
     */
    private void manuallyDownloadArtifactToLocal(Artifact artifact) {
        String groupPath = artifact.getGroupId().replace('.', '/');
        String version = artifact.getVersion();
        // timestamped SNAPSHOT 版本匹配 \d+.\d+.\d+-\d{8}.\d{6}-\d+
        boolean isTimestampedSnapshot = version.matches("\\d+\\.\\d+\\.\\d+-\\d{8}\\.\\d{6}-\\d+");
        // 远端路径：如果是 timestamped SNAPSHOT，目录名还原为 *-SNAPSHOT
        String remoteVersionDir = isTimestampedSnapshot
                ? version.substring(0, version.indexOf('-')) + "-SNAPSHOT"
                : version;
        // 本地路径：timestamped 版本号直接当目录名（Maven Resolver 的标准行为）
        String localVersionDir = version;

        String artifactPath = groupPath + "/" + artifact.getArtifactId();
        String jarFilename = artifact.getArtifactId() + "-" + version + ".jar";
        String pomFilename = artifact.getArtifactId() + "-" + version + ".pom";
        Path localDir = localRepoDir.resolve(artifactPath + "/" + localVersionDir);
        Path localJar = localDir.resolve(jarFilename);
        Path localPom = localDir.resolve(pomFilename);

        // 如果本地已经有了，跳过
        if (Files.exists(localJar)) return;

        try {
            Files.createDirectories(localDir);
        } catch (IOException e) {
            log.warn("[MavenPluginResolver] Cannot create local dir {}: {}", localDir, e.getMessage());
            return;
        }

        // 创建 trust-all SSLSocketFactory
        javax.net.ssl.SSLSocketFactory socketFactory = null;
        try {
            TrustManager[] allTrust = { new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String s) { }
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String s) { }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, allTrust, new java.security.SecureRandom());
            socketFactory = ctx.getSocketFactory();
        } catch (Exception ignored) { }

        // 从每个 remote repo 下载 jar + pom
        for (RemoteRepository repo : remoteRepos) {
            String baseUrl = repo.getUrl().endsWith("/") ? repo.getUrl() : repo.getUrl() + "/";
            String repoArtifactPath = artifactPath + "/" + remoteVersionDir;

            // 下载 jar
            String jarUrl = baseUrl + repoArtifactPath + "/" + jarFilename;
            log.info("[MavenPluginResolver] Attempting manual download: {}", jarUrl);
            if (downloadUrlToFile(jarUrl, localJar, socketFactory)) {
                log.info("[MavenPluginResolver] Downloaded jar from {} → {}", jarUrl, localJar);
                // 尝试下载 pom（可选）
                String pomUrl = baseUrl + repoArtifactPath + "/" + pomFilename;
                downloadUrlToFile(pomUrl, localPom, socketFactory);
                return;
            }
        }
        log.warn("[MavenPluginResolver] Failed to manually download {} from any repo", artifact);
    }

    /** 用 HttpURLConnection 下载 URL 到本地文件。自动设置系统代理 + trust-all SSL。 */
    private boolean downloadUrlToFile(String urlStr, Path target, javax.net.ssl.SSLSocketFactory socketFactory) {
        try {
            java.net.URL url = new java.net.URL(urlStr);
            boolean isHttps = url.getProtocol().equals("https");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            if (isHttps && socketFactory != null) {
                javax.net.ssl.HttpsURLConnection hc = (javax.net.ssl.HttpsURLConnection) conn;
                hc.setSSLSocketFactory(socketFactory);
                hc.setHostnameVerifier((hostname, session) -> true);
            }
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200) return false;

            try (java.io.InputStream in = conn.getInputStream();
                 java.io.OutputStream out = Files.newOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            log.debug("[MavenPluginResolver] Download {} failed: {}", urlStr, e.getMessage());
            // 清理可能写了一半的文件
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            return false;
        }
    }

    /**
     * 从第一个可用的 remote repo 下载 maven-metadata.xml，解析出 SNAPSHOT 的 timestamped 实际版本。
     * <p>
     * 例如 metadata 里 {@code <snapshotVersion><value>1.0.0-20261103.090048-1</value></snapshotVersion>}
     * → 返回 {@code "1.0.0-20261103.090048-1"}。
     *
     * @return timestamped 版本号，或者 null 如果任何 repo 都没 metadata
     */
    private String resolveSnapshotVersion(Artifact artifact) {
        String groupPath = artifact.getGroupId().replace('.', '/');
        String metaPath = groupPath + "/" + artifact.getArtifactId()
                + "/" + artifact.getVersion() + "/maven-metadata.xml";

        // 创建一个显式的 trust-all SSLSocketFactory（确保 URL.openStream 也信任 Nexus 证书）
        javax.net.ssl.SSLSocketFactory trustAllSocketFactory;
        try {
            TrustManager[] allTrust = { new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String s) { }
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String s) { }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, allTrust, new java.security.SecureRandom());
            trustAllSocketFactory = ctx.getSocketFactory();
        } catch (Exception e) {
            log.warn("[MavenPluginResolver] Failed to create trust-all SSL factory: {}", e.getMessage());
            trustAllSocketFactory = null;
        }

        for (RemoteRepository repo : remoteRepos) {
            String metaUrl = repo.getUrl() + metaPath;
            try {
                log.info("[MavenPluginResolver] Fetching SNAPSHOT metadata from {}", metaUrl);
                java.net.URL url = new java.net.URL(metaUrl);
                // 用 HttpsURLConnection 显式设置 trust-all SSL —— 绕过 JVM truststore
                if (url.getProtocol().equals("https") && trustAllSocketFactory != null) {
                    javax.net.ssl.HttpsURLConnection conn = (javax.net.ssl.HttpsURLConnection) url.openConnection();
                    conn.setSSLSocketFactory(trustAllSocketFactory);
                    conn.setHostnameVerifier((hostname, session) -> true);
                    conn.connect();
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        log.warn("[MavenPluginResolver] SNAPSHOT metadata HTTP {} from {}", code, metaUrl);
                        continue;
                    }
                    try (java.io.InputStream is = conn.getInputStream()) {
                        return parseSnapshotVersionXml(is, metaUrl);
                    }
                } else {
                    try (java.io.InputStream is = url.openStream()) {
                        return parseSnapshotVersionXml(is, metaUrl);
                    }
                }
            } catch (Exception e) {
                log.warn("[MavenPluginResolver] SNAPSHOT metadata failed at {}: {} - {}",
                        metaUrl, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.warn("[MavenPluginResolver] Could not resolve SNAPSHOT version for {} from any remote repo — try direct path",
                artifact);
        return null;
    }

    /** 从 metadata.xml 流里提取 jar 对应的 timestamped 版本号。 */
    private String parseSnapshotVersionXml(java.io.InputStream is, String metaUrl) throws java.io.IOException {
        byte[] bytes = is.readAllBytes();
        String xml = new String(bytes);
        log.info("[MavenPluginResolver] Metadata from {}: {} bytes", metaUrl, bytes.length);
        var matcher = java.util.regex.Pattern.compile(
                "<snapshotVersion>.*?<extension>jar</extension>.*?<value>(.+?)</value>",
                java.util.regex.Pattern.DOTALL).matcher(xml);
        if (matcher.find()) {
            String resolved = matcher.group(1);
            log.info("[MavenPluginResolver] SNAPSHOT resolved: {} → {}", metaUrl, resolved);
            return resolved;
        }
        log.warn("[MavenPluginResolver] Metadata found but no jar snapshotVersion at {}", metaUrl);
        return null;
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

        // === Stage 1: 并发解析所有直接 artifacts ===
        List<String> uniqueCoords = coordinates.stream()
                .filter(seen::add)  // 按 Set 去重
                .toList();

        log.info("[MavenPluginResolver] Resolving {} direct artifact(s) with {} threads...",
                uniqueCoords.size(), downloadConcurrency);

        List<CompletableFuture<Path>> directFutures = uniqueCoords.stream()
                .map(coord -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return resolveArtifact(coord);
                    } catch (ResolutionException e) {
                        throw new CompletionException(e);
                    }
                }, downloadPool))
                .toList();

        // 收集结果 —— 保持坐标声明顺序
        for (int i = 0; i < uniqueCoords.size(); i++) {
            try {
                Path p = directFutures.get(i).get();
                result.add(p);
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException || e instanceof CompletionException
                        ? e.getCause() : e;
                throw new ResolutionException("Failed to resolve artifact: " + uniqueCoords.get(i), cause);
            }
        }

        // === Stage 2: collect + resolve 传递依赖（串行 — Maven Resolver 整体操作）===
        // 注意：Maven Resolver 对本地已有的 pom 做 "verifying that is downloadable from" 慢速远端验证
        // 是已知问题（本地 metadata _remote.repositories 记录的 repo ID 与 session 配置不匹配）。
        // 我们已经：① 设了 setUpdatePolicy("daily") ② setIgnoreArtifactDescriptorRepositories(true)
        // ③ 构造时清理 _remote.repositories。但 collect 阶段仍可能因 profile activation（需 Java 版本属性）
        //    或 parent pom 缺失而失败，更糟的是 HTTP 连接 hang 住不返回。
        //
        // 解决：给 collect 阶段加 30s 超时 —— 超时/失败都 fallback 到 direct jars only。
        // 生产场景下 step 模块传递依赖（Jackson/SLF4J 等）宿主 fat jar 已有，
        // extra_deps 动态下载的 artifact（如 sqlite-jdbc）本身也没有 compile scope 传递依赖，够用。
        try {
            CollectRequest collectRequest = new CollectRequest();
            for (String coord : coordinates) {
                collectRequest.addDependency(new Dependency(parseCoordinate(coord), "compile"));
            }
            collectRequest.setRepositories(remoteRepos);

            // 用单独线程跑 collect，超时直接 fallback —— 避免 Maven Resolver HTTP hang
            ExecutorService oneShot = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mvn-collect");
                t.setDaemon(true);
                return t;
            });
            Future<CollectResult> collectFuture = oneShot.submit(() ->
                    repositorySystem.collectDependencies(session, collectRequest));
            CollectResult collectResult;
            try {
                collectResult = collectFuture.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                collectFuture.cancel(true);
                oneShot.shutdownNow();
                log.warn("[MavenPluginResolver] collectDependencies timed out after 30s — " +
                        "falling back to direct jars only (transitive deps skipped)");
                return result;
            }
            oneShot.shutdownNow();

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
