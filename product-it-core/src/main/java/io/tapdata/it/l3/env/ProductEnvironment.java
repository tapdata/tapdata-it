package io.tapdata.it.l3.env;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.tapdata.it.l3.api.TmApiClient;
import io.tapdata.it.l3.api.model.TmApiException;

import java.util.logging.Logger;

/**
 * 一次已就绪的 TapData 产品环境的句柄：暴露外部访问地址、创建已认证 {@link TmApiClient}、以及 teardown。
 * <p>
 * {@link AutoCloseable}：{@link #close()} 依据配置决定是否 {@link #teardown()}（CI 销毁 / 本地保留）。
 * 复用既有环境（{@code reuseExisting}）时 {@link #teardown()} 为空操作，不会删除非本框架创建的环境。
 */
public class ProductEnvironment implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ProductEnvironment.class.getName());

    private final L3EnvConfig cfg;
    private final KubernetesClient client;      // reuseExisting 时为 null
    private final NodePortAllocator allocator;   // reuseExisting 时为 null
    private final String namespace;
    private final String tmBaseUrl;
    private final String apiserverUrl;
    private final int tmNodePort;
    private final boolean deployed;              // 本框架是否真正部署（决定 teardown 行为）

    /** 部署成功后由 {@link DeploymentOrchestrator} 调用。 */
    ProductEnvironment(L3EnvConfig cfg, KubernetesClient client, NodePortAllocator allocator,
                       String namespace, String nodeAddress, int tmNodePort, int apiNodePort) {
        this.cfg = cfg;
        this.client = client;
        this.allocator = allocator;
        this.namespace = namespace;
        this.tmNodePort = tmNodePort;
        this.deployed = true;
        // 本地只能经 SSH 隧道访问集群时，NodePort 的节点地址不可达：显式配 l3.tm.base.url 指向隧道本地端
        this.tmBaseUrl = TmEndpoints.apiBase(TmEndpoints.root(cfg, nodeAddress, tmNodePort));
        this.apiserverUrl = apiNodePort > 0 ? "http://" + nodeAddress + ":" + apiNodePort : null;
    }

    private ProductEnvironment(L3EnvConfig cfg, String tmBaseUrl) {
        this.cfg = cfg;
        this.client = null;
        this.allocator = null;
        this.namespace = cfg.resolveNamespace();
        this.tmNodePort = 0;
        this.deployed = false;
        this.tmBaseUrl = TmEndpoints.apiBase(tmBaseUrl);
        this.apiserverUrl = null;
    }

    /** 复用既有环境：不部署、不持有 K8s client、teardown 空操作。 */
    static ProductEnvironment reused(L3EnvConfig cfg, String tmBaseUrl) {
        return new ProductEnvironment(cfg, tmBaseUrl);
    }

    public L3EnvConfig config() {
        return cfg;
    }

    public String namespace() {
        return namespace;
    }

    /** TM 外部访问基址，形如 {@code http://<nodeIP>:<nodePortTM>/api}。 */
    public String tmBaseUrl() {
        return tmBaseUrl;
    }

    public String apiserverUrl() {
        return apiserverUrl;
    }

    public int tmNodePort() {
        return tmNodePort;
    }

    public KubernetesClient client() {
        return client;
    }

    /**
     * 基于本环境创建一个已认证的 TM API 客户端：
     * 预置 token（{@code l3.tm.token}）优先，否则账密登录（fresh TM 引导可能有延迟，内置有限重试）。
     */
    public TmApiClient openApiClient() {
        TmApiClient api = new TmApiClient(tmBaseUrl, cfg.connectTimeoutMs(), cfg.readTimeoutMs());
        String token = cfg.tmToken();
        if (token != null && !token.isEmpty()) {
            return api.withToken(token);
        }
        TmApiException last = null;
        for (int attempt = 1; attempt <= 12; attempt++) {
            try {
                return api.login(cfg.loginEmail(), cfg.loginPassword());
            } catch (TmApiException e) {
                last = e;
                LOG.info("TM login attempt " + attempt + "/12 failed: " + e.getMessage());
                sleep(5_000);
            }
        }
        throw new IllegalStateException("Cannot authenticate to TM at " + tmBaseUrl
                + " with configured credentials", last);
    }

    @Override
    public void close() {
        if (cfg.teardownOnExit() && deployed) {
            teardown();
        }
    }

    /** 删除本框架创建的命名空间（级联清理全部资源）并释放 NodePort 租约；复用模式下为空操作。 */
    public void teardown() {
        if (!deployed) {
            LOG.info("Reuse-existing environment; skip teardown of namespace " + namespace);
            return;
        }
        try {
            if (allocator != null) {
                allocator.release(cfg);
            }
        } catch (RuntimeException e) {
            LOG.warning("Release leases on teardown failed: " + e.getMessage());
        }
        if (client != null && namespace != null) {
            try {
                client.namespaces().withName(namespace).delete();
                LOG.info("Deleted namespace " + namespace);
            } catch (RuntimeException e) {
                LOG.warning("Delete namespace " + namespace + " failed: " + e.getMessage());
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
