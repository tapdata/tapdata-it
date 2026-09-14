# TapData 数据源连接器集成测试覆盖范围总览

> 整理日期：2026-09-13
> 覆盖代码库：`tapdata-connectors`（开源，67 个连接器模块）、`tapdata-connectors-enterprise`（闭源，17 个连接器模块）
> 测试框架：`tapdata-it` / `tapdata-connector-it`（连接器通用集成测试框架，本文档所在仓库）

---

## 1. 口径说明（“集成测试”在这里指什么）

TapData 连接器的**集成测试**不是散落在各连接器里的 Mockito 单测（`src/test`），而是：

- 位于各连接器模块 `src/it/java` 目录、以 `*IT` 命名的类（Maven `maven-failsafe-plugin` 执行，与单测 `surefire` 分离）；
- 这些类**继承共享框架基类** `io.tapdata.it.ConnectorIT`，只需实现 `createContext()` 提供连接配置和已初始化的 Connector 实例，即可自动运行框架内置的**全部通用用例**；
- 框架通过 `ConnectorFunctions` **能力检测**驱动：被测连接器未注册的能力对应用例自动 `assumeTrue` 跳过，实现“一份用例、全连接器复用”。

因此“覆盖范围”由两部分构成：

1. **框架内置的通用用例集**（所有接入连接器共享，见 §3）；
2. **各连接器在 `src/it` 中追加/覆写**的方言与场景专属用例（见 §5）。

> 本文档采用**聚焦口径**：只统计“基于 `ConnectorIT` 框架的 `src/it` 集成测试”。其余零散 `*IT`（risingwave `src/test`、enterprise `journal-parsing`）与 `connectors-common/debezium-bucket` 中 vendored 的 Debezium 上游 IT 见 §7 附注，不计入主口径。

---

## 2. 框架架构

```
真实数据源（MySQL / MongoDB / Oracle / DB2 / DB2 i / MSSQL / OceanBase …）
        ▲ ConnectorFunctions 能力调用
        │
tapdata-it / tapdata-connector-it（test scope 依赖，单向依赖具体 Connector）
  io.tapdata.it.ConnectorIT            ← 抽象基类：生命周期 + 74 个通用用例 + 能力检测
  io.tapdata.it.performance.PerformanceConnectorIT  ← 继承 ConnectorIT，+3 性能用例
  io.tapdata.it.tpcc.TpccConnectorIT   ← 继承 PerformanceConnectorIT，+5 TPC-C 用例
  io.tapdata.it.ConnectorTestContext   ← 测试上下文（builder）
  io.tapdata.it.asserts.*              ← RecordAssert / TableAssert / TapValueAssert
  io.tapdata.it.support.*              ← EngineCodecs / TestLog / TestStateMap / TestTableMap …
  io.tapdata.it.tpcc / performance     ← TpccAdapter / PerformanceAdapter 接口 + 配置
        ▲ 继承 + createContext()
        │
各连接器模块： XxxConnectorIT extends ConnectorIT 或 TpccConnectorIT
```

**继承层次与用例规模**

| 层次 | 类 | 用例数 | 说明 |
|---|---|--:|---|
| 基础层 | `ConnectorIT` | 74 | 连接/元数据、DDL、读写、索引约束、事务、CDC、命令、分区、TapValue 编解码往返、能力覆盖元测试 |
| 性能层 | `PerformanceConnectorIT` | +3 | batchRead(1000 行/批)、streamRead(recordSize 100)、writeRecord(4 线程/500 行批)，基于 1KB 记录 |
| 基准层 | `TpccConnectorIT` | +5 | TPC-C 模式与计数、全量 batchRead、写入目标、多表 CDC、断点续读 |
| | **框架合计** | **82** | 接入的连接器按继承层次自动获得对应用例 |

**关键机制**

- **能力驱动自动跳过**：用例内 `require(functions()::getXxxFunction, "capability")` 检测能力，未注册即 `assumeTrue` 跳过（报告中标 `SKIPPED`，区分“未实现”与“未通过”）。
- **声明式必实现契约（“原则 3”）**：`requiredCapabilities()` 默认取连接器**已实现能力全集**（也可用 `SOURCE_ROLE_CAPABILITIES` / `TARGET_ROLE_CAPABILITIES` 收窄）。元用例 `should_all_capabilities_implemented_and_covered` 双校验：
  - 3a：声明必实现的接口必须真的被实现；
  - 3b：已实现的能力**必须至少被一个用例覆盖**——杜绝“实现了但无人测”。
- **隔离与确定性**：表名随机（`_tap_it_` 前缀 + UUID）、`@AfterEach` 兜底删表；随机数据由生成器统一产出，主键用序列（1..N）保证 update/delete 可精确定位。
- **旁路验证**（`requiresVerifier = true`）：用不经连接器能力的独立手段（JDBC 元数据/直查系统表）核对建表、删表等动作真实生效，避免“自证自洽”。
- **数据类型编解码往返**：`should_wrap_*` / `should_validate_wrap_types_against_spec_through_database` 等用例验证 TapValue ↔ 原生值经真实库往返后类型与取值保真。

---

## 3. 框架内置用例覆盖清单（`ConnectorIT`，74 项）

用例↔能力映射取自源码中的 `@UnderTest(value="能力名")` 注解。

### A. 连接与元数据（8）
| 用例 | 覆盖能力 |
|---|---|
| `should_test_connection` | `connectionTest` |
| `should_connection_check` | `connectionCheck` |
| `should_discover_schema_of_created_table` | `discoverSchema` |
| `should_find_table_after_create` | `getTableNames` |
| `should_table_not_found_after_drop` | `dropTable` |
| `should_get_table_info` | `getTableInfo` |
| `should_check_table_name` | `checkTableName` |
| `should_get_charsets` | `getCharsets` |

### B. 表级 DDL（7）
| 用例 | 覆盖能力 |
|---|---|
| `should_create_table_v2` | `createTableV2` |
| `should_create_table_v2_when_exists` | `createTableV2`（幂等） |
| `should_clear_table` | `clearTable` |
| `should_drop_table` | `dropTable`（幂等） |
| `should_alter_table_charset` | `alterTableCharset` |
| `should_alter_table_ttl` | `alterTableTTL` |
| `should_alter_database_timezone` | `alterDatabaseTimeZone` |

### C. 数据写入与读取（8）
| 用例 | 覆盖能力 |
|---|---|
| `should_write_insert_records` | `writeRecord`（insert） |
| `should_batch_count_match` | `batchCount` |
| `should_batch_read_data_consistent` | `batchRead` |
| `should_write_update_records` | `writeRecord`（update） |
| `should_write_delete_records` | `writeRecord`（delete） |
| `should_query_by_filter` | `queryByFilter` |
| `should_query_by_advance_filter` | `queryByAdvanceFilter` |
| `should_after_initial_sync` | `afterInitialSync` |

### D. 索引与约束（9）
| 用例 | 覆盖能力 |
|---|---|
| `should_create_index` / `should_query_indexes` / `should_delete_index` | `createIndex` / `queryIndexes` / `deleteIndex` |
| `should_create_constraint` / `should_query_constraints` / `should_drop_constraint` | `createConstraint` / `queryConstraints` / `dropConstraint` |
| `should_create_foreign_key_constraint` / `should_query_foreign_key_constraints` / `should_drop_foreign_key_constraint` | `createConstraint` / `queryConstraints` / `dropConstraint`（外键） |

### E. 字段级 DDL（4）
| 用例 | 覆盖能力 |
|---|---|
| `should_new_field` | `newField` |
| `should_drop_field` | `dropField` |
| `should_alter_field_name` | `alterFieldName` |
| `should_alter_field_attributes` | `alterFieldAttributes` |

### F. 流式读取 / 增量 CDC（4）
| 用例 | 覆盖能力 |
|---|---|
| `should_timestamp_to_stream_offset` | `timestampToStreamOffset` |
| `should_stream_read_incremental` | `streamRead` |
| `should_stream_read_one_by_one` | `streamReadOneByOne` |
| `should_stream_read_multi_connection` | `streamReadMultiConnection` |

### G. 事务（2）
| 用例 | 覆盖能力 |
|---|---|
| `should_transaction_commit` | `transactionBegin` + `transactionCommit` |
| `should_transaction_rollback` | `transactionBegin` + `transactionRollback` |

### H. 命令与原始操作（5）
| 用例 | 覆盖能力 |
|---|---|
| `should_execute_command` / `should_execute_command_v2` | `executeCommand` / `executeCommandV2` |
| `should_run_raw_command` | `runRawCommand` |
| `should_count_raw_command` | `countRawCommand` |
| `should_export_event_sql` | `exportEventSql` |

### I. 其他能力（16）
| 用例 | 覆盖能力 |
|---|---|
| `should_get_current_timestamp` | `getCurrentTimestamp` |
| `should_query_hash_by_advance_filter` | `queryHashByAdvanceFilter` |
| `should_control` / `should_process_control` | `control` / `processControl` |
| `should_get_stream_offset` / `should_flush_offset` | `getStreamOffset` / `flushOffset` |
| `should_error_handle` | `errorHandle` |
| `should_create_partition_table` / `should_query_partition_tables` / `should_drop_partition_table` | `createPartitionTable` / `queryPartitionTablesByParentName` / `dropPartitionTable` |
| `should_count_by_partition_filter` | `countByPartitionFilter` |
| `should_get_read_partitions` | `getReadPartitions` |
| `should_query_field_min_max` | `queryFieldMinMaxValue` |
| `should_connector_website` / `should_table_website` | `connectorWebsite` / `tableWebsite` |
| `should_command_callback` | `commandCallback` |

### J. TapValue / 编解码往返与类型保真（10）
| 用例 | 覆盖点 |
|---|---|
| `should_wrap_generated_values_to_tap_value` | 生成值 → TapValue 包装 |
| `should_unwrap_tap_value_to_plain_values` | TapValue → 原生值解包 |
| `should_wrap_unwrap_round_trip_keep_values` | 往返保真 |
| `should_preserve_origin_value_when_unwrapping` | origin 值保留 |
| `should_write_records_via_engine_codec_roundtrip` | 引擎 codec 写路径（`writeRecord`） |
| `should_wrap_read_back_values_to_tap_value` | 读回值包装 |
| `should_wrap_null_and_boundary_values` | NULL / 边界值 |
| `should_wrap_special_value_samples` | 特殊样本值 |
| `should_wrap_unwrap_nested_types` | 嵌套类型 |
| `should_validate_wrap_types_against_spec_through_database` | 按 spec `dataTypes` 经真实库校验包装类型 |

### K. 契约校验元测试（1）
| 用例 | 覆盖点 |
|---|---|
| `should_all_capabilities_implemented_and_covered` | 原则 3a/3b：声明必实现能力已实现、已实现能力全体被测 |

### 性能层（`PerformanceConnectorIT`，+3）
`should_measure_batch_read_performance`、`should_measure_stream_read_performance`、`should_measure_write_record_performance`

### 基准层（`TpccConnectorIT`，+5）
`should_read_tpcc_schema_and_counts`、`should_batch_read_all_tpcc_source_rows`、`should_write_tpcc_rows_to_target_tables`、`should_stream_tpcc_transactions`、`should_resume_tpcc_from_saved_offset`

---

## 4. 接入连接器总览

| 仓库 | 连接器模块 | 基类 | 自有用例 | 连接配置 |
|---|---|---|--:|---|
| tapdata-connectors | `connectors/mysql-connector` | `ConnectorIT` | 1 | `src/it/resources/config/mysql-connection.json` |
| tapdata-connectors | `connectors/mongodb-connector` | `ConnectorIT` | 1 | `src/it/resources/config/mongodb-connection.json` |
| tapdata-connectors | `connectors/oceanbase-mysql-connector` | `TpccConnectorIT` | 5 | `src/it/resources/config/oceanbase-mysql-connection.json` |
| tapdata-connectors-enterprise | `connectors/db2-connector` | `TpccConnectorIT` | 12 | `src/it/resources/config/db2-connection.json` |
| tapdata-connectors-enterprise | `connectors/db2i-connector` | `TpccConnectorIT` | 8 | `src/it/resources/config/db2i-connection.json` |
| tapdata-connectors-enterprise | `connectors/mssql-connector` | `TpccConnectorIT` | 5 | `src/it/resources/config/mssql-connection.json` |
| tapdata-connectors-enterprise | `connectors/oracle-connector` | `TpccConnectorIT` | 5 | `src/it/resources/config/oracle-connection.json` |
| tapdata-connectors-enterprise | `connectors/oceanbase-oracle-connector` | `TpccConnectorIT` | 5 | `src/it/resources/config/oceanbase-oracle-connection.json` |

> 合计 **8 个连接器**（开源 3 + 闭源 5）接入框架。继承 `TpccConnectorIT` 的 6 个连接器现在**都**实现了 `TpccAdapter` 与 `PerformanceAdapter`，因此能跑满「连接器通用 + 性能层(3) + 基准层(5)」三层用例。
> 连接配置支持系统属性/环境变量覆盖：`-Dconnector.it.host=…` 或 `CONNECTOR_IT_HOST=…`（键名与连接器 spec 一致）。

---

## 5. 各连接器追加的专属用例

### 5.1 `mysql-connector`（开源，`extends ConnectorIT`）
- `testGithubAction`：占位冒烟用例。
- `requiredCapabilities()` 显式声明 34 项（源+目标双角色），保证“声明必实现—已实现—已覆盖”三向一致。

### 5.2 `mongodb-connector`（开源，`extends ConnectorIT`）
- 1 个占位用例；`requiredCapabilities()` 声明读写/索引/事务等 22 项（`memoryFetcher` 诊断钩子由基类排除）。
- 无 schema 概念，`discoverSchema` 走“先旁路写采样数据再推断”路径；运行需 `-Dapp_type=DAAS`。

### 5.3 `oceanbase-mysql-connector`（开源，`extends TpccConnectorIT`）
| 用例 | 覆盖点 |
|---|---|
| `should_round_trip_full_types_and_one_megabyte_fields` | 全类型 + 1MB 大字段往返 |
| `should_resume_from_saved_offset` | 断点续读 |
| `should_stream_insert_update_and_delete` | CDC 覆盖 I/U/D |
| `should_apply_commit_rollback_savepoint_and_uncommitted_rules` | 事务提交/回滚/保存点/未提交隔离 |
| `should_stream_large_committed_transaction` | 大事务流式 |

- 覆写 `createPerformanceAdapter()`，提供 `OceanbaseMysqlPerformanceAdapter`（oceanbase-client JDBC 旁路建表/写入/计数），启用性能层 3 个用例；`-Pperformance-only` 可单独运行。

### 5.4 `db2-connector`（闭源，`extends TpccConnectorIT`）
覆写 `should_stream_read_incremental` + 追加：
- `should_read_and_stream_db2_extended_types`（扩展原生类型全量+CDC）
- `should_stream_transactional_lob_rows_without_cross_contamination`（LOB 跨行/回滚隔离）
- `should_stream_only_committed_transaction_rows` / `should_not_stream_uncommitted_transaction`
- `should_stream_rows_kept_after_partial_rollback`（保存点部分回滚）
- `should_stream_large_committed_transaction`
- `should_resume_stream_from_saved_offset`（按 LRI 续读）
- `should_read_and_stream_range_partition_table`（范围分区）
- `should_read_and_stream_adaptive_compressed_table`（自适应压缩表）
- `should_stream_db2_table_ddl_and_continue_dml` / `should_continue_stream_after_comment_ddl`（DDL 中断后继续）

### 5.5 `db2i-connector`（闭源，`extends TpccConnectorIT`）
覆写 `should_stream_read_incremental` + 追加：
- `should_read_and_stream_db2i_extended_types`（含 1MiB CLOB/BLOB）
- `should_stream_only_committed_transaction_rows` / `should_not_stream_uncommitted_transaction`
- `should_stream_rows_kept_after_partial_rollback`（保存点部分回滚）
- `should_stream_large_committed_transaction`
- `should_resume_stream_from_saved_offset`（journal 断点续读）
- `should_support_large_table_and_field_names`
- 覆写 `tableColumns` 用 `QSYS2.SYSCOLUMNS` 直查（jt400 JDBC 元数据在该环境恒空）；`createVerifier()` 定制旁路校验。

### 5.6 `mssql-connector`（闭源，`extends TpccConnectorIT`）
- `should_accept_empty_sub_partition_request`（空子分区请求）
- `should_batch_and_stream_uniqueidentifier_and_large_fields`（`uniqueidentifier` + 大字段）
- `should_apply_mssql_commit_rollback_savepoint_and_uncommitted_rules`
- `should_resume_mssql_from_saved_offset`
- `should_stream_mssql_large_committed_transaction`
- 覆写 `createPerformanceAdapter()`，提供 `MssqlPerformanceAdapter`（`EVENT_TIME DATETIME2` 避开 `TIMESTAMP`＝`ROWVERSION` 的坑），启用性能层 3 个用例；`-Pperformance-it` / `-Pperformance-only` 可运行。

### 5.7 `oracle-connector`（闭源，`extends TpccConnectorIT`）
覆写 `tpccStreamReadFunction` / `tpccGetStreamOffsetFunction`（TPC-C 用 LogMiner），追加：
- `should_stream_oracle_committed_rows_with_supplemental_log`（补充日志 CDC）
- `should_batch_and_stream_oracle_clob_and_blob`
- `should_apply_oracle_commit_rollback_savepoint_and_uncommitted_rules`
- `should_resume_oracle_from_saved_offset`
- `should_stream_oracle_large_committed_transaction`
- 覆写 `createPerformanceAdapter()`，提供 `OraclePerformanceAdapter`（Oracle thin JDBC 旁路，`NUMBER(19)`/`VARCHAR2`），启用性能层 3 个用例；`-Pperformance-it` / `-Pperformance-only` 可运行。

### 5.8 `oceanbase-oracle-connector`（闭源，`extends TpccConnectorIT`）
- `should_round_trip_full_types_and_one_megabyte_fields`
- `should_resume_from_saved_offset`
- `should_stream_insert_update_and_delete`
- `should_apply_commit_rollback_savepoint_and_uncommitted_rules`
- `should_stream_large_committed_transaction`
- 覆写 `prepareStreamReadTable`（`sleep(20s)`）、`streamReadTimeoutSeconds=90`、`waitForStreamReadCatchUp=true`。
- 覆写 `createPerformanceAdapter()`，提供 `OceanbaseOraclePerformanceAdapter`（oceanbase-client JDBC 旁路，`NUMBER(19)`/`VARCHAR2`），启用性能层 3 个用例；`-Pperformance-only` 可单独运行。

---

## 6. 覆盖缺口（尚未接入框架的连接器）

**开源仓库 67 个连接器模块中，仅 3 个有 `src/it` 集成测试，其余 64 个未接入**：

`activemq, aliyun-adb-mysql, aliyun-adb-postgres, aliyun-mongodb, aliyun-rds-mariadb, aliyun-rds-mysql, aliyun-rds-postgres, aws-clickhouse, aws-rds-mysql, azure-cosmosdb, bigquery, clickhouse, coding, connector-perf-test, csv, custom, databend, doris, dummy, dws, elasticsearch, excel, file-stream, greenplum, hazelcast, hbase, highgo, http-receiver, huawei-cloud-gaussdb, json, kafka-avro, kafka, kafka-enhanced, mariadb, mock-source, mock-target, mongodb-atlas, mongodb-lower, mysql-pxc, opengauss, paimon-plus, polar-db-mysql, polar-db-postgres, postgres, quickapi, rabbitmq, redis, risingwave, rocketmq, selectdb, snowflake, starrocks, tablestore, tdd, tdengine, tencent-db-mariadb, tencent-db-mongodb, tencent-db-postgres, tidb, vastbase, vika, xml, yashandb, zoho-desk`

**闭源仓库 17 个连接器模块中，5 个有 `src/it`，其余 12 个未接入**：

`aliyun-rds-mssql, dameng, gbase8a, gbase8s, hana, informix, iris, kingbaser3, kingbaser6, sybase, tencent-db-mssql, tencent-db-mysql`

> 注意：无 `src/it` **不代表无测试**——这些连接器普遍有 `src/test` 下的 Mockito 单测；缺的是“真实数据源上的端到端能力验证”。其中 `postgres`、`tidb`、`mariadb`、`greenplum`、`starrocks`、`clickhouse`、`redis`、`elasticsearch`、`kafka` 等主流源/目标是接入框架的高优先级候选。

---

## 7. 附注：其他 `*IT`（不计入主口径）

| 位置 | 性质 | 说明 |
|---|---|---|
| `tapdata-connectors/connectors/risingwave-connector/src/test/…/*IT.java` | 连接器自有 IT，放在 `src/test` 下 | `RisingWaveConnectionTestIT`、`RisingWaveTlsIT`、`RisingWaveWriteBenchmarkIT`；独立于 `ConnectorIT` 框架 |
| `tapdata-connectors-enterprise/connectors-common/journal-parsing/src/it/…` | 组件级 IT | DB2 journal 解析（`JdbcFileDecoderIT`、`JournalBufferFullIT`、`JournalEntryCcsidIT` 等 6 个）；针对 `journal-parsing` 公共模块，非某个连接器 |
| `tapdata-connectors/connectors-common/debezium-bucket/**/src/test/**/*IT.java` | **vendored Debezium 上游测试** | `debezium-connector-mysql` / `-postgres` / `-highgo` 内自带的上游 IT（各 20~30 个），随 fork 一并进入仓库，**不是 TapData 连接器集成测试** |

---

## 8. 运行方式与 CI

**本地运行（单连接器）**
```bash
mvn -pl connectors/mysql-connector -am install -DskipTests                 # 安装 tapdata-it 等依赖
cd connectors/mysql-connector
mvn test-compile failsafe:integration-test failsafe:verify -DskipITs=false -o
# 连接参数经 -D 传入：-Dconnector.it.host=127.0.0.1 -Dconnector.it.port=3306 …
# MongoDB 需：-Dapp_type=DAAS
# 性能层（batchRead/streamRead/writeRecord）：追加 -Pperformance-only（db2/db2i/oracle/mssql 也可用 -Pperformance-it）
```

**CI 接线**

- 两个连接器仓库各有入口 workflow `tapdata-connectors/.github/workflows/connector-it.yml`、`tapdata-connectors-enterprise/.github/workflows/connector-it.yml`；
- 两者均 `uses: tapdata/tapdata-it/.github/workflows/connector-it-reusable.yml@main`，实际逻辑集中在 `tapdata-it` 维护；
- 触发：`push`（`connectors/**`、`connectors-common/**`、`pom.xml`、`.github/**` 变更）、PR、`workflow_dispatch`（可手填模块列表）；
- 变更左移：reusable workflow 分析改动文件，只挑选**被修改且含 IT 用例**的模块跑矩阵（`connectors-common` 变更时按 `all`/`affected` 策略）；
- 运行环境：自托管 runner（`office-build`）。

**历史执行结果（tapdata-it 报告，2026-08-26，当时框架 75 用例）**

| Connector | 总用例 | 通过 | 失败 | 错误 | 跳过 | 通过率 |
|---|--:|--:|--:|--:|--:|--:|
| MySQL | 75 | 51 | 0 | 0 | 24 | 100% |
| MongoDB | 75 | 35 | 0 | 0 | 40 | 100% |
| DB2 i (AS400) | 75 | 38 | 0 | 1 | 36 | 97.4% |

> 跳过项均为“连接器未注册该能力”的正常跳过；DB2 i 唯一错误 `should_drop_field` 经实验确认为 **DB2 i 服务器端拒绝 `ALTER TABLE … DROP COLUMN`（SQL0952/RC10）**，连接器 SQL 生成正确，属环境限制。报告详见 `docs/report/`。

---

## 9. 已知边界与限制

1. **覆盖面小**：开源 67 + 闭源 17 = 84 个连接器模块中仅 8 个接入框架，覆盖以 RDBMS 与 MongoDB 为主；NoSQL/消息队列/对象存储类连接器普遍未接入（许多无建表/DDL 概念，需走框架的“无表 DDL 退化”路径适配）。
2. **需真实数据源**：IT 直连真实库（报告环境为 `113.98.206.x` 上的 MySQL/MongoDB/DB2i 等），本地无 docker 化数据库（`tapdata-it/docker` 仅提供 runner），环境依赖较强。
3. **仅验证 `ConnectorFunctions` 层**：框架直连 PDK 能力函数，**跳过引擎侧 DAG 编排**；引擎级端到端一致性由 `iengine-app` 与 `auto-test` 承担，二者互补。
4. **跳过率偏高**：如 MongoDB 40/75 跳过，源于其未注册大量能力——“100% 通过”只说明“未被跳过的用例全通过”，不代表能力覆盖全。
5. **方言特例需自备**：DB2 i 的 jt400 元数据假阴性、Oracle LogMiner 补充日志、OceanBase 大事务等，均通过连接器侧覆写 `Verifier`/超时/`prepareStreamReadTable` 规避，非框架通用能力。

---

## 附：数据来源

- 框架：`tapdata-it/tapdata-connector-it/src/main/java/io/tapdata/it/ConnectorIT.java`（74 用例）、`performance/PerformanceConnectorIT.java`（3）、`tpcc/TpccConnectorIT.java`（5）
- 设计文档：`tapdata-it/docs/connector-it.md`、`docs/tapdata-connector-it-architecture.md`
- 报告：`tapdata-it/docs/report/2026-08-12|08-17|08-26-*.md`
- 各连接器：`*/src/it/java/**/*ConnectorIT.java` 与 `*/src/it/resources/config/*-connection.json`
