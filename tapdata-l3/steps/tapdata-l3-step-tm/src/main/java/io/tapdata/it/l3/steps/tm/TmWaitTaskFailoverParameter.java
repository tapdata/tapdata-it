package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * tm_wait_task_failover Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   task_id: "#{ #steps['create_task'].outputs['task_id'] }"
 *   previous_agent_id: "#{ #steps['locate_engine'].outputs['agent_id'] }"
 *   wait_timeout_seconds: 300   # 必须大于任务心跳超时 (jobHeartTimeout × 2)
 *   poll_interval_ms: 5000
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TmWaitTaskFailoverParameter extends ParameterConfig {

    /** 要观察的 Task ID — 必填。 */
    private String taskId;

    /** 故障前运行任务的 engine agentId (process_id) — 必填。 */
    private String previousAgentId;

    /** 最大等待时长 (秒) — 默认 300，必须大于任务心跳超时时间。 */
    private int waitTimeoutSeconds = 300;

    /** 轮询间隔 (毫秒) — 默认 5000。 */
    private int pollIntervalMs = 5000;
}
