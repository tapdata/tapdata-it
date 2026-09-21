package io.tapdata.it.l3.base;

import io.tapdata.entity.utils.DataMap;
import io.tapdata.it.config.ConnectionConfigLoader;
import io.tapdata.it.l3.api.DataSourceAPI;
import io.tapdata.it.l3.api.DataSourceDefinitionAPI;
import io.tapdata.it.l3.api.DataSourcePayload;
import io.tapdata.it.l3.api.TaskAPI;
import io.tapdata.it.l3.api.TaskDtoFactory;
import io.tapdata.it.l3.api.TmApiClient;
import io.tapdata.it.l3.env.EnvironmentManager;
import io.tapdata.it.l3.env.L3EnvConfig;
import io.tapdata.it.l3.env.ProductEnvironment;
import io.tapdata.it.verifier.ConnectorVerifier;
import com.tapdata.tm.commons.task.dto.TaskDto;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * L3 用例基类：统一管理"环境就绪 + TM 认证"生命周期，并桥接 {@code tapdata-it-common} 的旁路验证器/配置加载。
 * <p>
 * 环境由 {@link EnvironmentManager} 进程级单例保障：<b>整轮 IT 只部署一次</b>，多个用例类复用同一产品；
 * 复用既有环境（{@code l3.reuse.existing=true}）时跳过部署直连给定 TM。
 * <p>
 * 子类用法：
 * <ul>
 *   <li>{@link #tm()}：已登录的 {@link TmApiClient}，{@code tm.taskApi()/dataSourceApi()/pdkApi()...}；</li>
 *   <li>{@link #env()}：{@link ProductEnvironment}（tmBaseUrl、namespace、config 等）；</li>
 *   <li>{@link #loadConnectionJson(String)} + {@link #directVerifier(DataMap)}：读取连接 JSON 并建旁路验证器。</li>
 * </ul>
 */
public abstract class ProductIT {

    /** 子类可用的日志器（清理阶段等非断言路径的异常只记录不冒泡）。 */
    protected static final Logger LOG = Logger.getLogger(ProductIT.class.getName());

    private static ProductEnvironment environment;
    private static TmApiClient tmApiClient;

    @BeforeAll
    static void __ensureEnvironment() {
        if (environment == null) {
            environment = EnvironmentManager.ensureReady();
            tmApiClient = environment.openApiClient();
        }
    }

    /** 已认证的 TM API 客户端。 */
    protected static TmApiClient tm() {
        ensureInit();
        return tmApiClient;
    }

    /** 当前产品环境句柄。 */
    protected static ProductEnvironment env() {
        ensureInit();
        return environment;
    }

    /** 便捷：环境配置。 */
    protected static L3EnvConfig config() {
        return env().config();
    }

    private static void ensureInit() {
        if (environment == null) {
            __ensureEnvironment();
        }
    }

    // ---- 环境预检 / 异步等待便捷入口（用例侧不必关心超时与轮询参数）----

    /** 轮询间隔（{@code l3.poll.interval.ms}）。 */
    protected static long pollIntervalMs() {
        return config().pollIntervalMs();
    }

    /** 部署/就绪超时秒数（{@code l3.ready.timeout.sec}）。 */
    protected static long readyTimeoutSec() {
        return config().readyTimeoutSec();
    }

    /** 任务收敛超时秒数（{@code l3.task.timeout.sec}）。 */
    protected static long taskTimeoutSec() {
        return config().taskTimeoutSec();
    }

    /**
     * 预检：所需 connector 已在 TM 注册（对齐 A5——jar 由 TM 首启默认上传，用例只核验不上传）。
     * 未注册时给出明确失败原因，而不是让任务以难以定位的 runError 收场。
     */
    protected static void requireConnectorRegistered(String... databaseTypes) {
        DataSourceDefinitionAPI definitions = tm().dataSourceDefinitionApi();
        for (String type : databaseTypes) {
            if (!definitions.isRegistered(type)) {
                throw new AssertionError("Connector [" + type + "] not registered in TM; "
                        + "expected TM to upload connector jars on first startup (assumption A5)");
            }
        }
    }

    /** 解析库类型的 pdkHash（建连接与任务节点 {@code attrs.pdkHash} 必需）。 */
    protected static String pdkHash(String databaseType) {
        return tm().dataSourceDefinitionApi().pdkHash(databaseType);
    }

    /** 等待连接测连 + 模型加载完成（{@code loadFieldsStatus=finished}）。 */
    protected static void awaitConnectionLoaded(String connectionId) {
        TmAwaits.connectionLoaded(tm().dataSourceApi(), connectionId, readyTimeoutSec(), pollIntervalMs());
    }

    /** 等待指定表在连接的元数据中可见（表发现结果）。 */
    protected static void awaitTablesDiscovered(String connectionId, List<String> tableNames) {
        TmAwaits.tableDiscovered(tm().metadataInstancesApi(), connectionId, tableNames,
                readyTimeoutSec(), pollIntervalMs());
    }

    /** 等待任务全量同步跑完（{@code status=complete}），失败态即刻抛错。 */
    protected static void awaitTaskCompleted(String taskId) {
        TmAwaits.taskCompleted(tm().taskApi(), taskId, taskTimeoutSec(), pollIntervalMs());
    }

    /** 等待任务进入 {@code running}（增/改类用例需先跑起来再旁路注入 DML）。 */
    protected static void awaitTaskRunning(String taskId) {
        TmAwaits.taskRunning(tm().taskApi(), taskId, taskTimeoutSec(), pollIntervalMs());
    }

    // ---- 产品流程一步式入口（把「建连接→建模→表发现」「存→确认→启动」的固定串接内聚在框架）----

    /**
     * 创建连接并等待就绪：自动补齐 {@code pdkHash}，并把 {@code database_type} 归一化为 TM 连接器定义
     * 的 {@code type} 字段（例如 {@code mysql → Mysql}），对齐 A5；提交后等测连通过且模型加载完成。
     *
     * @param payload 连接请求体（{@link DataSourcePayload#fromNode} 由用例配置构造）
     * @return 连接 id
     */
    protected static String createConnection(DataSourcePayload payload) {
        String databaseType = payload.databaseType();
        DataSourceDefinitionAPI definitions = tm().dataSourceDefinitionApi();
        Map<String, Object> definition = definitions.find(databaseType);
        if (definition == null) {
            throw new IllegalStateException("Connector [" + databaseType + "] not registered in TM; "
                    + "expected TM to upload connector jars on first startup (assumption A5)");
        }
        String canonicalType = String.valueOf(definition.get("type"));
        String pdkHash = String.valueOf(definition.get("pdkHash"));
        payload.type(canonicalType);

        String id = tm().dataSourceApi().create(payload.pdkHash(pdkHash));
        awaitConnectionLoaded(id);
        return id;
    }

    /**
     * 装配并启动一个库表复制任务（{@code save → confirm → batchStart}），不等待收敛。
     *
     * @param taskName 任务名
     * @param source   源端连接快照（{@link TaskDtoFactory.Endpoint#of}）
     * @param target   目标端连接快照
     * @param tables   待同步表；空则整库
     * @param withCdc  {@code true} 为全量+增量（{@code initial_sync+cdc}），{@code false} 为纯全量
     * @return 任务 id
     */
    protected static String startCopyTask(String taskName, TaskDtoFactory.Endpoint source, TaskDtoFactory.Endpoint target,
                                          List<String> tables, boolean withCdc) {
        TaskDto task = withCdc
                ? TaskDtoFactory.replicate(taskName, source, target, tables)
                : TaskDtoFactory.fullCopy(taskName, source, target, tables);
        TaskAPI tasks = tm().taskApi();
        String id = tasks.save(task);
        System.out.println("[L3DEBUG] Task saved id=" + id + " status=" + tasks.status(id));
        // confirm 必须基于服务端最新状态推进；初始 TaskDto 未携带 save 后的 id/status，
        // 直接回传会导致状态机因读不到当前状态而报 Transition.Not.Found。
        Map<String, Object> saved = tasks.findById(id);
        System.out.println("[L3DEBUG] Task loaded before confirm: " + saved);
        // confirm 推进到 wait_start（但不立即启动），再由 batchStart 真正启动
        String confirmResp = tasks.confirm(id, saved);
        System.out.println("[L3DEBUG] Task confirm response: " + confirmResp);
        System.out.println("[L3DEBUG] Task status after confirm: " + tasks.status(id));
        String startResp = tasks.start(id);
        System.out.println("[L3DEBUG] Task start response: " + startResp);
        System.out.println("[L3DEBUG] Task status after start: " + tasks.status(id));
        return id;
    }

    // ---- 连接配置 / 旁路验证器桥接 ----

    /**
     * 从 classpath/文件加载连接描述 JSON（复用 {@link ConnectionConfigLoader} 的加载与覆盖语义）。
     * 典型结构：{@code {"source": {...}, "target": {...}}}，用 {@link #sub(DataMap, String)} 取子节点。
     */
    protected static DataMap loadConnectionJson(String classpathResource) {
        try {
            return ConnectionConfigLoader.load(classpathResource);
        } catch (Exception e) {
            throw new IllegalStateException("Load connection json failed: " + classpathResource
                    + " (" + e.getMessage() + ")", e);
        }
    }

    /** 取嵌套子配置为 {@link DataMap}（如 {@code sub(cfg, "source")}）。 */
    @SuppressWarnings("unchecked")
    protected static DataMap sub(DataMap parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Connection config missing node: " + key);
        }
        if (value instanceof DataMap) {
            return (DataMap) value;
        }
        DataMap child = DataMap.create();
        child.putAll((Map<String, Object>) value);
        return child;
    }

    /** 以连接配置直建旁路验证器。 */
    protected static ConnectorVerifier directVerifier(DataMap connection) {
        return DirectVerifierFactory.create(connection);
    }

    // ---- 清理（尽力而为：清理失败只记录，不得掩盖用例本身的结论）----

    /** 删除任务（先停后删；用例失败时任务可能仍在 running，停失败不阻断删除）。 */
    protected static void removeTaskQuietly(String taskId) {
        if (taskId == null) {
            return;
        }
        quietly("stop task " + taskId, () -> tm().taskApi().stop(taskId));
        quietly("delete task " + taskId, () -> tm().taskApi().delete(taskId));
    }

    /** 删除连接（不存在/被引用时仅告警）。 */
    protected static void removeConnectionQuietly(String connectionId) {
        if (connectionId == null) {
            return;
        }
        quietly("connection " + connectionId, () -> tm().dataSourceApi().delete(connectionId));
    }

    /** 关闭旁路验证器。 */
    protected static void closeQuietly(ConnectorVerifier verifier) {
        if (verifier == null) {
            return;
        }
        quietly("verifier", verifier::close);
    }

    private static void quietly(String what, ThrowingAction action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Cleanup {0} failed (ignored): {1}", new Object[]{what, e.getMessage()});
        }
    }

    /** 可抛checked 异常的清理动作。 */
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
