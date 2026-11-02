package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * Parameters for TM Task management steps (create/start/stop/wait/delete).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TmTaskParameter extends ParameterConfig {

    // ===== 通用 (多个 action 共用) =====

    /** TM task ID — used by start/stop/wait/delete actions. */
    private String taskId;

    // ===== Create Task 专属 =====

    /** Task name (unique). */
    private String name;

    /** sync_type: "sync" (全量+增量), "cdc" (增量only), "full" (全量only). 默认 sync. */
    private String syncType = "sync";

    /** Source connection ID (from tm_create_datasource output). */
    private String sourceConnectionId;

    /** Target connection ID (from tm_create_datasource output). */
    private String targetConnectionId;

    /** Source table names to sync — 如果为 null 表示全表同步. */
    private List<String> sourceTables;

    /** Target table prefix/suffix strategy — 可选. */
    private String targetTablePrefix;

    /** DAG nodes — 原始节点配置，用户可以直接传完整 DAG. 如果传了这个，就跳过上面的简单字段. */
    private Map<String, Object> dagNodes;

    /** 原始 TaskDto JSON — 完全自定义任务定义. 优先级最高. */
    private Map<String, Object> rawTaskConfig;

    // ===== Wait Task 专属 =====

    /** 等待的目标状态: "running" / "stopped" / "completed" / "error". 默认 "completed". */
    private String targetStatus = "completed";

    /** 轮询间隔 (毫秒). 默认 3000. */
    private int pollIntervalMs = 3000;

    /** 额外等待的最大时长 (秒). 默认 300. */
    private int waitTimeoutSeconds = 300;

    /** 是否在 source 表数 vs 实际同步行数 对上时就算完成 — 仅用于 wait full-sync. */
    private boolean waitForFullSyncRows = false;

    /** 期望的全量同步总行数 — 配合 waitForFullSyncRows 使用. */
    private Long expectedFullSyncRows;
}
