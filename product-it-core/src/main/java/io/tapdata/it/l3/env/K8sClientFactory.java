package io.tapdata.it.l3.env;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 由 {@link L3EnvConfig} 构建 fabric8 {@link KubernetesClient}。
 * <p>
 * 认证优先级：内联 {@code l3.kubeconfig.content} &gt; 文件 {@code l3.kubeconfig.path}
 * （默认 {@code /etc/rancher/k3s/k3s.yaml}）&gt; 集群内/默认自动发现；多上下文 kubeconfig 可用
 * {@code l3.kubeconfig.context} 选定上下文（如本地 {@code k3sdev}）。
 * 可选用 {@code l3.apiserver.url} 覆盖服务端地址（本地经 SSH 隧道连远端 k3s 时设为 {@code https://127.0.0.1:6443}），
 * 配合 {@code l3.trust.certs=true}（默认）跳过与隧道地址不匹配的证书校验；
 * {@code l3.auth.token} 提供 ServiceAccount token 认证。
 */
public final class K8sClientFactory {

    private K8sClientFactory() {
    }

    public static KubernetesClient create(L3EnvConfig cfg) {
        Config config = buildConfig(cfg);
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    private static Config buildConfig(L3EnvConfig cfg) {
        String context = cfg.kubeconfigContext();
        Config config;
        String inline = cfg.kubeconfigContent();
        if (inline != null && !inline.isEmpty()) {
            config = Config.fromKubeconfig(inline, null, context);
        } else {
            Path path = Paths.get(cfg.kubeconfigPath());
            if (Files.exists(path)) {
                try {
                    config = Config.fromKubeconfig(Files.readString(path), null, context);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Read kubeconfig failed: " + path + " (" + e.getMessage() + ")", e);
                }
            } else {
                // 无本地 kubeconfig：尝试集群内 ServiceAccount 或默认上下文（上下文经 KUBECTL_CONTEXT 等环境变量控制）
                config = Config.autoConfigure(context);
            }
        }

        String apiServerUrl = cfg.apiServerUrl();
        if (apiServerUrl != null && !apiServerUrl.isEmpty()) {
            config.setMasterUrl(apiServerUrl);
            // 隧道/自签场景下原 kubeconfig CA 与实际地址不匹配，清空并由 trustCerts 跳过校验
            config.setCaCertData(null);
            config.setCaCertFile(null);
        }
        if (cfg.trustCerts()) {
            config.setTrustCerts(true);
        }
        String token = cfg.authToken();
        if (token != null && !token.isEmpty()) {
            config.setOauthToken(token);
        }
        return config;
    }
}
