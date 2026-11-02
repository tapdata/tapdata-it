package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * tm_get_task Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   task_id: "#{ #steps['create_task'].outputs['task_id'] }"
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TmGetTaskParameter extends ParameterConfig {

    /** 要查询的 Task ID — 必填。 */
    private String taskId;
}
