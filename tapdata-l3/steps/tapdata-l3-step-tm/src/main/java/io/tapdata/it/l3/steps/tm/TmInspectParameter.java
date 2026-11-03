package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * Parameters for TM Inspect (数据校验) steps.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TmInspectParameter extends ParameterConfig {

    // ===== 通用 =====

    /** Inspect ID — used by execute/assert actions. */
    private String inspectId;

    /** 关联的 Flow/Task ID (TM 内部叫 flowId, 我们 YAML 里用 task_id 就是 flowId). */
    private String taskId;

    // ===== Create Inspect 专属 =====

    /** 校验任务名称. */
    private String name;

    /** 代理类型 — TM 真实值是 "private" (不是 "Collect"!). */
    private String agentType = "private";

    /** 指定 agentId (agent processId). 不传则 TM 按 agentTags 自动分配. */
    private String agentId;

    /** 校验模式: "full" / "incremental" / "all". 默认 all. */
    private String inspectMode = "all";

    /** Inspect 运行模式: "manual" / "timing". 默认 "manual". */
    private String mode = "manual";

    /** 校验方法: "row_count" / "value". 默认 "row_count". */
    private String inspectMethod = "row_count";

    /** 差异处理模式: "All" / "firstMismatch". 默认 "All". */
    private String inspectDifferenceMode = "All";

    /** 源连接 ID. */
    private String sourceConnectionId;

    /** 目标连接 ID. */
    private String targetConnectionId;

    /** 要校验的表列表 — 如果为 null 校验任务中所有表. */
    private List<String> tables;

    /** 原始 InspectDto JSON — 完全自定义. 优先级最高. */
    private Map<String, Object> rawInspectConfig;

    /** Execute 轮询等待超时 (秒). 默认 300s. */
    private int waitTimeoutSeconds = 300;

    // ===== Assert Inspect 专属 =====

    /** 是否允许数据不一致 (默认 false — 必须一致). */
    private boolean allowMismatch = false;

    /** 最大允许不一致行数. */
    private Long maxMismatchRows = 0L;
}
