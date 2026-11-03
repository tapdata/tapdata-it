# tapdata-l3-step-assert-jdbc

JDBC 数据断言 Step — 验证任意 JDBC 可达数据库中的数据状态，特别适合 CDC 同步链路的端到端校验。

## Actions

### `assert_jdbc_data`
对指定 SQL 查询执行断言。支持 **6 种断言模式** + **轮询重试** 应对 CDC 最终一致性。

**必填 inputs**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `jdbc_url` | String | JDBC URL |
| `sql` | String | 查询 SQL |

**可选 inputs**:
| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `username` | String | null | 数据库用户名 |
| `password` | String | null | 数据库密码 |
| `driver_class` | String | 自动检测 | 显式指定 JDBC driver |
| `assert_mode` | String | — | 断言模式（SELECT 查询必填） |
| `expected_count` | Integer | — | row_count_equals 模式期望值 |
| `expected_min` | Integer | — | row_count_gte / row_count_gt 模式最小值 |
| `expected` | Object | — | value_equals 模式期望值 |
| `column` | String | — | value_equals 模式断言列名 |
| `sql_type` | String | `auto` | `auto`/`query`/`execute` — `execute` 模式只执行 SQL 不做断言 |
| `poll_interval` | int | 0 | 轮询间隔秒数（0 = 单次尝试不重试） |
| `max_attempts` | int | 1 | 最大尝试次数 |

### 断言模式一览

| assert_mode | 额外输入 | 语义 |
|------------|---------|------|
| `row_count_equals` | `expected_count` | 返回行数 === N |
| `row_count_gte` | `expected_min` | 返回行数 ≥ N |
| `row_count_gt` | `expected_min` | 返回行数 > N |
| `exists` | — | 至少 1 行返回 |
| `empty` | — | 0 行返回 |
| `value_equals` | `column`, `expected` | 第一行的指定列等于期望值 |

### DDL/DML 执行模式
如果 SQL 是 INSERT/UPDATE/DELETE/CREATE/DROP（`sql_type: execute`），不做断言，直接返回受影响行数。

---

## 动态 JDBC Driver

与 `tapdata-l3-step-jdbc` 共享相同的自动驱动加载机制，已内置 7 种数据库驱动坐标（含 SQLServer `com.microsoft.sqlserver:mssql-jdbc:12.6.1.jre11`）。自定义驱动通过 YAML `extra_deps` 指定：

```yaml
extra_deps:
  - "com.dameng:DmJdbcDriver:8.1.3.62"
inputs:
  jdbc_url: "jdbc:dm://..."
  driver_class: "dm.jdbc.driver.DmDriver"
```

---

## YAML 示例

### 基本行数断言
```yaml
- id: verify_source_rows
  action: assert_jdbc_data
  inputs:
    jdbc_url: "#{ #steps['create_src'].outputs['jdbc_url'] }"
    username: "#{ #steps['create_src'].outputs['username'] }"
    password: "#{ #steps['create_src'].outputs['password'] }"
    sql: "SELECT COUNT(*) FROM users"
    assert_mode: "row_count_equals"
    expected_count: 100
```

### 轮询等待 CDC 最终一致性
```yaml
- id: wait_for_cdc
  action: assert_jdbc_data
  timeout_seconds: 180
  inputs:
    jdbc_url: "#{ #steps['create_tgt'].outputs['jdbc_url'] }"
    username: "#{ #steps['create_tgt'].outputs['username'] }"
    password: "#{ #steps['create_tgt'].outputs['password'] }"
    sql: "SELECT COUNT(*) FROM users WHERE col_a LIKE 'inc_%'"
    assert_mode: "row_count_gte"
    expected_min: 10
    poll_interval: 3        # 每 3 秒重试
    max_attempts: 40        # 最多 40 次 = 120 秒
```

### value_equals 断言
```yaml
- id: verify_specific_value
  action: assert_jdbc_data
  inputs:
    jdbc_url: "jdbc:postgresql://localhost/..."
    username: "user"
    password: "pass"
    sql: "SELECT name FROM users WHERE id = 1"
    assert_mode: "value_equals"
    column: "name"
    expected: "Alice"
```

### 执行 DDL
```yaml
- id: drop_table
  action: assert_jdbc_data
  inputs:
    jdbc_url: "jdbc:mysql://..."
    username: "root"
    sql: "DROP TABLE IF EXISTS temp_table"
    sql_type: "execute"
```

---

## 错误处理

- **断言失败** → 抛 `AssertionFailedException`（带详细对比信息）
- **JDBC driver 未找到** → 抛 `IllegalStateException`，提示通过 `extra_deps` 添加
- **查询超时** → `poll_interval` + `max_attempts` 用尽后抛最后一次的异常
- **Class.forName** → 使用当前线程 context ClassLoader（由 `BaseStep` 在异步 worker 线程设置为 Step 专属 `PluginClassLoader`）

## 输出

- **断言模式**: `attempts`, `elapsed_ms`, `row_count`
- **执行模式**: `rows_affected`, `elapsed_ms`
