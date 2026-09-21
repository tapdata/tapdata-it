package io.tapdata.it.l3.env;

import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.logging.Logger;

/**
 * 环境管理器（进程级单例，复刻引擎 IT 的 {@code engine()} 复用范式）：
 * <ul>
 *   <li>首个 IT 类触发一次完整部署，后续类复用同一 {@link ProductEnvironment}，避免每类各部署一份（成本不可接受）；</li>
 *   <li>{@code reuseExisting=true} 时跳过部署，直接连给定 {@code l3.tm.base.url}（+ 可选 {@code l3.tm.token}），
 *       支撑独立进程反复测某环境（需求3）；</li>
 *   <li>JVM 退出钩子按 {@code l3.teardown.on.exit} 决定是否销毁命名空间（CI 销毁 / 本地保留）。</li>
 * </ul>
 */
public final class EnvironmentManager {

    private static final Logger LOG = Logger.getLogger(EnvironmentManager.class.getName());

    private static final Object LOCK = new Object();
    private static volatile ProductEnvironment environment;
    private static volatile KubernetesClient client;
    private static volatile boolean hookRegistered;

    private EnvironmentManager() {
    }

    /** 确保环境就绪并返回句柄（幂等；线程安全）。 */
    public static ProductEnvironment ensureReady() {
        return ensureReady(L3EnvConfig.load());
    }

    /** 以显式配置确保环境就绪（测试可注入自定义配置）。 */
    public static ProductEnvironment ensureReady(L3EnvConfig cfg) {
        ProductEnvironment local = environment;
        if (local != null) {
            return local;
        }
        synchronized (LOCK) {
            if (environment != null) {
                return environment;
            }
            if (cfg.reuseExisting()) {
                String baseUrl = cfg.tmBaseUrl();
                if (baseUrl == null || baseUrl.isEmpty()) {
                    throw new IllegalStateException(
                            "reuseExisting=true 需要同时提供 l3.tm.base.url（及可选 l3.tm.token）");
                }
                LOG.info("Reusing existing TM environment at " + baseUrl);
                environment = ProductEnvironment.reused(cfg, baseUrl);
                return environment;
            }

            TapdataDeployment deployment = TapdataDeployment.forMode(cfg.deploymentMode());
            client = K8sClientFactory.create(cfg);
            registerShutdownHook(cfg);
            environment = new DeploymentOrchestrator(cfg, client, deployment).run();
            return environment;
        }
    }

    /** 当前就绪环境；未初始化返回 {@code null}。 */
    public static ProductEnvironment current() {
        return environment;
    }

    public static boolean isReady() {
        return environment != null;
    }

    /** 主动销毁环境（删除命名空间、关闭 client、重置单例）。用于显式收尾或失败清理。 */
    public static void shutdown() {
        synchronized (LOCK) {
            if (environment != null) {
                environment.teardown();
                environment = null;
            }
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException e) {
                    LOG.warning("Close Kubernetes client failed: " + e.getMessage());
                }
                client = null;
            }
        }
    }

    private static void registerShutdownHook(L3EnvConfig cfg) {
        if (hookRegistered || !cfg.teardownOnExit()) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (environment != null) {
                    LOG.info("Shutdown hook: tearing down L3 environment");
                    environment.teardown();
                }
            } catch (RuntimeException e) {
                LOG.warning("Shutdown teardown error: " + e.getMessage());
            }
        }, "l3-env-teardown"));
    }
}
