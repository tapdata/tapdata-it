package io.tapdata.it.l3.api;

import com.tapdata.tm.commons.dag.DAG;
import com.tapdata.tm.commons.dag.Edge;
import com.tapdata.tm.commons.dag.nodes.DatabaseNode;
import com.tapdata.tm.commons.task.dto.ParentTaskDto;
import com.tapdata.tm.commons.task.dto.TaskDto;
import io.tapdata.it.l3.api.model.TmApiException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务请求体装配：把「两个已建好的连接 + 一组表」转成 TM 可直接 {@code save/confirm/start} 的
 * {@link TaskDto}（复用管理端 DTO，需求 4）。
 * <p>
 * 装配口径与 Web 端建任务一致（等价于前端提交的 JSON）：
 * <ul>
 *   <li>任务级：{@code syncType=migrate}、{@code type=initial_sync|initial_sync+cdc}、
 *       {@code status=wait_start}、{@code accessNodeType=AUTOMATIC_PLATFORM_ALLOCATION}；</li>
 *   <li>节点级：{@link DatabaseNode}（{@code type=database}）+ {@code connectionId}/{@code databaseType}/
 *       {@code tableNames}/{@code migrateTableSelectType}；</li>
 *   <li>节点 {@code attrs}：{@code pdkType}/{@code pdkHash}/{@code connectionType}/{@code connectionName}/
 *       {@code capabilities}/{@code position}——这些值 TM 只存在连接文档上，故经 {@link Endpoint} 一次读回，
 *       避免用例硬编码 pdkHash。</li>
 * </ul>
 * 用例侧典型写法：
 * <pre>
 * TaskDtoFactory.Endpoint src = TaskDtoFactory.Endpoint.of(tm().dataSourceApi(), sourceConnId);
 * TaskDtoFactory.Endpoint dst = TaskDtoFactory.Endpoint.of(tm().dataSourceApi(), targetConnId);
 * String taskId = tm().taskApi().save(TaskDtoFactory.fullCopy("l3-f-01", src, dst, tables));
 * </pre>
 */
public final class TaskDtoFactory {

    /** TM 自动分配执行节点（无 agent 手工绑定时用例不必关心调度）。 */
    public static final String ACCESS_NODE_AUTO = "AUTOMATIC_PLATFORM_ALLOCATION";
    /** 指定表同步（{@code tableNames} 非空时使用）；{@code all} 表示整库。 */
    public static final String SELECT_CUSTOM = "custom";
    public static final String SELECT_ALL = "all";

    private TaskDtoFactory() {
    }

    /**
     * 端点元信息：建任务所需的连接属性快照（一次 {@code GET /v2/ds/{id}?noSchema=true} 读回）。
     */
    public static final class Endpoint {

        private final String connectionId;
        private final String connectionName;
        private final String databaseType;
        private final String connectionType;
        private final String pdkHash;
        private final List<Object> capabilities;

        @SuppressWarnings("unchecked")
        private Endpoint(String connectionId, Map<String, Object> connection) {
            this.connectionId = connectionId;
            this.connectionName = str(connection, "name");
            this.databaseType = firstNonBlank(str(connection, "database_type"), str(connection, "databaseType"));
            this.connectionType = firstNonBlank(str(connection, "connection_type"), str(connection, "connectionType"));
            this.pdkHash = firstNonBlank(str(connection, "pdkHash"), str(connection, "pdk_hash"));
            Object caps = connection.get("capabilities");
            this.capabilities = caps instanceof List ? Collections.unmodifiableList(new ArrayList<>((List<Object>) caps))
                    : Collections.emptyList();
        }

        /** 从 TM 读回连接元信息（要求连接已由 {@link DataSourceAPI#create} 创建并带 {@code pdkHash}）。 */
        public static Endpoint of(DataSourceAPI api, String connectionId) {
            Map<String, Object> connection = api.findByIdNoSchema(connectionId);
            if (connection == null || connection.isEmpty()) {
                throw new TmApiException("Connection " + connectionId + " not found in TM");
            }
            Endpoint endpoint = new Endpoint(connectionId, connection);
            if (endpoint.pdkHash == null) {
                throw new TmApiException("Connection " + connectionId
                        + " has no pdkHash; create it with DataSourcePayload.pdkHash(...)");
            }
            return endpoint;
        }

        public String connectionId() {
            return connectionId;
        }

        public String connectionName() {
            return connectionName;
        }

        public String databaseType() {
            return databaseType;
        }

        public String connectionType() {
            return connectionType;
        }

        public String pdkHash() {
            return pdkHash;
        }
    }

    /**
     * 全量复制任务（{@code migrate} + {@code initial_sync}）：跑完自然进入 {@code complete}，不驻留增量。
     *
     * @param name    任务名
     * @param source  源端连接快照
     * @param target  目标端连接快照
     * @param tables  待同步表；空则整库（{@code migrateTableSelectType=all}）
     */
    public static TaskDto fullCopy(String name, Endpoint source, Endpoint target, List<String> tables) {
        return migrate(name, source, target, tables, ParentTaskDto.TYPE_INITIAL_SYNC);
    }

    /** 全量 + 增量复制任务（{@code migrate} + {@code initial_sync+cdc}）：全量完成后驻留 {@code running}。 */
    public static TaskDto replicate(String name, Endpoint source, Endpoint target, List<String> tables) {
        return migrate(name, source, target, tables, ParentTaskDto.TYPE_INITIAL_SYNC_CDC);
    }

    private static TaskDto migrate(String name, Endpoint source, Endpoint target, List<String> tables, String syncPointsType) {
        TaskDto task = new TaskDto();
        task.setName(name);
        task.setSyncType(TaskDto.SYNC_TYPE_MIGRATE);
        task.setType(syncPointsType);
        // TM create 会强制置为 edit；confirm 时再按状态机推进到 wait_start，
        // 这里不预置 wait_start，否则状态机把 confirm 当成 WAIT_START 自环。
        task.setEditVersion(String.valueOf(System.currentTimeMillis()));
        task.setAccessNodeType(ACCESS_NODE_AUTO);
        task.setIsAutoCreateIndex(Boolean.TRUE);
        task.setIsStopOnError(Boolean.TRUE);
        task.setSourceId(source.connectionId());
        task.setSourceName(source.connectionName());
        task.setTargetId(target.connectionId());
        task.setTargetName(target.connectionName());

        // 引擎侧若干 Boolean 字段会被直接 unboxing（HazelcastPdkBaseNode.generateNodeConfig /
        // HazelcastSourcePdkBaseNode.initStreamOffsetInitialAndCDC），必须显式给非 null 值
        task.setFileLog(Boolean.FALSE);
        task.setDoubleActive(Boolean.FALSE);
        task.setDataSaving(Boolean.FALSE);
        task.setOldVersionTimezone(Boolean.FALSE);
        task.setShareCdcEnable(Boolean.FALSE);

        DatabaseNode sourceNode = sourceNode(source, tables);
        DatabaseNode targetNode = targetNode(target);
        com.tapdata.tm.commons.task.dto.Dag dag = new com.tapdata.tm.commons.task.dto.Dag();
        dag.setNodes(Arrays.asList(sourceNode, targetNode));
        dag.setEdges(Collections.singletonList(new Edge(sourceNode.getId(), targetNode.getId())));
        task.setDag(DAG.build(dag));
        return task;
    }

    /** 源节点：库级迁移（{@code type=database}），指定表时 {@code migrateTableSelectType=custom}。 */
    private static DatabaseNode sourceNode(Endpoint endpoint, List<String> tables) {
        DatabaseNode node = baseNode(endpoint);
        boolean custom = tables != null && !tables.isEmpty();
        node.setMigrateTableSelectType(custom ? SELECT_CUSTOM : SELECT_ALL);
        if (custom) {
            node.setTableNames(new ArrayList<>(tables));
        }
        node.setNoPrimaryKeyTableSelectType("All");
        return node;
    }

    /** 目标节点：仅指定连接与写入策略，表结构由 TM 在 confirm（推演）阶段自动创建。 */
    private static DatabaseNode targetNode(Endpoint endpoint) {
        DatabaseNode node = baseNode(endpoint);
        node.setMigrateTableSelectType(SELECT_ALL);
        return node;
    }

    private static DatabaseNode baseNode(Endpoint endpoint) {
        DatabaseNode node = new DatabaseNode();
        node.setId(UUID.randomUUID().toString());
        node.setName(endpoint.connectionName());
        node.setConnectionId(endpoint.connectionId());
        node.setDatabaseType(endpoint.databaseType());
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("connectionName", endpoint.connectionName());
        attrs.put("connectionType", endpoint.connectionType());
        attrs.put("pdkType", "pdk");
        attrs.put("pdkHash", endpoint.pdkHash());
        attrs.put("capabilities", endpoint.capabilities);
        attrs.put("accessNodeProcessId", "");
        attrs.put("position", Arrays.asList(0, 0));
        node.setAttrs(attrs);
        return node;
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }
}
