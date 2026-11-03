# tapdata-l3-step-tm

TapData **TM (T/Monitor)** 平台集成 Step 模块 — 覆盖数据源管理、任务生命周期、数据校验（Inspect）三大场景共 **14 个 Action**。

所有 Action 继承 `io.tapdata.it.l3.plugin.step.BaseStep`，遵循统一的 `before → doExecute → after → destroy` 生命周期，支持 DSL YAML 中的 `timeout_seconds`、`keep_alive`、`hooks`、`extra_deps` 等标准配置。

## Actions

### 数据源管理（Connection）

#### `tm_login`
登录 TM 平台并缓存 access token。
- **Inputs**: 无（通过 TmClient 配置自动读取 `tm_base_url` + `access_code`）
- **Outputs**: `access_token`, `tm_base_url`

#### `tm_create_datasource`
在 TM 中创建一个数据源 Connection。未指定 `pdk_hash` 时自动调 `/api/DatabaseTypes` 解析。
- **必填 inputs**: `name`, `database_type`, `database_host`, `database_port`, `database_username`
- **可选 inputs**: `database_password`, `database_name`, `connection_type`（默认 `source_and_target`）, `pdk_hash`, `config`, `agent_id`
- **Outputs**: `connection_id`, `name`, `pdk_hash`, `database_type`

#### `tm_delete_datasource`
删除指定 Connection。
- **必填 inputs**: `connection_id`
- **Outputs**: `connection_id`, `deleted`

#### `tm_list_datasources`
列出 TM 中已有 Connection，可按数据库类型过滤。
- **可选 inputs**: `database_type`（过滤）, `limit`（默认 50）
- **Outputs**: `total`, `connections`（每项含 `id`/`name`/`database_type`/`connection_type`/`status`）

#### `tm_test_connection`
测试一个或多个 Connection 的连接状态。
- **默认并行**：同时传入多个 `connection_ids` 时，默认多线程并行测试（并发度 = min(连接数, CPU核数)）
- **可选 inputs**: `connection_id`（单个）或 `connection_ids`（列表）, `parallel`（默认 `true`）, `max_threads`, `poll_interval_ms`（默认 2000）, `wait_timeout_seconds`（默认 20）
- **Outputs**: `connection_id`（最后一个）, `status`（全部 ready 时为 ready，否则为最后一个状态）, `results`（Map<connectionId, status>）

**并行 vs 串行示例**：
```yaml
# 默认并行 —— 两个连接同时测
- id: test_both
  action: tm_test_connection
  inputs:
    connection_ids:
      - "#{ #steps['create_src'].outputs['connection_id'] }"
      - "#{ #steps['create_tgt'].outputs['connection_id'] }"

# 强制串行 —— TM agent 资源受限时
- id: test_seq
  action: tm_test_connection
  inputs:
    connection_ids: [...]
    parallel: false

# 限制并发度为 2
- id: test_limited
  action: tm_test_connection
  inputs:
    connection_ids: [...]
    max_threads: 2
```

---

### 任务管理（Task）

#### `tm_create_task`
创建数据同步任务（Task/Flow），支持三种模式：
1. `raw_task_config` — 完整自定义 JSON（最高优先级）
2. `dag_nodes` — 自定义 DAG 节点列表
3. **简单模式** — 只传 `source_connection_id` + `target_connection_id` + `source_tables`，自动构建直连 DAG（默认模式）
- **必填 inputs**: `name`
- **可选 inputs**: `sync_type`（默认 `sync`）, `source_connection_id`, `target_connection_id`, `source_tables`（列表，空则 `.*` 全表）, `target_table_prefix`, `dag_nodes`, `raw_task_config`, `agent_id`
- **Outputs**: `task_id`, `flow_id`, `name`, `sync_type`, `source_node_id`, `target_node_id`, `agent_id`, `source_table_name`, `target_table_name`

#### `tm_start_task`
启动 TM 任务（`PUT /api/Task/start/{id}`），回查真实状态。
- **必填 inputs**: `task_id`
- **Outputs**: `task_id`, `status`

#### `tm_stop_task`
停止运行中的 TM 任务（`PUT /api/Task/stop/{id}`）。
- **必填 inputs**: `task_id`
- **Outputs**: `task_id`, `status`

#### `tm_delete_task`
删除 TM 任务（`DELETE /api/Task/{id}`）。已不存在时不抛异常。
- **必填 inputs**: `task_id`
- **Outputs**: `task_id`, `deleted`

#### `tm_wait_task`
轮询等待任务达到目标状态。具备 milestone 进度摘要（TASK→DEDUCTION→TABLE_INIT→DATA_NODE_INIT→SNAPSHOT→CDC），API 返回 `status=running` 但所有 milestone 已 FINISH 时自动判定完成。
- **必填 inputs**: `task_id`
- **可选 inputs**: `target_status`（默认 `completed`，可 `running`/`stopped`/`error`）, `poll_interval_ms`（默认 3000）, `wait_timeout_seconds`（默认 300）
- **Outputs**: `task_id`, `final_status`, `reached_target`, `attempts`

#### `tm_get_task`
查询单个 Task 当前详情。
- **必填 inputs**: `task_id`
- **Outputs**: `task_id`, `name`, `type`, `status`, `current_step`, `source_connection_id`, `target_connection_id`

---

### 数据校验（Inspect）

#### `tm_create_inspect`
针对某个同步任务（flow）创建数据校验 Inspect。从 Task DAG 自动提取 source/target connection、table。
- **必填 inputs**: `name`, `task_id`（即 flowId）
- **可选 inputs**: `agent_id`, `agent_type`（默认 `private`）, `inspect_mode`（默认 `all`，可 `full`/`incremental`/`all`）, `inspect_method`（默认 `row_count`，可 `value`）, `source_connection_id`, `target_connection_id`, `tables`, `raw_inspect_config`
- **Outputs**: `inspect_id`, `name`, `task_id`

#### `tm_execute_inspect`
执行 Inspect 任务并轮询等待完成（固定 5s 间隔）。
- **必填 inputs**: `inspect_id`
- **可选 inputs**: `wait_timeout_seconds`（默认 300）
- **Outputs**: `inspect_id`, `status`, `result`, `total_tables`, `mismatched_tables`, `total_rows`, `mismatched_rows`, `success`

#### `tm_assert_inspect_result`
断言最近一次 Inspect 结果符合预期。
- **必填 inputs**: `inspect_id`
- **可选 inputs**: `allow_mismatch`（默认 `false` 严格模式）, `max_mismatch_rows`（默认 0，宽松模式下允许上限）
- **Outputs**: `inspect_id`, `assert_passed`, `total_rows`, `mismatched_rows`, `total_tables`, `mismatched_tables`

---

## 通用特性

### 自动清理
- `keep_alive: false`（默认）时，`Connection` / `Task` / `Inspect` 会在 Scenario 结束时自动调用对应 DELETE API
- 设置 `keep_alive: true` 可保留资源用于后续手动调试

### 错误处理
- Connection/Task DELETE 不存在时静默成功（幂等）
- `tm_test_connection` 连接失败不抛异常，结果通过 `results` output 暴露（真正验证在 Task re-schemas 时发生）
- Inspect 不通过时抛 `AssertionFailedException`，标记 Step 失败

### TmClient 配置
- 通过 `before()` 惰性创建，从类路径 / 环境变量 / 构造器参数读取 TM base URL 和 access code
- PATCH 请求因已知的 Java HttpClient + PF4J 隔离问题，走 subprocess curl 作为 workaround

### YAML 示例（完整同步链路）
```yaml
scenario: full_sync_cdc_test
steps:
  - id: login
    action: tm_login

  - id: create_src_ds
    action: tm_create_datasource
    inputs:
      name: "gp_src_mysql"
      database_type: "mysql"
      database_host: "172.17.0.1"
      database_port: 23306
      database_username: "root"
      database_password: "root"
      database_name: "gp_src"

  - id: create_tgt_ds
    action: tm_create_datasource
    inputs:
      name: "gp_tgt_mysql"
      database_type: "mysql"
      database_host: "172.17.0.1"
      database_port: 33306
      database_username: "root"
      database_password: "root"
      database_name: "gp_tgt"

  - id: test_conns
    action: tm_test_connection
    timeout_seconds: 60
    inputs:
      connection_ids:
        - "#{ #steps['create_src_ds'].outputs['connection_id'] }"
        - "#{ #steps['create_tgt_ds'].outputs['connection_id'] }"
      parallel: true

  - id: create_task
    action: tm_create_task
    inputs:
      name: "gp_sync_mysql_mysql"
      sync_type: "cdc"
      source_connection_id: "#{ #steps['create_src_ds'].outputs['connection_id'] }"
      target_connection_id: "#{ #steps['create_tgt_ds'].outputs['connection_id'] }"
      source_tables: [".*"]

  - id: start_task
    action: tm_start_task
    inputs:
      task_id: "#{ #steps['create_task'].outputs['task_id'] }"

  - id: wait_task
    action: tm_wait_task
    timeout_seconds: 300
    inputs:
      task_id: "#{ #steps['create_task'].outputs['task_id'] }"
      poll_interval_ms: 3000
      wait_timeout_seconds: 300
```
