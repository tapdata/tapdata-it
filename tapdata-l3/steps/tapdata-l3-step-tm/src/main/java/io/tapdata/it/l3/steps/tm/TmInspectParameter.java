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

    /** 关联的 Task ID. */
    private String taskId;

    // ===== Create Inspect 专属 =====

    /** 校验任务名称. */
    private String name;

    /** 代理类型 — 默认 "Collect". */
    private String agentType = "Collect";

    /** 校验模式: "full" (全量校验) / "incremental" (增量校验) / "all". 默认 all. */
    private String inspectMode = "all";

    /** 源连接 ID. */
    private String sourceConnectionId;

    /** 目标连接 ID. */
    private String targetConnectionId;

    /** 要校验的表列表 — 如果为 null 校验任务中所有表. */
    private List<String> tables;

    /** 原始 InspectDto JSON — 完全自定义. 优先级最高. */
    private Map<String, Object> rawInspectConfig;

    // ===== Assert Inspect 专属 =====

    /** 是否允许数据不一致 (默认 false — 必须一致). */
    private boolean allowMismatch = false;

    /** 最大允许不一致行数. */
    private Long maxMismatchRows = 0L;
}
