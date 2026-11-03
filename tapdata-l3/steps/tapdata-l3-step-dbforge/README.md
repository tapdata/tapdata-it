# tapdata-l3-step-dbforge

DBForge 数据库租约管理 Step 模块 — 按需申请/销毁临时数据库，执行跨引擎 SQL。

## Actions

### `dbforge_create_database`
通过 DBForge 服务申请一个数据库租约（lease）。申请成功后自动注册 Scenario 结束时的清理钩子（除非 `keep_alive: true`）。

**必填 inputs**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `db_type` | String | 数据库类型：`mysql` / `postgresql` / `mongodb` / `sqlserver` / `clickhouse` 等 |

**可选 inputs**:
| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `mode` | String | `dedicated` | `dedicated`（独占实例）或 `shared`（共享实例） |
| `topology` | String | `single` | `single`（单节点）或 `replica`（主从） |

**Outputs**（可直接在后续 Step 引用）:
| 字段 | 说明 |
|------|------|
| `lease_id` | 租约唯一 ID（销毁时用） |
| `jdbc_url` | JDBC 连接串 |
| `uri` | 原生连接 URI（MongoDB 用） |
| `host`, `port`, `database`, `username`, `password` | 拆分后的连接信息 |

**YAML 示例**:
```yaml
- id: create_src_db
  action: dbforge_create_database
  keep_alive: false  # 默认 false，Scenario 结束自动销毁
  inputs:
    db_type: "mysql"
    mode: "dedicated"
    topology: "single"

- id: create_tgt_db
  action: dbforge_create_database
  inputs:
    db_type: "postgresql"
```

---

### `dbforge_destroy_database`
手动销毁/释放指定的 DBForge 租约。作为自动清理的替代（调试时可能希望手动控制）。

**必填 inputs**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `lease_id` | String | 租约 ID，通常引用 `dbforge_create_database` 的 `outputs['lease_id']` |

**Outputs**: `lease_id`, `destroyed`

---

### `dbforge_execute_sql`
在任意 JDBC 可达数据库上执行 DDL / DML / 查询 SQL。支持多语句（分号分隔）。

**必填 inputs**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `jdbc_url` | String | JDBC URL |
| `username` | String | 数据库用户名 |
| `sql` | String | SQL 语句（分号分隔多语句） |

**可选 inputs**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `password` | String | 数据库密码（SQLite/DuckDB 等文件数据库可空） |
| `driver_class` | String | 显式指定 JDBC driver class；缺省按 URL 前缀自动匹配 |

**自动驱动匹配**：`jdbc:mysql:` → `com.mysql.cj.jdbc.Driver`，`jdbc:postgresql:` → `org.postgresql.Driver`，`jdbc:sqlite:` → `org.sqlite.JDBC`，`jdbc:clickhouse:` → `com.clickhouse.jdbc.ClickHouseDriver`，`jdbc:sqlserver:` → `com.microsoft.sqlserver.jdbc.SQLServerDriver`，`jdbc:oracle:` → `oracle.jdbc.OracleDriver`，`jdbc:mariadb:` → `org.mariadb.jdbc.Driver`

**Outputs**: `affected_rows`, `statement_count`, `results`（SELECT 语句最多收集 100 行结果集）

**YAML 示例**:
```yaml
- id: setup_schema
  action: dbforge_execute_sql
  inputs:
    jdbc_url: "#{ #steps['create_src_db'].outputs['jdbc_url'] }"
    username: "#{ #steps['create_src_db'].outputs['username'] }"
    password: "#{ #steps['create_src_db'].outputs['password'] }"
    sql: |
      CREATE TABLE users (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255), age INT);
      CREATE TABLE orders (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT, amount DECIMAL(10,2));
      INSERT INTO users (name, age) VALUES ('Alice', 30), ('Bob', 25);
```

---

## DBForge Client 配置

DBForge 服务端地址和认证信息通过环境变量或配置文件 `~/.dbforge` 读取。非 Spring 注入，在 `before()` 中惰性创建 `DbforgeClient`。

## 自动清理
`keep_alive: false`（默认）时，`DbforgeCreateDatabaseAction` 会在 Scenario 上下文注册 `destroyLease` 清理动作，Scenario 结束自动释放所有租约。
