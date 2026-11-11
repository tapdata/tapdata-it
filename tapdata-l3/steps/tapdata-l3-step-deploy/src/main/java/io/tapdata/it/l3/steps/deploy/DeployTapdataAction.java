package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.BaseStep;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * deploy_tapdata —— 远程 K8s 集群部署 TapData 多节点集群（TM + Engine + ApiServer）。
 *
 * 完整流程：
 * <ol>
 *   <li>校验参数 / 生成 namespace</li>
 *   <li>上传 Dockerfile + Helm chart 到远程 /tmp/tapdata-deploy/</li>
 *   <li>远程：curl 下载 tar.gz → 解压 → docker build → docker push</li>
 *   <li>远程：创建 namespace + imagePullSecret</li>
 *   <li>渲染 values.yaml（基于 deploy-on-kubernetes/values.yaml.tmpl），上传到远程</li>
 *   <li>远程：helm install --wait</li>
 *   <li>等待所有 TapData Pod ready</li>
 * </ol>
 *
 * <p>MongoDB 环境**不**由本 step 负责安装（由其他 step 创建后通过 mongodb_uri 传入）。
 *
 * <pre>
 * inputs:
 *   artifact_url: https://.../tapdata-7.0.0.tar.gz   # 必填
 *   harbor_registry: harbor.example.com               # 必填
 *   harbor_project: tapdata                           # 可选
 *   harbor_username: admin                            # 可选（私有仓库必填）
 *   harbor_password: xxx                              # 可选
 *   image_tag: 7.0.0                                  # 可选
 *   namespace:                                        # 可选，自动生成
 *   helm_release: tapdata                             # 可选
 *   mongodb_uri: mongodb://...                        # 必填
 *   mongodb_user: root                                # 可选
 *   mongodb_password: AbcDef123                       # 可选
 *   server_replicas: 1                                # 可选
 *   engine_replicas: 2                                # 可选
 *   apiserver_replicas: 1                             # 可选
 *   license_host: 113.98.206.142:18080                # 可选
 *   host: 113.98.206.142                              # 可选
 * </pre>
 */
public class DeployTapdataAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(DeployTapdataAction.class);

    /** 远程工作目录。 */
    private static final String REMOTE_BASE = "/tmp/tapdata-deploy";
    private static final String REMOTE_DOCKERFILE = REMOTE_BASE + "/Dockerfile";
    private static final String REMOTE_CHART = REMOTE_BASE + "/chart";
    private static final String REMOTE_ARTIFACTS = REMOTE_BASE + "/artifacts";
    private static final String REMOTE_BUILD = REMOTE_BASE + "/build";
    private static final String REMOTE_VALUES = REMOTE_BASE + "/values.yaml";

    @Override
    public String getActionName() {
        return "deploy_tapdata";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        DeployTapdataParameter p = config.getInputParameterConfig(DeployTapdataParameter.class);

        // ---- 校验 ----
        boolean hasArtifact = p.getArtifactUrl() != null && !p.getArtifactUrl().isBlank();
        boolean hasPrebuilt = p.getPrebuiltImage() != null && !p.getPrebuiltImage().isBlank();
        if (!hasArtifact && !hasPrebuilt) {
            throw new IllegalArgumentException("Either artifact_url or prebuilt_image must be provided");
        }
        if (p.getHarborRegistry() == null || p.getHarborRegistry().isBlank()) {
            throw new IllegalArgumentException("harbor_registry is required");
        }
        if (p.getMongodbUri() == null || p.getMongodbUri().isBlank()) {
            throw new IllegalArgumentException("mongodb_uri is required");
        }

        K8sClient k8s = new K8sClient(p.getHost());
        String namespace = NamespaceUtil.resolve(p.getNamespace());
        String helmRelease = (p.getHelmRelease() == null || p.getHelmRelease().isBlank()) ? "tapdata" : p.getHelmRelease();

        // 计算最终 image（prebuilt 优先，否则组装 harbor/project/tapdata:tag）
        String imageRepo;
        if (hasPrebuilt) {
            imageRepo = p.getPrebuiltImage();
        } else {
            String imageTag = resolveImageTag(p.getImageTag(), p.getArtifactUrl());
            imageRepo = p.getHarborRegistry() + "/" + p.getHarborProject() + "/tapdata:" + imageTag;
        }

        log.info("[deploy_tapdata] prebuilt={} artifact={}", hasPrebuilt,
                hasArtifact ? p.getArtifactUrl() : "(skipped)");
        log.info("[deploy_tapdata] harbor={}/{}", p.getHarborRegistry(), p.getHarborProject());
        log.info("[deploy_tapdata] image={}", imageRepo);
        log.info("[deploy_tapdata] namespace={} helmRelease={}", namespace, helmRelease);

        // 注册清理钩子
        final String cleanupNs = namespace;
        ctx.registerCleanup(() -> {
            try {
                log.info("[deploy_tapdata] cleanup: removing remote work dir");
                k8s.exec("rm -rf " + REMOTE_BASE);
            } catch (Exception ignore) {}
        });

        // ---- 1. 远程前置检查 ----
        checkRemoteTools(k8s);

        // ---- 2. 准备远程工作目录 ----
        k8s.exec("rm -rf " + REMOTE_BASE + " && mkdir -p " + REMOTE_BASE + " " + REMOTE_ARTIFACTS + " " + REMOTE_BUILD);

        // ---- 3. 上传 Dockerfile（仅非 prebuilt 模式） ----
        if (!hasPrebuilt) {
            uploadDockerfile(k8s, p);
        }

        // ---- 4. 上传 Helm chart ----
        uploadHelmChart(k8s, p);

        if (!hasPrebuilt) {
            // ---- 5. 下载制品包 + 解压 ----
            k8s.exec("curl -fSL --retry 3 --retry-delay 2 " + shellQuote(p.getArtifactUrl()) +
                    " -o " + REMOTE_ARTIFACTS + "/tapdata.tar.gz", 600_000L);
            k8s.exec("tar -xzf " + REMOTE_ARTIFACTS + "/tapdata.tar.gz -C " + REMOTE_BUILD +
                    " --strip-components=1");
            k8s.exec("test -d " + REMOTE_BUILD + " && echo OK || echo EMPTY");

            // ---- 6. Docker build + push ----
            buildAndPushImage(k8s, p, imageRepo);
        } else {
            log.info("[deploy_tapdata] skipping artifact download + docker build (prebuilt_image={})", imageRepo);
        }

        // ---- 7. 创建 namespace + imagePullSecret ----
        k8s.exec("kubectl create namespace " + namespace +
                " --dry-run=client -o yaml | kubectl apply -f -");

        String pullSecretName = (p.getImagePullSecret() != null && !p.getImagePullSecret().isBlank())
                ? p.getImagePullSecret()
                : "tapdata-registry-auth";
        createImagePullSecret(k8s, p, namespace, pullSecretName);

        // ---- 8. 渲染 values.yaml 并上传 ----
        String valuesYaml = renderHelmValues(p, imageRepo, pullSecretName);
        Files.writeString(Files.createTempFile("tapdata-values-", ".yaml"), valuesYaml);
        File tmpValues = new File(System.getProperty("java.io.tmpdir"),
                Files.createTempFile("tapdata-values-", ".yaml").getFileName().toString());
        Files.writeString(tmpValues.toPath(), valuesYaml);
        k8s.scpTo(tmpValues.getAbsolutePath(), REMOTE_VALUES);
        tmpValues.delete();

        // ---- 9. helm upgrade --install（幂等：首次安装 / 重跑升级均可，values 变化触发滚动更新）----
        // --wait 会等 Pod Ready。chart 已配 startupProbe（900s 窗口），Ready 需等 TM 真正监听 3030
        // （实测冷启动约 7~8 分钟），所以 helm 超时必须大于 startupProbe 窗口。
        String helmInstall = String.format(
                "helm upgrade --install %s %s -n %s --create-namespace " +
                        "--wait --timeout 900s -f %s",
                helmRelease, REMOTE_CHART, namespace, REMOTE_VALUES);
        k8s.exec(helmInstall, 950_000L).assertSuccess("helm upgrade --install " + helmRelease);

        // ---- 10. 就绪断言：kubectl wait Ready + Running Pod 计数 ----
        // helm --wait 在 release 已存在且无变化时直接返回，不能作为就绪依据 —— 必须独立断言。
        K8sClient.ExecResult pods = k8s.exec(
                "kubectl -n " + namespace + " get pods " +
                        "-l app.kubernetes.io/instance=" + helmRelease +
                        " -o wide");
        if (pods.isSuccess()) {
            log.info("[deploy_tapdata] pods:\n{}", pods.stdout);
        }
        int minReadyPods = resolveMinReadyPods(p);
        try {
            assertPodsReady(k8s, namespace, minReadyPods);
        } catch (Exception e) {
            dumpDeployDiagnostics(k8s, namespace);
            throw e;
        }

        log.info("[deploy_tapdata] DONE — namespace={}", namespace);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("namespace", namespace);
        outputs.put("helmRelease", helmRelease);
        outputs.put("image", imageRepo);
        outputs.put("harborRegistry", p.getHarborRegistry());
        return success(outputs);
    }

    // ========================================================================
    // 就绪断言 + 失败诊断
    // ========================================================================

    /** min_ready_pods 显式指定优先；否则 = server + engine + apiserver 副本之和（至少 1）。 */
    private static int resolveMinReadyPods(DeployTapdataParameter p) {
        if (p.getMinReadyPods() != null && p.getMinReadyPods() >= 0) return p.getMinReadyPods();
        int total = nvl(p.getServerReplicas()) + nvl(p.getEngineReplicas()) + nvl(p.getApiserverReplicas());
        return Math.max(1, total);
    }

    private static int nvl(Integer v) {
        return v == null ? 1 : v;
    }

    /**
     * 真正的就绪断言（专有 namespace，用 --all 即可覆盖全部 TapData Pod）：
     * 1. kubectl wait --for=condition=Ready —— 所有 Pod 达到 Ready（0 Pod 时直接失败，杜绝空部署假成功）；
     * 2. Running Pod 计数 >= min_ready_pods —— 防止只起了部分组件。
     */
    private void assertPodsReady(K8sClient k8s, String namespace, int minReadyPods) throws Exception {
        K8sClient.ExecResult wait = k8s.exec(
                "kubectl -n " + namespace + " wait --for=condition=Ready pod --all --timeout=300s",
                320_000L);
        if (!wait.isSuccess()) {
            throw new RuntimeException("Pods not Ready within 300s in namespace '" + namespace
                    + "': " + wait.getStdoutTrimmed());
        }
        K8sClient.ExecResult count = k8s.exec(
                "kubectl -n " + namespace + " get pods --field-selector=status.phase=Running --no-headers | wc -l");
        int running = -1;
        try { running = Integer.parseInt(count.getStdoutTrimmed()); } catch (NumberFormatException ignored) {}
        if (running < minReadyPods) {
            throw new RuntimeException("Ready-pod count assertion failed: running=" + running
                    + ", expected >= " + minReadyPods + " in namespace '" + namespace + "'");
        }
        log.info("[deploy_tapdata] readiness OK — running pods={} (min={})", running, minReadyPods);
    }

    /** 就绪断言失败时 dump 诊断信息（pods/events/replicaset events）到 step 日志。 */
    private void dumpDeployDiagnostics(K8sClient k8s, String namespace) {
        log.warn("[deploy_tapdata] readiness assertion FAILED — dumping diagnostics for ns={}", namespace);
        String[] diags = {
                "kubectl -n " + namespace + " get pods -o wide",
                "kubectl -n " + namespace + " get events --sort-by=.lastTimestamp 2>/dev/null | tail -40",
                "kubectl -n " + namespace + " describe replicasets 2>/dev/null | grep -A8 'Events:' | head -60",
        };
        for (String cmd : diags) {
            try {
                K8sClient.ExecResult r = k8s.exec(cmd, 60_000L);
                log.warn("[deploy_tapdata] diag `${}`:\n{}", cmd, r.getStdoutTrimmed());
            } catch (Exception e) {
                log.warn("[deploy_tapdata] diag `{}` failed: {}", cmd, e.getMessage());
            }
        }
    }

    // ========================================================================
    // 远程工具检查
    // ========================================================================

    private void checkRemoteTools(K8sClient k8s) {
        K8sClient.ExecResult r = k8s.exec("for t in docker helm kubectl curl; do " +
                "command -v $t || echo \"MISSING:$t\"; done");
        if (r.isSuccess()) {
            if (r.stdout.contains("MISSING")) {
                throw new RuntimeException("Remote missing required tools: " + r.stdout.trim());
            }
            log.info("[deploy_tapdata] remote tools ok:\n{}", r.stdout);
        }
    }

    // ========================================================================
    // Dockerfile 上传
    // ========================================================================

    private void uploadDockerfile(K8sClient k8s, DeployTapdataParameter p) throws Exception {
        if (p.getDockerfilePath() != null && !p.getDockerfilePath().isBlank()) {
            // 用户指定了远程已有的 Dockerfile —— 检查存在即可
            k8s.exec("test -f " + p.getDockerfilePath() +
                    " && ln -sf " + p.getDockerfilePath() + " " + REMOTE_DOCKERFILE);
            return;
        }
        // 从 classpath resources/docker/Dockerfile 取出
        InputStream is = getClass().getClassLoader()
                .getResourceAsStream("docker/Dockerfile");
        if (is == null) {
            throw new RuntimeException("Built-in Dockerfile not found on classpath");
        }
        Path tmp = Files.createTempFile("tapdata-Dockerfile-", "");
        Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
        k8s.scpTo(tmp.toString(), REMOTE_DOCKERFILE);
        Files.deleteIfExists(tmp);
        log.info("[deploy_tapdata] uploaded Dockerfile -> remote");
    }

    // ========================================================================
    // Helm chart 上传
    // ========================================================================

    private void uploadHelmChart(K8sClient k8s, DeployTapdataParameter p) throws Exception {
        if (p.getHelmChartPath() != null && !p.getHelmChartPath().isBlank()) {
            k8s.exec("test -d " + p.getHelmChartPath() +
                    " && rm -rf " + REMOTE_CHART +
                    " && cp -r " + p.getHelmChartPath() + " " + REMOTE_CHART);
            return;
        }
        // 本地 source 路径：开发期 classpath 目录 or jar 解压出来的临时目录
        Path localChart = K8sClient.getClasspathDir("helm");
        boolean tempExtract = false;
        if (localChart == null || !Files.isDirectory(localChart)) {
            localChart = extractClasspathDir("helm");
            tempExtract = true;
        }
        // 直接 scp/cp 整个目录（K8sClient 自动判断本地/远程）
        k8s.exec("rm -rf " + REMOTE_CHART);
        k8s.scpTo(localChart.toString(), REMOTE_CHART);
        log.info("[deploy_tapdata] uploaded Helm chart from {} ({}) -> {}",
                localChart, Files.size(localChart), REMOTE_CHART);

        // 清理临时解压目录（jar 打包场景）
        if (tempExtract) {
            try (Stream<Path> walk = Files.walk(localChart)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignore) {} });
            }
        }
    }

    /**
     * 从 classpath 中把一个目录型资源解压到临时目录。
     * 对 jar 打包场景，通过 ClassLoader.getResources + URLClassLoader 遍历 jar entry 完成。
     * 简化实现：先尝试走 zipfs 或 JarFile。
     */
    private Path extractClasspathDir(String resourceDir) throws Exception {
        Path target = Files.createTempDirectory("tapdata-helm-");
        ClassLoader cl = getClass().getClassLoader();

        // 先判断是否在 jar 里
        var url = cl.getResource(resourceDir);
        if (url == null) {
            throw new RuntimeException("Resource not found: " + resourceDir);
        }
        if ("file".equals(url.getProtocol())) {
            // 开发期 —— 直接 cp -r
            Path src = Paths.get(url.toURI());
            try (Stream<Path> walk = Files.walk(src)) {
                walk.forEach(srcPath -> {
                    try {
                        Path rel = src.relativize(srcPath);
                        Path dstPath = target.resolve(rel.toString());
                        if (Files.isDirectory(srcPath)) {
                            Files.createDirectories(dstPath);
                        } else {
                            Files.copy(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            return target;
        }

        // jar:file:!/helm/ 形式 —— 用 JarFile
        String urlStr = url.toString();
        int jarEnd = urlStr.indexOf('!');
        String jarPath = urlStr.substring("jar:file:".length(), jarEnd);
        String entryPrefix = urlStr.substring(jarEnd + 2);

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(java.net.URLDecoder.decode(jarPath, java.nio.charset.StandardCharsets.UTF_8))) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(entryPrefix + "/") || entry.isDirectory()) continue;
                Path dst = target.resolve(name.substring(entryPrefix.length() + 1));
                Files.createDirectories(dst.getParent());
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }

    // ========================================================================
    // Docker build + push
    // ========================================================================

    private void buildAndPushImage(K8sClient k8s, DeployTapdataParameter p, String imageRepo) {
        StringBuilder script = new StringBuilder();

        // Harbor login（如果提供了用户名密码）
        boolean needsLogin = (p.getHarborUsername() != null && !p.getHarborUsername().isBlank());
        if (needsLogin) {
            script.append("docker login ").append(p.getHarborRegistry())
                    .append(" -u ").append(shellQuote(p.getHarborUsername()))
                    .append(" -p ").append(shellQuote(p.getHarborPassword() == null ? "" : p.getHarborPassword()))
                    .append(" || exit 1\n");
        }

        // Docker build
        script.append("echo '[deploy] docker build...'\n");
        script.append("docker build -t ").append(imageRepo)
                .append(" -f ").append(REMOTE_DOCKERFILE)
                .append(" ").append(REMOTE_BUILD).append(" || exit 1\n");

        // Docker push
        script.append("echo '[deploy] docker push...'\n");
        script.append("docker push ").append(imageRepo).append(" || exit 1\n");

        k8s.execScript(script.toString());
        log.info("[deploy_tapdata] build+push done: {}", imageRepo);
    }

    // ========================================================================
    // imagePullSecret
    // ========================================================================

    private void createImagePullSecret(K8sClient k8s, DeployTapdataParameter p,
                                       String namespace, String secretName) {
        if (p.getHarborUsername() == null || p.getHarborUsername().isBlank()) {
            log.info("[deploy_tapdata] no harbor auth configured — skipping imagePullSecret");
            return;
        }
        String cmd = String.format(
                "kubectl create secret docker-registry %s -n %s " +
                        "--docker-server=%s " +
                        "--docker-username=%s " +
                        "--docker-password=%s " +
                        "--dry-run=client -o yaml | kubectl apply -f -",
                secretName, namespace,
                p.getHarborRegistry(),
                shellQuote(p.getHarborUsername()),
                shellQuote(p.getHarborPassword() == null ? "" : p.getHarborPassword()));
        k8s.exec(cmd).assertSuccess("create imagePullSecret");
        log.info("[deploy_tapdata] imagePullSecret created: {}/{}", namespace, secretName);
    }

    // ========================================================================
    // values.yaml 渲染
    // ========================================================================

    private String renderHelmValues(DeployTapdataParameter p, String imageRepo, String pullSecretName) {
        boolean hasPullSecret = p.getHarborUsername() != null && !p.getHarborUsername().isBlank();
        boolean hasLicenseHost = p.getLicenseHost() != null && !p.getLicenseHost().isBlank();
        String backendUrl = (p.getBackendUrl() != null && !p.getBackendUrl().isBlank())
                ? p.getBackendUrl()
                : "http://tapdata-server-svr:3030/api/";

        StringBuilder sb = new StringBuilder(4096);
        sb.append("# Auto-generated by deploy_tapdata step\n");
        sb.append("global:\n");
        // 设为 harbor registry —— helpers.tpl 会拼 "registry/repository:tag" 形成完整镜像路径
        sb.append("  imageRegistry: \"").append(p.getHarborRegistry()).append("\"\n");
        if (hasPullSecret) {
            sb.append("  imagePullSecret:\n");
            sb.append("    enabled: true\n");
            sb.append("    name: ").append(pullSecretName).append("\n");
            sb.append("    registry: ").append(p.getHarborRegistry()).append("\n");
            sb.append("    username: \"").append(nullSafe(p.getHarborUsername())).append("\"\n");
            sb.append("    password: \"").append(nullSafe(p.getHarborPassword())).append("\"\n");
            sb.append("  imagePullSecrets:\n");
            sb.append("    - name: ").append(pullSecretName).append("\n");
        } else {
            sb.append("  imagePullSecret:\n");
            sb.append("    enabled: false\n");
            sb.append("  imagePullSecrets: []\n");
        }
        sb.append("  mongodb:\n");
        sb.append("    replicaSetName: tapdata-mongodb\n");
        sb.append("    protocol: mongodb\n");
        sb.append("    user: \"").append(nullSafe(p.getMongodbUser())).append("\"\n");
        sb.append("    password: \"").append(nullSafe(p.getMongodbPassword())).append("\"\n");
        sb.append("    authSource: ").append(nullSafe(p.getMongodbAuthSource())).append("\n");
        sb.append("    database: ").append(nullSafe(p.getMongodbDatabase())).append("\n");
        if (p.getMongodbHosts() != null && !p.getMongodbHosts().isBlank()) {
            sb.append("    hosts: \"").append(p.getMongodbHosts()).append("\"\n");
        } else {
            sb.append("    hosts: \"").append(extractHostsFromUri(p.getMongodbUri())).append("\"\n");
        }
        sb.append("\n");

        // MongoDB sub-chart 禁用（用外部的）
        sb.append("mongodb:\n");
        sb.append("  enabled: false\n");
        sb.append("\n");

        // tapdata-server
        sb.append("tapdata-server:\n");
        sb.append("  replicaCount: ").append(p.getServerReplicas()).append("\n");
        sb.append("  image:\n");
        // imageRepo = harbor/project/tapdata:tag —— 切分一下
        String registry = extractRegistry(imageRepo);
        String repoOnly = extractRepo(imageRepo);
        String tag = extractTag(imageRepo);
        sb.append("    repository: ").append(repoOnly).append("\n");
        sb.append("    tag: ").append(tag).append("\n");
        sb.append("    pullPolicy: IfNotPresent\n");
        sb.append("  service:\n");
        sb.append("    type: ClusterIP\n");
        sb.append("    port: 3030\n");
        sb.append("  env:\n");
        sb.append("    module: frontend\n");
        sb.append("    backendUrl: \"").append(backendUrl).append("\"\n");
        sb.append("    javaVersion: java17\n");
        sb.append("    tz: Asia/Shanghai\n");
        if (hasLicenseHost) {
            sb.append("    licenseHost: \"").append(p.getLicenseHost()).append("\"\n");
        }
        if (p.getLicense() != null && !p.getLicense().isBlank()) {
            sb.append("    license: \"").append(p.getLicense()).append("\"\n");
        }
        // 覆盖 deployment.yaml 硬编码的 wait-for-mongodb initContainer（等 MongoDBCommunity CRD）
        sb.append("  initContainers: []\n");
        sb.append("\n");

        // tapdata-engine
        sb.append("tapdata-engine:\n");
        sb.append("  replicaCount: ").append(p.getEngineReplicas()).append("\n");
        sb.append("  image:\n");
        sb.append("    repository: ").append(repoOnly).append("\n");
        sb.append("    tag: ").append(tag).append("\n");
        sb.append("    pullPolicy: IfNotPresent\n");
        sb.append("  env:\n");
        sb.append("    module: backend\n");
        sb.append("    backendUrl: \"").append(backendUrl).append("\"\n");
        sb.append("    javaVersion: java17\n");
        sb.append("    tz: Asia/Shanghai\n");
        if (hasLicenseHost) {
            sb.append("    licenseHost: \"").append(p.getLicenseHost()).append("\"\n");
        }
        sb.append("  initContainers: []\n");
        sb.append("\n");

        // tapdata-apiserver
        sb.append("tapdata-apiserver:\n");
        sb.append("  enabled: true\n");
        sb.append("  replicaCount: ").append(p.getApiserverReplicas()).append("\n");
        sb.append("  image:\n");
        sb.append("    repository: ").append(repoOnly).append("\n");
        sb.append("    tag: ").append(tag).append("\n");
        sb.append("    pullPolicy: IfNotPresent\n");
        sb.append("  service:\n");
        sb.append("    type: ClusterIP\n");
        sb.append("    port: 3080\n");
        sb.append("  env:\n");
        sb.append("    module: apiserver\n");
        sb.append("    backendUrl: \"").append(backendUrl).append("\"\n");
        sb.append("    javaVersion: java17\n");
        sb.append("    tz: Asia/Shanghai\n");
        if (hasLicenseHost) {
            sb.append("    licenseHost: \"").append(p.getLicenseHost()).append("\"\n");
        }
        sb.append("  initContainers: []\n");
        sb.append("\n");

        // 全局 imageRegistry 不设（我们用完整 repository + tag）
        // 注意：Helm chart 模板里的 {{ .Values.global.imageRegistry }} 前缀如果非空会拼接，所以这里必须置空
        sb.append("# Ingress disabled by default\n");
        sb.append("ingress:\n");
        sb.append("  enabled: false\n");

        return sb.toString();
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private static String resolveImageTag(String userTag, String artifactUrl) {
        if (userTag != null && !userTag.isBlank()) return userTag.trim();
        // 从 URL 文件名里推断，如 https://.../tapdata-7.0.0.tar.gz → 7.0.0
        try {
            int slash = artifactUrl.lastIndexOf('/');
            String file = (slash < 0) ? artifactUrl : artifactUrl.substring(slash + 1);
            int dot = file.lastIndexOf('.');
            String noExt = (dot < 0) ? file : file.substring(0, dot);
            // 去掉 tapdata- / tapdata_ 前缀
            if (noExt.startsWith("tapdata-")) return noExt.substring("tapdata-".length());
            if (noExt.startsWith("tapdata_")) return noExt.substring("tapdata_".length());
            // 找版本号
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+[._-]\\d+[._-]\\d+)").matcher(noExt);
            if (m.find()) return m.group(1).replace('_', '.').replace('-', '.');
        } catch (Exception ignore) {}
        return "latest";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 从 mongodb://user:pwd@host1:27017,host2:27017/db?authSource=admin 里提取 hosts 部分。 */
    private static String extractHostsFromUri(String uri) {
        try {
            int at = uri.indexOf('@');
            if (at < 0) {
                // 不带凭据，跳过 mongodb://
                int proto = uri.indexOf("://");
                int slash = uri.indexOf('/', proto + 3);
                if (slash < 0) return uri.substring(proto + 3);
                return uri.substring(proto + 3, slash);
            }
            int slash = uri.indexOf('/', at);
            int qmark = uri.indexOf('?', at);
            int end = Math.min(slash < 0 ? Integer.MAX_VALUE : slash, qmark < 0 ? Integer.MAX_VALUE : qmark);
            return uri.substring(at + 1, end);
        } catch (Exception ignore) { return uri; }
    }

    private static String extractRegistry(String fullImage) {
        int firstSlash = fullImage.indexOf('/');
        // 如果第一段有 '.' 或 ':' 就是 registry，否则返回空
        if (firstSlash < 0) return "";
        String first = fullImage.substring(0, firstSlash);
        if (first.contains(".") || first.contains(":")) return first;
        return "";
    }

    private static String extractRepo(String fullImage) {
        int firstSlash = fullImage.indexOf('/');
        int lastColon = fullImage.lastIndexOf(':');
        String withoutTag;
        if (lastColon > firstSlash) {
            withoutTag = fullImage.substring(firstSlash + 1, lastColon);
        } else {
            withoutTag = fullImage.substring(firstSlash + 1);
        }
        return withoutTag;
    }

    private static String extractTag(String fullImage) {
        int lastColon = fullImage.lastIndexOf(':');
        if (lastColon < 0) return "latest";
        return fullImage.substring(lastColon + 1);
    }
}
