package io.tapdata.it.l3.env;

/**
 * TM 访问地址推导工具：统一「集群节点地址 + NodePort」与「本地 SSH 隧道覆盖」两种口径，
 * 供 {@link DeploymentOrchestrator}（健康探活）与 {@link ProductEnvironment}（API 基址）共用，
 * 避免两处各自拼接导致探测地址与实际访问地址不一致。
 * <p>
 * 约定：{@code root} = TM 服务根地址（不含 {@code /api}，如 {@code http://10.0.0.5:30300}）；
 * {@code apiBase} = {@code root + "/api"}，即 {@link io.tapdata.it.l3.api.TmApiClient} 的基址。
 */
final class TmEndpoints {

    private TmEndpoints() {
    }

    /**
     * TM 服务根地址：配置了 {@code l3.tm.base.url} 时以其为准（本地隧道场景，
     * NodePort 已 forward 到 {@code 127.0.0.1:<端口>}），否则由节点地址与分配的 NodePort 推导。
     */
    static String root(L3EnvConfig cfg, String nodeAddress, int nodePort) {
        String override = cfg.tmBaseUrl();
        if (override != null && !override.isEmpty()) {
            return stripApi(trailingSlash(override));
        }
        return "http://" + nodeAddress + ":" + nodePort;
    }

    /** 由根地址补齐 {@code /api} 前缀，得到 TM API 基址。 */
    static String apiBase(String root) {
        String r = trailingSlash(root);
        return r.endsWith("/api") ? r : r + "/api";
    }

    private static String stripApi(String url) {
        return url.endsWith("/api") ? url.substring(0, url.length() - "/api".length()) : url;
    }

    private static String trailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
