package io.tapdata.it.l3.env;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 部署编排器：内聚一次完整产品部署的全部动作（各 {@link TapdataDeployment} 模式共用）：
 * <ol>
 *   <li>创建带 {@code run-id} 标签的命名空间（隔离多版本并行）；</li>
 *   <li>按需创建 image-pull docker-registry Secret；</li>
 *   <li>为 TM / apiserver 分配空闲 NodePort（{@link NodePortAllocator}）；</li>
 *   <li>合并公共 + 模式变量，逐个渲染（{@link ManifestRenderer}）并 apply 清单；</li>
 *   <li>等待各 Deployment 就绪 + TM {@code /health} HTTP 探活双闸；</li>
 *   <li>解析节点可达地址，返回 {@link ProductEnvironment}。</li>
 * </ol>
 */
class DeploymentOrchestrator {

    private static final Logger LOG = Logger.getLogger(DeploymentOrchestrator.class.getName());

    private final L3EnvConfig cfg;
    private final KubernetesClient client;
    private final TapdataDeployment deployment;
    private final NodePortAllocator allocator;

    DeploymentOrchestrator(L3EnvConfig cfg, KubernetesClient client, TapdataDeployment deployment) {
        this.cfg = cfg;
        this.client = client;
        this.deployment = deployment;
        this.allocator = new NodePortAllocator(client);
    }

    ProductEnvironment run() {
        String namespace = cfg.resolveNamespace();
        LOG.info("Deploying TapData [" + deployment.mode() + "] into namespace " + namespace
                + " (run " + cfg.runId() + ")");

        createNamespace(namespace);
        String pullSecretBlock = ensureImagePullSecret(namespace);

        Set<Integer> reserved = new HashSet<>();
        int tmNodePort = allocator.allocate(cfg, "TM", reserved, cfg.nodePortTm());
        reserved.add(tmNodePort);
        int apiNodePort = allocator.allocate(cfg, "APISERVER", reserved, cfg.nodePortApi());

        Map<String, String> context = buildContext(namespace, tmNodePort, apiNodePort, pullSecretBlock);

        for (String file : deployment.resourceFiles()) {
            String path = "deployments/" + deployment.mode().resourceDir() + "/" + file;
            String yaml = ManifestRenderer.render(path, context);
            applyAll(namespace, yaml, file);
        }

        awaitReady(namespace);
        String nodeAddress = resolveNodeAddress();
        String tmRoot = TmEndpoints.root(cfg, nodeAddress, tmNodePort);
        awaitTmHealthy(tmRoot);

        LOG.info("TapData environment ready: TM at " + TmEndpoints.apiBase(tmRoot));
        return new ProductEnvironment(cfg, client, allocator, namespace, nodeAddress, tmNodePort, apiNodePort);
    }

    // ---- 命名空间 ----

    private void createNamespace(String namespace) {
        client.resource(new NamespaceBuilder()
                .withNewMetadata()
                .withName(namespace)
                .addToLabels("app.kubernetes.io/managed-by", "tapdata-l3")
                .addToLabels("tapdata.io/run-id", cfg.runId())
                .endMetadata()
                .build()).createOrReplace();
    }

    // ---- image-pull secret（可选）----

    /** 返回注入到各 Deployment 的 {@code imagePullSecrets} YAML 块；无需时返回空串。 */
    private String ensureImagePullSecret(String namespace) {
        if (!cfg.needsImagePullSecret()) {
            return "";
        }
        String registry = cfg.imageRegistry();
        String user = cfg.registryUsername();
        String pass = cfg.registryPassword();
        String auth = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        String dockerCfg = "{\"auths\":{\"" + registry + "\":{\"username\":\"" + user
                + "\",\"password\":\"" + pass + "\",\"auth\":\"" + auth + "\"}}}";
        String encoded = Base64.getEncoder().encodeToString(dockerCfg.getBytes(StandardCharsets.UTF_8));

        Secret secret = new SecretBuilder()
                .withNewMetadata().withName(cfg.imagePullSecretName()).withNamespace(namespace).endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .addToData(".dockerconfigjson", encoded)
                .build();
        client.resource(secret).inNamespace(namespace).createOrReplace();
        return "imagePullSecrets:\n        - name: " + cfg.imagePullSecretName();
    }

    // ---- 渲染上下文（公共变量；模式变量随后合并）----

    private Map<String, String> buildContext(String namespace, int tmNodePort, int apiNodePort, String pullBlock) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("NAMESPACE", namespace);
        ctx.put("IMAGE", cfg.tapdataImage());
        ctx.put("MONGO_IMAGE", cfg.mongoImage());
        ctx.put("MONGO_HOST", "tapdata-mongodb");
        ctx.put("MONGO_PORT", "27017");
        ctx.put("MONGO_USER", cfg.mongoUser());
        ctx.put("MONGO_PASSWORD", cfg.mongoPassword());
        ctx.put("MONGO_DATABASE", cfg.mongoDatabase());
        ctx.put("MONGO_AUTH_SOURCE", cfg.mongoAuthSource());
        ctx.put("MONGO_URI", "tapdata-mongodb:27017/" + cfg.mongoDatabase()
                + "?authSource=" + cfg.mongoAuthSource());
        ctx.put("SERVER_SVC", "tapdata-server");
        ctx.put("TM_PORT", "3030");
        ctx.put("API_PORT", "3080");
        ctx.put("BACKEND_URL", "http://tapdata-server:3030/api/");
        ctx.put("NODEPORT_TM", String.valueOf(tmNodePort));
        ctx.put("NODEPORT_API", String.valueOf(apiNodePort));
        ctx.put("TZ", "Asia/Shanghai");
        ctx.put("JAVA_VERSION", "java17");
        ctx.put("LICENSE", cfg.license());
        ctx.put("LICENSE_HOST", cfg.licenseHost());
        ctx.put("IMAGE_PULL_SECRETS_BLOCK", pullBlock);
        ctx.putAll(deployment.context(cfg));
        return ctx;
    }

    private void applyAll(String namespace, String yaml, String file) {
        List<HasMetadata> resources = client.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .inNamespace(namespace)
                .createOrReplace();
        LOG.info("Applied " + file + " (" + resources.size() + " resource(s)) into " + namespace);
    }

    // ---- 就绪等待 ----

    private void awaitReady(String namespace) {
        long timeoutMs = cfg.readyTimeoutSec() * 1000L;
        List<Deployment> deployments = client.apps().deployments().inNamespace(namespace).list().getItems();
        for (Deployment dep : deployments) {
            String name = dep.getMetadata().getName();
            try {
                client.apps().deployments().inNamespace(namespace).withName(name)
                        .waitUntilReady(timeoutMs, TimeUnit.MILLISECONDS);
                LOG.info("Deployment ready: " + name);
            } catch (RuntimeException e) {
                // fabric8 6.x 的 waitUntilReady 不抛受检 InterruptedException；线程被中断时以
                // KubernetesClientException 形式返回，此处统一恢复中断标记后按失败处理
                if (e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Deployment " + name + " not ready within "
                        + cfg.readyTimeoutSec() + "s: " + e.getMessage(), e);
            }
        }
    }

    /** 探活 TM 根路径 {@code /health}（{@code IndexController}，免登录）；基址与用例侧保持一致。 */
    private void awaitTmHealthy(String tmRoot) {
        String url = tmRoot + "/health";
        long deadline = System.currentTimeMillis() + cfg.readyTimeoutSec() * 1000L;
        IllegalStateException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (httpOk(url)) {
                    return;
                }
                last = new IllegalStateException("TM /health not 2xx yet: " + url);
            } catch (Exception e) {
                last = new IllegalStateException("TM /health probe error: " + e.getMessage());
            }
            sleep(3_000);
        }
        throw new IllegalStateException("TM did not become healthy at " + url, last);
    }

    private static boolean httpOk(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        try {
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } finally {
            conn.disconnect();
        }
    }

    // ---- 节点可达地址 ----

    private String resolveNodeAddress() {
        String configured = cfg.nodeAddress();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        List<Node> nodes = client.nodes().list().getItems();
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No cluster nodes visible; set l3.node.address explicitly");
        }
        List<NodeAddress> addresses = nodes.get(0).getStatus().getAddresses();
        String external = pickAddress(addresses, "ExternalIP");
        if (external != null) {
            return external;
        }
        String internal = pickAddress(addresses, "InternalIP");
        if (internal != null) {
            return internal;
        }
        String hostname = pickAddress(addresses, "Hostname");
        return hostname != null ? hostname : "localhost";
    }

    private static String pickAddress(List<NodeAddress> addresses, String type) {
        if (addresses != null) {
            for (NodeAddress a : addresses) {
                if (type.equals(a.getType())) {
                    return a.getAddress();
                }
            }
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
