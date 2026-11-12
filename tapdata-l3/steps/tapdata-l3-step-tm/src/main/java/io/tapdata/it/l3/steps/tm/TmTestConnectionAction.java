package io.tapdata.it.l3.steps.tm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.StepResult;
import io.tapdata.it.l3.plugin.step.BaseStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 测试 TM 数据源连接状态.
 * <p>
 * 发起 PATCH /api/Connections/{id} {"status":"testing"}，然后轮询 GET /api/Connections/{id}
 * 直到 status 变为 ready / failed / error 或超时.
 * <p>
 * 当同时指定多个 {@code connection_ids} 时，默认多线程**并行**测试；
 * 设置 {@code parallel: false} 可强制单线程串行执行，{@code max_threads} 控制并发度.
 *
 * <pre>
 * # 单个连接
 * - id: test_src_conn
 *   action: tm_test_connection
 *   timeout_seconds: 30
 *   inputs:
 *     connection_id: "#{ #steps['create_src_datasource'].outputs['connection_id'] }"
 *
 * # 多连接 —— 默认并行 (并发度 = min(连接数, CPU核数))
 * - id: test_both
 *   action: tm_test_connection
 *   timeout_seconds: 60
 *   inputs:
 *     connection_ids:
 *       - "#{ #steps['create_src_datasource'].outputs['connection_id'] }"
 *       - "#{ #steps['create_tgt_datasource'].outputs['connection_id'] }"
 *     poll_interval_ms: 1500
 *     wait_timeout_seconds: 30
 *
 * # 强制串行 —— 用于 TM agent 资源受限场景
 * - id: test_sequential
 *   action: tm_test_connection
 *   inputs:
 *     connection_ids: [...]
 *     parallel: false
 *
 * # 限制并发度为 2
 * - id: test_limited
 *   action: tm_test_connection
 *   inputs:
 *     connection_ids: [...]
 *     parallel: true
 *     max_threads: 2
 * </pre>
 *
 * Outputs: {@code connection_id}, {@code status}, {@code results}
 */
public class TmTestConnectionAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(TmTestConnectionAction.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private TmClient client;

    public TmTestConnectionAction() {}

    @Override
    public void before(StepConfig config, ScenarioContext ctx) {
        if (client == null) client = new TmClient();
    }

    @Override
    public String getActionName() {
        return "tm_test_connection";
    }

    @Override
    public int getRecommendedTimeoutSeconds(StepConfig config) {
        Map<String, Object> inputs = config.getInputs();
        if (inputs == null) return 45;
        int waitTimeout = getInt(inputs, "wait_timeout_seconds", 20);
        int count = 1;
        Object idsObj = inputs.get("connection_ids");
        if (idsObj instanceof List<?> list && !list.isEmpty()) count = list.size();
        boolean parallel = getBool(inputs, "parallel", true);
        if (parallel && count > 1) {
            return waitTimeout + 45;
        }
        return count * (waitTimeout + 10) + 20;
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        Map<String, Object> inputs = config.getInputs();

        // 支持单个 connection_id 或 connection_ids 列表
        List<String> ids = new ArrayList<>();
        Object connIdObj = inputs.get("connection_id");
        Object connIdsObj = inputs.get("connection_ids");

        if (connIdsObj instanceof List) {
            for (Object o : (List<?>) connIdsObj) ids.add(String.valueOf(o));
        }
        if (connIdObj != null && !String.valueOf(connIdObj).isBlank()) {
            ids.add(String.valueOf(connIdObj));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Required input missing: connection_id or connection_ids");
        }

        int pollIntervalMs = getInt(inputs, "poll_interval_ms", 2000);
        int waitTimeoutSeconds = getInt(inputs, "wait_timeout_seconds", 20);

        boolean parallel = getBool(inputs, "parallel", true);
        int maxThreads = getInt(inputs, "max_threads",
                Math.min(ids.size(), Math.max(1, Runtime.getRuntime().availableProcessors())));

        Map<String, String> results;
        if (parallel && ids.size() > 1) {
            results = testConnectionsParallel(ids, pollIntervalMs, waitTimeoutSeconds, maxThreads);
        } else {
            results = testConnectionsSequential(ids, pollIntervalMs, waitTimeoutSeconds);
        }

        // 汇总结果 —— lastId/lastStatus 沿用原有语义
        boolean allReady = results.values().stream().allMatch(s -> "ready".equalsIgnoreCase(s));
        String lastId = ids.get(ids.size() - 1);
        String lastStatus = results.get(lastId);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("connection_id", lastId);
        outputs.put("status", allReady ? "ready" : lastStatus);
        outputs.put("results", results);

        // 尽力而为 — 连接测试结果仅做诊断，不阻断流程。真正的验证在 Task re-schemas 时发生
        if (allReady) {
            log.info("[TM] All connections ready: {}", results);
        } else {
            log.warn("[TM] Some connections not ready (will continue, real check at Task re-schemas): {}", results);
        }
        return success(outputs);
    }

    /** 串行测试 —— 逐个连接依次发起 PATCH + 轮询。 */
    private Map<String, String> testConnectionsSequential(List<String> ids, int pollIntervalMs, int waitTimeoutSeconds) throws Exception {
        log.info("[TM] Testing {} connection(s) SEQUENTIALLY...", ids.size());
        Map<String, String> results = new LinkedHashMap<>();
        for (String connId : ids) {
            results.put(connId, testOneConnection(connId, pollIntervalMs, waitTimeoutSeconds));
        }
        return results;
    }

    /** 并行测试 —— 使用固定大小线程池同时发起所有连接的 PATCH + 轮询。 */
    private Map<String, String> testConnectionsParallel(List<String> ids, int pollIntervalMs,
                                                          int waitTimeoutSeconds, int maxThreads) throws Exception {
        log.info("[TM] Testing {} connection(s) PARALLEL (max_threads={})...", ids.size(), maxThreads);
        long t0 = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, maxThreads),
                r -> { Thread t = new Thread(r, "tm-test-conn"); t.setDaemon(true); return t; });
        try {
            List<Future<Map.Entry<String, String>>> futures = new ArrayList<>();
            for (String connId : ids) {
                futures.add(executor.submit(() -> Map.entry(connId,
                        testOneConnection(connId, pollIntervalMs, waitTimeoutSeconds))));
            }

            Map<String, String> results = new LinkedHashMap<>();
            for (Future<Map.Entry<String, String>> f : futures) {
                try {
                    Map.Entry<String, String> entry = f.get(waitTimeoutSeconds + 30L, TimeUnit.SECONDS);
                    results.put(entry.getKey(), entry.getValue());
                } catch (TimeoutException te) {
                    // 兜底超时 — 单连接 waitTimeoutSeconds + 30s 已经够长
                    throw new RuntimeException("Parallel test connection exceeded safety timeout", te);
                }
            }

            log.info("[TM] Parallel test done in {}ms — results: {}", System.currentTimeMillis() - t0, results);
            return results;
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    /** 测试单个连接: PATCH status=testing + submit=true → 轮询 → 返回最终状态. */
    private String testOneConnection(String connId, int pollIntervalMs, int waitTimeoutSeconds) throws Exception {
        log.info("[TM] Testing connection: {}", connId);

        // 1. 发起测试 PATCH — submit=true 是关键，否则 TM 不会真正触发异步测试任务
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "testing");
        body.put("submit", true);
        JsonNode patchResp = client.patch("/api/Connections/" + connId, body);
        log.info("[TM] PATCH response status={}, submit={}", text(patchResp, "status"), text(patchResp, "submit"));

        // 2. 轮询等待结果
        long deadline = System.currentTimeMillis() + waitTimeoutSeconds * 1000L;
        String lastStatus = "testing";
        int pollCount = 0;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollIntervalMs);
            pollCount++;
            try {
                JsonNode conn = client.get("/api/Connections/" + connId);
                lastStatus = text(conn, "status");
                String submit = text(conn, "submit");
                log.info("[TM] Connection {} poll#{}: status={}, submit={}, isInit={}", connId, pollCount, lastStatus, submit, conn.has("isInit") ? conn.get("isInit").asText() : "n/a");

                if ("ready".equalsIgnoreCase(lastStatus)) return "ready";
                // invalid/failed/error 都是终态 — 测试完成但连接不可用
                if ("invalid".equalsIgnoreCase(lastStatus) || "failed".equalsIgnoreCase(lastStatus) || "error".equalsIgnoreCase(lastStatus)) return lastStatus;
                // 其他值继续等
            } catch (Exception e) {
                log.warn("[TM] Connection {} poll error: {}", connId, e.getMessage());
            }
        }

        log.warn("[TM] Connection {} test timed out after {}s, last status={}", connId, waitTimeoutSeconds, lastStatus);
        return "timeout";
    }

    private static int getInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try { return Integer.parseInt(v.toString()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean getBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v != null) {
            String s = v.toString().trim().toLowerCase();
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
            if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        }
        return def;
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
    }
}
