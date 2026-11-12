# TapData L3 系统集成测试框架（tapdata-l3）

L3 = 对**整套部署的 TapData 产品**做黑盒端到端测试。用例以 **DSL YAML** 编排，Step 通过 SPI 插件动态加载，CLI 打包为可执行 Fat Jar，可本地或 CI 直接运行。

- 设计文档：[docs/tapdata_l3-design.md](../docs/tapdata_l3-design.md)
- 用例目录 / 认领台账见 wiki：`07_集成测试/tapdata/管理端/集成测试用例目录.md`

## 模块结构

| 模块 | 职责 |
|---|---|
| `tapdata-l3-cli` | 入口（Picocli），提供 `run` / `describe` / `validate` 子命令 |
| `tapdata-l3-core` | Runtime：DSL 解析、场景执行、并行调度、报告输出（唯一依赖 Spring Boot 的模块） |
| `tapdata-l3-plugin` | SPI：`ActionHandler` 接口、`BaseStep`、`StepResult`（零 Spring 依赖） |
| `tapdata-l3-common` | 通用资产：`ScenarioConfig`、`ScenarioContext`、`SafeSpelEvaluator`、控制流模型 |
| `steps/*` | 内置 Step 插件（dbforge / tm / jdbc / assert / deploy 等） |

## 快速开始

```bash
cd tapdata-l3
mvn -q -DskipTests package

# 查看已注册 Step
java -jar tapdata-l3-cli/target/tapdata-l3-cli.jar describe

# 只校验 DSL（语法 + 依赖预加载 + Handler 参数校验，不执行）
java -jar tapdata-l3-cli/target/tapdata-l3-cli.jar validate --dir cases/

# 执行用例目录
java -jar tapdata-l3-cli/target/tapdata-l3-cli.jar run --dir cases/ --group functional
```

`run` 常用参数：`--group functional|destructive|stability`、`--case-ids L3-F-01,L3-F-02`、`--output <dir>`。
全局插件仓库参数：`--remote-repos`、`--nexus-repo`、`--local-repo-dir`、`--plugins-dir`。

## 基础结构

```yaml
case_id: "L3-F-01"          # 必填，关联 wiki 用例编号
scenario: "MySQL → PostgreSQL CDC 黄金路径"
group: "functional"         # functional / destructive / stability
description: "可选说明"
env:                        # 可选：注入 System Property + SpEL #env
  TM_BASE_URL: "http://127.0.0.1:3030"
variables:                  # 可选：场景级变量，SpEL 通过 #variables 访问
  db_type: "mysql"
steps:                      # 必填，非空
  - id: create_src_db
    action: dbforge_create_database
    inputs:
      db_type: "#{ #variables['db_type'] }"
    outputs: [uri]
```

Step 通用字段：`id`（必填、全局唯一）、`action`、`inputs`、`outputs`、`timeout_seconds`、`keep_alive`、`hooks`、`use`（Maven 坐标，覆盖内置注册表）、`extra_deps`。

表达式与模板：

| 写法 | 用途 |
|---|---|
| `#{ ... }` | SpEL 安全求值，如 `#{ #variables['x'] }`、`#{ #steps['a'].outputs['uri'] }`、`#{ #env['TM_BASE_URL'] }` |
| `${var}` | 字符串模板替换，主要用于 `for` / `while` 循环内生成唯一 step id 与参数 |

## 控制流语法

`steps` 中的每个元素可以是普通 Step、`parallel`、`if`、`for`、`while`、`switch`，且控制流之间支持任意嵌套。

### if — 条件分支

```yaml
- if:
    condition: "#{ #variables['enable_cdc'] }"   # SpEL 或 true/false 字面量
    then:
      - id: start_cdc
        action: tm_api_start_task
        inputs:
          task_id: "#{ #steps['create_task'].outputs['task_id'] }"
    else:                                        # 可选
      - id: skip_cdc
        action: echo
```

`condition` 在运行时求值：`Boolean` 原值、数字非 0、非空集合/Map、`true/yes/y/1/on` 视为真。

### for — 遍历集合

```yaml
- for:
    var: sink_idx            # 必填，循环变量
    index_var: i             # 可选，零基索引
    items: [1, 2, 3]         # 列表字面量，或 SpEL："#{ #variables['targets'] }"
    steps:
      - id: "create_sink_db_${sink_idx}"
        action: dbforge_create_database
        inputs:
          db_type: postgresql
          suffix: "${sink_idx}"
          index: "${i}"
        outputs:
          - "sink_uri_${sink_idx}"
```

- 循环变量写入 `ScenarioContext`：`#{ #variables['sink_idx'] }`。
- `${var}` / `${index_var}` 会替换嵌套 Step 的 `id`、`inputs`、`outputs`、`hooks`。
- 建议用 `${...}` 让每轮 Step id 唯一；否则多轮会复用同一 id，`outputs` 以最后一轮为准。

### while — 条件循环

```yaml
- while:
    condition: "#{ #variables['retry'] < 3 }"   # 每轮开始前重新求值
    index_var: i                                 # 可选
    max_iterations: 100                          # 可选，默认 1000
    steps:
      - id: "poll_${i}"
        action: tm_api_get_task_status
        inputs:
          task_id: "#{ #steps['create_task'].outputs['task_id'] }"
        outputs: [status]
```

- `condition` 可引用上一轮 Step 更新过的变量或 outputs。
- 超过 `max_iterations` 直接抛错并使场景失败，避免死循环；多层循环嵌套时注意迭代次数相乘。
- `index_var` 在循环结束后自动恢复/移除。

### switch — 多路分支

```yaml
- switch:
    expression: "#{ #variables['db_type'] }"
    cases:
      mysql:
        - id: mysql_setup
          action: echo
      postgresql:
        - id: pg_setup
          action: echo
    default:                    # 可选；无命中且无 default 时不执行
      - id: unknown_setup
        action: echo
```

比较方式为 `String.valueOf(expression)` 与 case key 的字符串形式。

### parallel — 并行 Step 组

```yaml
- parallel:
    - id: create_sink_a
      action: dbforge_create_database
      inputs: { db_type: postgresql, suffix: a }
    - id: create_sink_b
      action: dbforge_create_database
      inputs: { db_type: postgresql, suffix: b }
```

硬规则：同一 `parallel` 块内的 Step 必须相互独立，**禁止引用同块内其他 Step 的 outputs**，解析阶段会直接拒绝。`parallel` 只能包含普通 Step，不能包含控制流块；控制流块内部可以放 `parallel`。

### 嵌套示例

`for → switch → while → if` 四层混合：

```yaml
- for:
    var: mode
    items: "#{ #variables['modes'] }"
    steps:
      - switch:
          expression: "#{ #variables['mode'] }"
          cases:
            a:
              - while:
                  condition: "#{ #variables['counter'] < 2 }"
                  index_var: w
                  steps:
                    - if:
                        condition: "#{ #variables['w'] == 1 }"
                        then:
                          - id: "a_hit_${mode}_${w}"
                            action: echo
                        else:
                          - id: "a_miss_${mode}_${w}"
                            action: echo
            b:
              - id: b_step
                action: echo
```

嵌套规则与注意点：

1. 模板按层级链式替换：外层 `for` 的 `${mode}` 先替换，内层 `while` 的 `${w}` 后替换。
2. 循环变量按层级保存/恢复；同名变量会临时遮蔽外层，退出后还原。
3. `step id` 在解析期做全局唯一性校验，嵌套分支内重复的 id 同样会被拒绝。
4. 依赖预加载与 preflight 校验会递归覆盖 `if/for/while/switch` 的所有分支（即使运行时不会执行）。
