package io.tapdata.it.l3.env;

import io.tapdata.it.l3.api.TmJson;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * L3 环境配置：以与 {@code ConnectionConfigLoader} 一致的优先级加载——
 * <ol>
 *   <li>JSON 文件（{@code l3.env.config} 指向，classpath 优先，其次文件系统）作为基线；</li>
 *   <li>系统属性 {@code l3.<key>}；</li>
 *   <li>环境变量 {@code L3_<KEY>}（点转下划线并大写）；</li>
 *   <li>字段默认值。</li>
 * </ol>
 * 采用点分扁平键（如 {@code l3.image.tag} / {@code L3_IMAGE_TAG}），避免嵌套歧义，便于 CI 以 {@code -D}/env 注入。
 */
public final class L3EnvConfig {

    public static final String PROPERTY_PREFIX = "l3.";
    public static final String ENV_PREFIX = "L3_";
    /** 指向基线 JSON 配置的属性/环境变量名（{@code l3.env.config} / {@code L3_ENV_CONFIG}）。 */
    public static final String CONFIG_PATH_PROP = "l3.env.config";
    public static final String CONFIG_PATH_ENV = "L3_ENV_CONFIG";

    private static final String DEFAULT_LICENSE =
            "P3reDOP8DjonvFtUC6u/bIkgS7e7KMLArRmFIj378qWBbOxoyhiAa/8/lKS5Yb8BlUez0xBJad057PdU8R1oncFYuT+Vtmpidk08cy1Ytsrcf/7WdLE8e0lGdBzlVJqVg2mpl9ZM+IFjKkd76gr94gEYE0aJL44ZkQsJGnq3bNFCHm5EHMz44GfBGj23CdGuJAxVmHiHioapMiMAJyPXWk4bWX2VqZ3ffEwNLnM7ACkfTCzvPiXAbRYrtsLfzeFJ.A2cmWlNrntIoUChrH9Rhb7X41bhod56i4R4KZitoQXTU/A90FtNXYs4vIUd387/9XIPLlHz5H3me59GrZAzZF2H25KwXwFGjSl1TbowMDvLj4bRWYW6/Uh4R7dXxBxDCFyHQVh0y2R+5XRplA80uFsm8NdwO1xbUJtR8gvfOuJ4fNYDeojaJs65VVgbR2dgxDpeJOwtvbc7BFTIs48xTgILBE64mmYBQti9wmPDbxWyofL/XdEow/aLXDx7+CKxkh72GwBzO6DZwcqdTRPriat2B2AyEmsQSXuNHGCMSZBygMF6ZgAjNItq9RwR5yXsj2BGHJd8kz9JDYZ1Lxa/YeA==";

    private final Map<String, Object> base;

    private L3EnvConfig(Map<String, Object> base) {
        this.base = base;
    }

    /** 按默认优先级加载（自动发现 {@code l3.env.config} 指向的基线 JSON）。 */
    public static L3EnvConfig load() {
        return load(resolveConfigPath());
    }

    /** 指定基线 JSON 路径（可为 null，仅用属性/环境/默认值）。 */
    public static L3EnvConfig load(String jsonPath) {
        Map<String, Object> base = new LinkedHashMap<>();
        if (jsonPath != null && !jsonPath.isEmpty()) {
            base.putAll(readJson(jsonPath));
        }
        return new L3EnvConfig(base);
    }

    private static String resolveConfigPath() {
        String p = System.getProperty(CONFIG_PATH_PROP);
        if (p != null && !p.isEmpty()) {
            return p;
        }
        String e = System.getenv(CONFIG_PATH_ENV);
        return (e != null && !e.isEmpty()) ? e : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String path) {
        try {
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
            if (in == null) {
                in = new FileInputStream(path);
            }
            try (InputStream r = in) {
                String json = new String(r.readAllBytes(), StandardCharsets.UTF_8);
                if (json.trim().isEmpty()) {
                    return new LinkedHashMap<>();
                }
                Map<String, Object> root = TmJson.read(json, Map.class);
                // 允许把配置包在 {"l3": {...}} 下
                Object l3 = root.get("l3");
                if (l3 instanceof Map) {
                    return new LinkedHashMap<>((Map<String, Object>) l3);
                }
                return new LinkedHashMap<>(root);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load L3 env config from " + path + ": " + ex.getMessage(), ex);
        }
    }

    // ---- 取值原语：sysprop > env > json > default ----

    String raw(String key) {
        String sys = System.getProperty(PROPERTY_PREFIX + key);
        if (sys != null && !sys.isEmpty()) {
            return sys;
        }
        String env = System.getenv(ENV_PREFIX + key.toUpperCase().replace('.', '_'));
        if (env != null && !env.isEmpty()) {
            return env;
        }
        Object json = base.get(key);
        if (json != null) {
            return String.valueOf(json);
        }
        return null;
    }

    public String str(String key, String def) {
        String v = raw(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    public int integer(String key, int def) {
        String v = raw(key);
        return (v == null || v.isEmpty()) ? def : Integer.parseInt(v.trim());
    }

    public long lng(String key, long def) {
        String v = raw(key);
        return (v == null || v.isEmpty()) ? def : Long.parseLong(v.trim());
    }

    public boolean bool(String key, boolean def) {
        String v = raw(key);
        return (v == null || v.isEmpty()) ? def : Boolean.parseBoolean(v.trim());
    }

    // ---- 具名字段（点分键，CI 友好）----

    public String kubeconfigPath() {
        return str("kubeconfig.path", "/etc/rancher/k3s/k3s.yaml");
    }

    /** 内联 kubeconfig 内容（优先于 {@link #kubeconfigPath()}）。 */
    public String kubeconfigContent() {
        return str("kubeconfig.content", null);
    }

    /** 指定 kubeconfig 中的上下文名（多集群 kubeconfig 用，如本地 {@code k3sdev}）；留空取 current-context。 */
    public String kubeconfigContext() {
        return str("kubeconfig.context", null);
    }

    /** 覆盖 API Server 地址（本地经 SSH 隧道时设为 {@code https://127.0.0.1:6443}）。 */
    public String apiServerUrl() {
        return str("apiserver.url", null);
    }

    public String authToken() {
        return str("auth.token", null);
    }

    public boolean trustCerts() {
        return bool("trust.certs", true);
    }

    public String imageRegistry() {
        return str("image.registry", "");
    }

    public String imageRepository() {
        return str("image.repository", "tapdata8/tapdata");
    }

    /** 制品镜像 tag（绑定不可变产物，如构建 SHA）。 */
    public String imageTag() {
        return str("image.tag", "latest");
    }

    /** 完整镜像引用：{@code [registry/]repository:tag}。 */
    public String tapdataImage() {
        String reg = imageRegistry();
        String repo = imageRepository();
        String prefix = (reg == null || reg.isEmpty()) ? "" : reg.endsWith("/") ? reg : reg + "/";
        return prefix + repo + ":" + imageTag();
    }

    public String mongoImage() {
        return str("mongo.image", "mongo:6.0");
    }

    /** 镜像仓库登录用户名（为空则不生成 image-pull Secret）。 */
    public String registryUsername() {
        return str("registry.username", null);
    }

    /** 镜像仓库登录口令。 */
    public String registryPassword() {
        return str("registry.password", null);
    }

    /** 生成的 image-pull Secret 名称。 */
    public String imagePullSecretName() {
        return str("image.pull.secret", "registry-auth");
    }

    /** 是否需要为镜像拉取创建 docker-registry Secret（仓库与凭据均提供时）。 */
    public boolean needsImagePullSecret() {
        String reg = imageRegistry();
        return reg != null && !reg.isEmpty() && registryUsername() != null && !registryUsername().isEmpty();
    }

    public DeploymentMode deploymentMode() {
        return DeploymentMode.from(str("deployment.mode", DeploymentMode.SINGLE_NODE.name()));
    }

    public String namespacePrefix() {
        return str("namespace.prefix", "tapdata-l3");
    }

    /** 显式命名空间（给定则覆盖 prefix+runId 组合）。 */
    public String namespace() {
        return str("namespace", null);
    }

    public String runId() {
        return str("run.id", "run-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** 生效命名空间：显式 {@code namespace} 优先，否则 {@code <prefix>-<runId>}。 */
    public String resolveNamespace() {
        String ns = namespace();
        return (ns != null && !ns.isEmpty()) ? ns : namespacePrefix() + "-" + runId();
    }

    public int nodePortMin() {
        return integer("node.port.min", 30000);
    }

    public int nodePortMax() {
        return integer("node.port.max", 32767);
    }

    /** NodePort 租约 ConfigMap 所在命名空间（跨 run 全局，避免端口竞争）。 */
    public String leaseNamespace() {
        return str("lease.namespace", "default");
    }

    /**
     * TM Service 的固定 NodePort（{@code 0} 表示由 {@link NodePortAllocator} 动态分配）。
     * <p>本地经 SSH 隧道访问时必须固定：隧道只能预先转发已知端口
     * （{@code ssh -C -L 6443:<nodeIP>:6443 -L <本值>:<nodeIP>:<本值> <jump>}）。
     */
    public int nodePortTm() {
        return integer("node.port.tm", 0);
    }

    /** API Server Service 的固定 NodePort（{@code 0} 表示动态分配），语义同 {@link #nodePortTm()}。 */
    public int nodePortApi() {
        return integer("node.port.apiserver", 0);
    }

    /** 构造 TM 外部访问地址用的节点可达地址（留空则自动取首个节点地址）。 */
    public String nodeAddress() {
        return str("node.address", null);
    }

    public boolean reuseExisting() {
        return bool("reuse.existing", false);
    }

    /**
     * 已部署环境下显式指定 TM 访问基址（覆盖由「节点地址 + NodePort」推导的结果）。
     * <p>典型场景：本地笔记本只能经 SSH 隧道访问集群，NodePort 端口以隧道映射到
     * {@code 127.0.0.1:<固定端口>}，此时配 {@code l3.node.port.tm} + {@code l3.tm.base.url=http://127.0.0.1:<端口>}。
     */
    public String tmBaseUrl() {
        return str("tm.base.url", null);
    }

    public String tmToken() {
        return str("tm.token", null);
    }

    public String loginEmail() {
        return str("login.email", "admin@admin.com");
    }

    public String loginPassword() {
        return str("login.password", "admin");
    }

    public int readyTimeoutSec() {
        return integer("ready.timeout.sec", 600);
    }

    /** 等待任务进入终态（complete/error/...）的超时秒数，全量同步大数据量时上调。 */
    public int taskTimeoutSec() {
        return integer("task.timeout.sec", 900);
    }

    /** 状态轮询间隔毫秒（任务状态、连接建模进度共用）。 */
    public int pollIntervalMs() {
        return integer("poll.interval.ms", 5_000);
    }

    public boolean teardownOnExit() {
        return bool("teardown.on.exit", true);
    }

    public String license() {
        return str("license", DEFAULT_LICENSE);
    }

    public String licenseHost() {
        return str("license.host", "113.98.206.142:18080");
    }

    public String mongoUser() {
        return str("mongo.user", "root");
    }

    public String mongoPassword() {
        return str("mongo.password", "AbcDef123");
    }

    public String mongoDatabase() {
        return str("mongo.database", "tapdata");
    }

    public String mongoAuthSource() {
        return str("mongo.auth.source", "admin");
    }

    public int connectTimeoutMs() {
        return integer("connect.timeout.ms", 10_000);
    }

    public int readTimeoutMs() {
        return integer("read.timeout.ms", 60_000);
    }
}
