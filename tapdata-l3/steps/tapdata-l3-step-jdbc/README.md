# tapdata-l3-step-jdbc

通用 JDBC 数据库操作 Step 模块 — 自动建表造数（`jdbc_init_source`）+ 增量写入触发 CDC（`jdbc_write_incremental`）。

## 动态 JDBC Driver 加载（核心特性）

本模块的两个 Action 都会在运行时自动声明以下 JDBC Driver 的 Maven 坐标，`PluginCenter` 会在首次执行时通过 `maven-resolver-supplier` 下载并注入到 Step 专属 `URLClassLoader`：

| 数据库 | driver class | Maven 坐标 |
|--------|-------------|-----------|
| MySQL 8+ | `com.mysql.cj.jdbc.Driver` | `mysql:mysql-connector-j:8.3.0` |
| MariaDB | `org.mariadb.jdbc.Driver` | `org.mariadb.jdbc:mariadb-java-client:3.3.3` |
| PostgreSQL | `org.postgresql.Driver` | `org.postgresql:postgresql:42.7.3` |
| SQLite | `org.sqlite.JDBC` | `org.xerial:sqlite-jdbc:3.45.2.0` |
| ClickHouse | `com.clickhouse.jdbc.ClickHouseDriver` | `com.clickhouse:clickhouse-jdbc:0.6.0` |
| Oracle 23+ | `oracle.jdbc.OracleDriver` | `com.oracle.database.jdbc:ojdbc11:23.3.0.23.09` |

### 自定义驱动
如果内置表没有你用的数据库（如 SQLServer、达梦、人大金仓等），可以通过 YAML `extra_deps` 指定：
```yaml
- id: custom_driver_demo
  action: jdbc_write_incremental
  extra_deps:
    - "com.microsoft.sqlserver:mssql-jdbc:12.6.1.jre11"
  inputs:
    jdbc_url: "jdbc:sqlserver://..."
    driver_class: "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    ...
```

### Driver 类检测优先级
1. 用户显式指定 `driver_class`（YAML inputs）
2. 从 `jdbc_url` 前缀自动匹配（覆盖上表中所有数据库）

---

## Actions

### `jdbc_init_source`
自动建表 + 批量造数，作为 CDC 增量同步的源端数据准备步骤。

**必填 inputs**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `jdbc_url` | String | JDBC URL |

**可选 inputs**:
| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `username` | String | null | 数据库用户名 |
| `password` | String | null | 数据库密码 |
| `driver_class` | String | 自动检测 | 显式指定 JDBC driver class |
| `table_count` | int | 1 | 要创建的表数量 |
| `rows_per_table` | int | 100 | 每个表的初始行数 |
| `columns_per_table` | int | 5 | 自动生成时的列数 |
| `table_names` | List | 自动生成 | 自定义表名列表 |
| `column_defs` | List\<Map\> | 自动生成 | 自定义列定义 `[{name, type, nullable}]` |
| `drop_if_exists` | boolean | true | DROP TABLE IF EXISTS |

**Outputs**: `table_names`, `total_rows`, `tables_created`

**自动生成特性**:
- 表名：`test/src/gp/sync/data` 前缀 + `users/orders/...` 随机词 + runId
- 列类型池：`INTEGER`, `BIGINT`, `VARCHAR(255)`, `DOUBLE`, `DECIMAL(10,2)`, `DATE`
- 列名：`col_a`, `col_b`, ... （自动分配安全类型避免 strict mode 截断）
- 第一列固定为 `id BIGINT PRIMARY KEY AUTO_INCREMENT`
- 数据类型感知：按 `java.sql.Types` 匹配 random bind

---

### `jdbc_write_incremental`
向源数据库写入增量操作（INSERT / UPDATE / DELETE / TRANSACTION），触发 CDC 通道产生 binlog 事件。

**必填 inputs**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `jdbc_url` | String | JDBC URL |

**两种使用模式**：

#### 模式 A：按 operation_counts 自动生成增量
```yaml
- id: write_inc
  action: jdbc_write_incremental
  inputs:
    jdbc_url: "#{ #steps['init_source'].outputs['jdbc_url'] }"
    table_name: "#{ #steps['init_source'].outputs['table_names'][0] }"
    use_transaction: true
    operation_counts:
      insert: 10
      update: 20
      delete: 100
```
**专属 inputs**:
| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `table_name` | String | — | 目标表 |
| `operation_counts` | Map | insert=10,update=20,delete=100 | 各操作数量 |
| `use_transaction` | boolean | true | 事务包裹 |

**UPDATE 逻辑**: 取 `MAX(id)` 后从尾部 200 行内随机选 id，更新第一列（非 id）的值为 `updated_<random>`

**DELETE 逻辑**: 取 `MAX(id)` 后从尾部 300 行内随机选 id（不重复），删除指定数量

#### 模式 B：自定义 SQL
```yaml
- id: write_sql
  action: jdbc_write_incremental
  inputs:
    jdbc_url: "jdbc:mysql://..."
    custom_sql:
      - "INSERT INTO users (name) VALUES ('Alice')"
      - "UPDATE users SET name='Bob' WHERE id=1"
      - "DELETE FROM users WHERE id=1"
```
或 `script_path` 指向 SQL 文件。

**Outputs**: `total_operations`, `operations_insert`, `operations_update`, `operations_delete`, `elapsed_ms`（模式 A）；`total_operations`, `elapsed_ms`（模式 B）

---

## YAML 示例（CDC 完整链路）
```yaml
scenario: mysql_to_mysql_cdc
steps:
  - id: init_source
    action: jdbc_init_source
    inputs:
      jdbc_url: "#{ #steps['create_src_db'].outputs['jdbc_url'] }"
      username: "#{ #steps['create_src_db'].outputs['username'] }"
      password: "#{ #steps['create_src_db'].outputs['password'] }"
      table_count: 1
      columns_per_table: 5
      rows_per_table: 100

  # ... TM create_datasource / create_task / start_task / wait_task ...

  - id: write_incremental
    action: jdbc_write_incremental
    timeout_seconds: 120
    inputs:
      jdbc_url: "#{ #steps['create_src_db'].outputs['jdbc_url'] }"
      table_name: "#{ #steps['init_source'].outputs['table_names'][0] }"
      use_transaction: true
      operation_counts:
        insert: 10
        update: 20
        delete: 100

  - id: verify_target
    action: assert_jdbc_data
    inputs:
      jdbc_url: "#{ #steps['create_tgt_db'].outputs['jdbc_url'] }"
      username: "#{ #steps['create_tgt_db'].outputs['username'] }"
      password: "#{ #steps['create_tgt_db'].outputs['password'] }"
      sql: "SELECT COUNT(*) FROM #{ #steps['init_source'].outputs['table_names'][0] } WHERE col_a LIKE 'inc_%'"
      assert_mode: "row_count_gte"
      expected_min: 10
      poll_interval: 3
      max_attempts: 20
```
