package io.tapdata.it.verifier;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.List;
import java.util.Map;

/**
 * 旁路数据验证器：直连被测 Connector 的**对端数据源**获取结果数据，
 * 全程不经过 Connector 的任何 read 能力（batchRead/queryByFilter/batchCount/streamRead 等）。
 * <p>
 * 用于验证 Connector 写入/读取的数据真实发生在对端数据源且符合预期，
 * 避免"用 Connector 的 read 验证 Connector 的 write"导致逻辑自洽通过。
 * <p>
 * 同时提供旁路准备能力（{@link #createTable} / {@link #insert}）：建表与数据准备
 * 也不经过 Connector 的 createTableV2/writeRecord，保证被测能力不与自身其他能力形成自洽。
 * <p>
 * 实现来源（按优先级）：
 * <ol>
 *   <li>RDBMS：Connector 实例的 JdbcContext 成员（反射获取其 HikariDataSource，JDBC 直连）</li>
 *   <li>MongoDB：Connector 实例的 MongoClient 成员（直连查询）</li>
 *   <li>其他数据源：子类覆写 {@code ConnectorIT.createVerifier()} 提供专属实现</li>
 * </ol>
 */
public interface ConnectorVerifier {

    /**
     * 直连对端数据源统计表行数（等价 SELECT COUNT(*)）。
     *
     * @param table 表名（RDBMS）/集合名（MongoDB）
     */
    long count(String table) throws Exception;

    /**
     * 直连对端数据源按主键批量取行（等价 SELECT * WHERE pk IN (...)，不经过 Connector）。
     *
     * @param table    表名/集合名
     * @param pkName   主键列名
     * @param pkValues 主键值集合
     * @return 行列表（列名 → 对端原生值）
     */
    List<Map<String, Object>> selectByPk(String table, String pkName, List<Object> pkValues) throws Exception;

    /**
     * 直连对端数据源读取全部行（等价 SELECT *，不经过 Connector）。
     * 无固定排序要求，调用方按需自行排序比对。
     *
     * @param table 表名/集合名
     * @return 行列表（列名 → 对端原生值；MongoDB 文档含 _id 字段，比对时由调用方忽略）
     */
    List<Map<String, Object>> selectAll(String table) throws Exception;

    /**
     * 直连对端数据源建表（等价 CREATE TABLE，不经过 Connector 的 createTableV2）。
     *
     * @param table  表名/集合名
     * @param fields 字段定义（方言 dataType 由子类 TestTableSpec 提供，直接拼 SQL）
     */
    void createTable(String table, List<TestFieldSpec> fields) throws Exception;

    /**
     * 直连对端数据源批量写入（等价 INSERT，不经过 Connector 的 writeRecord）。
     *
     * @param table 表名/集合名
     * @param rows  行列表（列名 → 值，首行的列序作为插入列序）
     */
    void insert(String table, List<Map<String, Object>> rows) throws Exception;

    /**
     * 直连对端数据源按条件更新行（等价 UPDATE ... SET ... WHERE column = value，
     * 旁路准备增量 DML 事件，不经过 Connector 的 writeRecord）。
     *
     * @param table       表名/集合名
     * @param setValues   待更新列（列名 → 新值）
     * @param whereColumn 条件列名（通常为主键）
     * @param whereValue  条件值
     * @return 影响行数（MongoDB 返回 matched 行数）
     */
    int update(String table, Map<String, Object> setValues, String whereColumn, Object whereValue) throws Exception;

    /**
     * 直连对端数据源按条件删除行（等价 DELETE WHERE column = value，
     * 旁路准备增量 DML 事件，不经过 Connector 的 writeRecord）。
     *
     * @param table       表名/集合名
     * @param whereColumn 条件列名（通常为主键）
     * @param whereValue  条件值
     * @return 影响行数
     */
    int delete(String table, String whereColumn, Object whereValue) throws Exception;

    /**
     * 直连对端数据源判断表是否存在（旁路锚点：DDL 被测动作是否真实生效）。
     * RDBMS 表不存在时 COUNT 抛 SQL 异常返回 false；MongoDB 按集合名匹配。
     */
    boolean tableExists(String table) throws Exception;

    /**
     * 直连对端数据源执行一条 DDL 语句（旁路制造源端 DDL，用于验证 Connector 的 DDL 采集能力，
     * 不经过 Connector 的 newField/dropField/alterFieldName）。默认不支持，RDBMS 验证器覆写。
     */
    default void executeDdl(String sql) throws Exception {
        throw new UnsupportedOperationException("executeDdl is not supported by " + getClass().getSimpleName());
    }

    /**
     * 直连对端数据源删表（旁路清理，不经过 Connector 的 dropTable）。
     */
    void dropTable(String table) throws Exception;

    /**
     * 直连对端数据源读取列元数据（等价 JDBC DatabaseMetaData.getColumns，字段级 DDL 的旁路验证）。
     * 每行含 name/type/size 键；无固定 schema 的数据源（MongoDB）返回空列表。
     */
    List<Map<String, Object>> tableColumns(String table) throws Exception;

    /**
     * 直连对端数据源列出索引名（旁路验证 createIndex/deleteIndex 动作，不经过 Connector 的 queryIndexes）。
     */
    List<String> listIndexes(String table) throws Exception;

    /**
     * 直连对端数据源建唯一索引（旁路准备，不经过 Connector 的 createIndex）。
     */
    void createIndex(String table, String indexName, String column) throws Exception;

    /**
     * 直连对端数据源列出约束名（旁路验证 createConstraint/dropConstraint 动作，不经过 Connector 的 queryConstraints）。
     * 无约束概念的数据源（MongoDB）返回空列表。
     */
    List<String> listConstraints(String table) throws Exception;

    /**
     * 直连对端数据源建唯一约束（旁路准备，不经过 Connector 的 createConstraint）。
     * 无约束概念的数据源（MongoDB）为空操作。
     */
    void createConstraint(String table, String constraintName, String column) throws Exception;

    /**
     * 直连对端数据源建外键约束（旁路准备，不经过 Connector 的 createConstraint）。
     * 无约束概念的数据源（MongoDB）为空操作。
     *
     * @param table           引用方表名
     * @param constraintName  外键约束名
     * @param column          外键列名
     * @param referencesTable 被引用表名
     * @param referencesColumn 被引用列名
     */
    void createForeignKeyConstraint(String table, String constraintName, String column,
                                    String referencesTable, String referencesColumn) throws Exception;

    /**
     * 直连对端数据源按表名前缀批量删表（旁路兜底清理，不经过 Connector 的 dropTable）。
     * 用于清理外键用例辅助父表等 tearDown 感知不到的外部残留表；无表概念的
     * 数据源（MongoDB）为空操作。
     *
     * @param prefix 表名前缀（如 {@code _tap_it_fkp_}），仅删除以该前缀开头的表
     */
    default void dropTablesByPrefix(String prefix) throws Exception {
        // 默认空操作：仅 RDBMS 需要前缀清理（MongoDB 集合无残留概念）
    }

    /** 释放旁路连接资源（数据源/客户端由 Connector 生命周期管理，通常无需额外关闭） */
    default void close() throws Exception {
    }
}
