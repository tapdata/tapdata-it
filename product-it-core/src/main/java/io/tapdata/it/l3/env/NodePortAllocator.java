package io.tapdata.it.l3.env;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * NodePort 分配器（需求2："动态查询可用端口 / 避免端口冲突"，支撑多版本并行）。
 * <p>
 * 双重去重：
 * <ol>
 *   <li><b>实时占用</b>：扫描集群内所有 Service 已声明的 {@code nodePort}；</li>
 *   <li><b>租约占位</b>：以带 {@code runId} 的 lease ConfigMap（{@value #LEASE_NAME}）原子占位，
 *       用 {@link io.fabric8.kubernetes.client.dsl.Resource#replace()} 的乐观并发（resourceVersion 冲突即重试），
 *       避免"读取到写入"之间的并发跑撞同一端口。</li>
 * </ol>
 */
public final class NodePortAllocator {

    /** 端口租约 ConfigMap 名称（存于 {@link L3EnvConfig#leaseNamespace()}，跨 run 全局可见）。 */
    public static final String LEASE_NAME = "l3-nodeport-leases";

    private static final Logger LOG = Logger.getLogger(NodePortAllocator.class.getName());
    private static final int MAX_ATTEMPTS = 8;

    private final KubernetesClient client;

    public NodePortAllocator(KubernetesClient client) {
        this.client = client;
    }

    /**
     * 在 {@code [min,max]} 内为 {@code logicalKey}（如 {@code TM}）分配一个空闲 NodePort 并原子占位。
     *
     * @param cfg      环境配置（端口范围、命名空间、runId）
     * @param logicalKey 用途标识（写入租约值，便于排查）
     * @param reserved  同一次部署中已分配、须一并排除的端口（可为空）
     * @return 分配到的 NodePort
     */
    public int allocate(L3EnvConfig cfg, String logicalKey, Set<Integer> reserved) {
        return allocate(cfg, logicalKey, reserved, 0);
    }

    /**
     * 带固定端口偏好的分配：{@code fixedPort > 0} 时优先使用该端口（本地 SSH 隧道只能预转发已知端口），
     * 仅当它未被集群 Service 占用、且未被其他 run 租约占用时生效；否则回退动态分配。
     *
     * @param fixedPort 期望的固定 NodePort（{@code 0} = 完全动态）
     */
    public int allocate(L3EnvConfig cfg, String logicalKey, Set<Integer> reserved, int fixedPort) {
        int min = cfg.nodePortMin();
        int max = cfg.nodePortMax();
        if (fixedPort > 0) {
            if (fixedPort < min || fixedPort > max) {
                throw new IllegalStateException("Fixed NodePort " + fixedPort + " out of range [" + min + "," + max + "]");
            }
            Set<Integer> taken = liveUsedPorts();
            taken.addAll(leasePorts(cfg));
            boolean ownedByUs = leaseOwnedByThisRun(cfg, fixedPort);
            if (!taken.contains(fixedPort) || ownedByUs) {
                if (claimLease(cfg, logicalKey, fixedPort)) {
                    LOG.info("Using fixed NodePort " + fixedPort + " for " + logicalKey + " (run " + cfg.runId() + ")");
                    return fixedPort;
                }
                LOG.info("Fixed NodePort " + fixedPort + " claimed by another run, fall back to dynamic for " + logicalKey);
            } else {
                LOG.warning("Fixed NodePort " + fixedPort + " busy (service/lease), fall back to dynamic for " + logicalKey);
            }
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Set<Integer> used = liveUsedPorts();
            used.addAll(leasePorts(cfg));
            if (reserved != null) {
                used.addAll(reserved);
            }
            Integer candidate = firstFree(min, max, used);
            if (candidate == null) {
                throw new IllegalStateException(
                        "No free NodePort in [" + min + "," + max + "]; all in use by cluster services/leases");
            }
            if (claimLease(cfg, logicalKey, candidate)) {
                LOG.info("Allocated NodePort " + candidate + " for " + logicalKey + " (run " + cfg.runId() + ")");
                return candidate;
            }
            // 占位冲突（并发抢占），重新扫描后重试
        }
        throw new IllegalStateException("Failed to claim a NodePort lease for " + logicalKey
                + " after " + MAX_ATTEMPTS + " attempts");
    }

    /** 释放本 run 在租约中的所有端口（teardown 时调用）。 */
    public void release(L3EnvConfig cfg) {
        try {
            ConfigMap cm = client.configMaps().inNamespace(cfg.leaseNamespace()).withName(LEASE_NAME).get();
            if (cm == null || cm.getData() == null) {
                return;
            }
            Map<String, String> data = new LinkedHashMap<>(cm.getData());
            data.entrySet().removeIf(e -> e.getValue() != null && e.getValue().startsWith(cfg.runId() + "|"));
            cm.setData(data);
            client.configMaps().inNamespace(cfg.leaseNamespace()).resource(cm).replace();
        } catch (RuntimeException e) {
            LOG.warning("Release NodePort leases failed for run " + cfg.runId() + ": " + e.getMessage());
        }
    }

    // ---- 内部 ----

    private Set<Integer> liveUsedPorts() {
        Set<Integer> used = new HashSet<>();
        for (Service svc : client.services().inAnyNamespace().list().getItems()) {
            if (svc.getSpec() == null || svc.getSpec().getPorts() == null) {
                continue;
            }
            for (ServicePort port : svc.getSpec().getPorts()) {
                if (port.getNodePort() != null) {
                    used.add(port.getNodePort());
                }
            }
        }
        return used;
    }

    /** 已被（任一 run）租约占用的端口集合。 */
    private Set<Integer> leasePorts(L3EnvConfig cfg) {
        Set<Integer> ports = new HashSet<>();
        ConfigMap cm = readLease(cfg);
        if (cm != null && cm.getData() != null) {
            for (String key : cm.getData().keySet()) {
                try {
                    ports.add(Integer.parseInt(key));
                } catch (NumberFormatException ignore) {
                    // 非端口键忽略
                }
            }
        }
        return ports;
    }

    /** 该端口的租约是否归属本 run（重跑/多槽位复用同一固定端口时不算冲突）。 */
    private boolean leaseOwnedByThisRun(L3EnvConfig cfg, int port) {
        ConfigMap cm = readLease(cfg);
        if (cm == null || cm.getData() == null) {
            return false;
        }
        String owner = cm.getData().get(String.valueOf(port));
        return owner != null && owner.startsWith(cfg.runId() + "|");
    }

    private ConfigMap readLease(L3EnvConfig cfg) {
        return client.configMaps().inNamespace(cfg.leaseNamespace()).withName(LEASE_NAME).get();
    }

    private static Integer firstFree(int min, int max, Set<Integer> used) {
        for (int p = min; p <= max; p++) {
            if (!used.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /** 乐观并发占位：端口归属本 run 或空闲时写入租约；冲突返回 false。 */
    private boolean claimLease(L3EnvConfig cfg, String logicalKey, int port) {
        String ns = cfg.leaseNamespace();
        ConfigMap cm = readLease(cfg);
        if (cm == null) {
            ConfigMap created = new ConfigMapBuilder()
                    .withNewMetadata().withName(LEASE_NAME).withNamespace(ns).endMetadata()
                    .addToData(String.valueOf(port), cfg.runId() + "|" + logicalKey)
                    .build();
            try {
                client.configMaps().inNamespace(ns).resource(created).create();
                return true;
            } catch (KubernetesClientException race) {
                return false; // 并发新建冲突，重试
            }
        }
        Map<String, String> data = cm.getData() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cm.getData());
        String owner = data.get(String.valueOf(port));
        boolean freeForUs = owner == null || owner.startsWith(cfg.runId() + "|");
        if (!freeForUs) {
            return false; // 端口被其它 run 占用
        }
        data.put(String.valueOf(port), cfg.runId() + "|" + logicalKey);
        cm.setData(data);
        try {
            client.configMaps().inNamespace(ns).resource(cm).replace(); // resourceVersion 乐观锁
            return true;
        } catch (KubernetesClientException conflict) {
            return false;
        }
    }
}
