package io.tapdata.it;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapdata.entity.codec.TapCodecsRegistry;
import io.tapdata.entity.codec.filter.TapCodecsFilterManager;
import io.tapdata.entity.mapping.DefaultExpressionMatchingMap;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.control.HeartbeatEvent;
import io.tapdata.entity.event.ddl.constraint.TapCreateConstraintEvent;
import io.tapdata.entity.event.ddl.constraint.TapDropConstraintEvent;
import io.tapdata.entity.event.ddl.entity.ValueChange;
import io.tapdata.entity.event.ddl.table.TapAlterDatabaseTimezoneEvent;
import io.tapdata.entity.event.ddl.table.TapAlterFieldAttributesEvent;
import io.tapdata.entity.event.ddl.table.TapAlterFieldNameEvent;
import io.tapdata.entity.event.ddl.table.TapAlterTableCharsetEvent;
import io.tapdata.entity.event.ddl.table.TapAlterTableTTLEvent;
import io.tapdata.entity.event.ddl.table.TapDropFieldEvent;
import io.tapdata.entity.event.ddl.table.TapNewFieldEvent;
import io.tapdata.entity.event.dml.TapDeleteRecordEvent;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.event.dml.TapUpdateRecordEvent;
import io.tapdata.entity.schema.TapConstraint;
import io.tapdata.entity.schema.TapConstraintMapping;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapIndexEx;
import io.tapdata.entity.schema.TapIndexField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.schema.type.TapType;
import io.tapdata.entity.schema.value.TapRawValue;
import io.tapdata.entity.schema.value.TapValue;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.entity.utils.cache.KVMap;
import io.tapdata.it.asserts.RecordAssert;
import io.tapdata.it.asserts.TableAssert;
import io.tapdata.it.asserts.TapValueAssert;
import io.tapdata.it.generator.RandomDataFactory;
import io.tapdata.it.mapping.TapTypeResolver;
import io.tapdata.it.mapping.TapValueClassResolver;
import io.tapdata.it.schema.TestDataType;
import io.tapdata.it.schema.TestFieldSpec;
import io.tapdata.it.schema.TestTableSpec;
import io.tapdata.it.support.EngineCodecs;
import io.tapdata.it.support.TestStateMap;
import io.tapdata.it.support.TestTableMap;
import io.tapdata.it.verifier.ConnectorVerifier;
import io.tapdata.it.verifier.VerifierFactory;
import io.tapdata.pdk.apis.annotations.TapConnectorClass;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.consumer.StreamReadOneByOneConsumer;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import io.tapdata.pdk.apis.context.TapConnectorContext;
import io.tapdata.pdk.apis.spec.TapNodeSpecification;
import io.tapdata.pdk.apis.entity.CommandResult;
import io.tapdata.pdk.apis.entity.ConnectionOptions;
import io.tapdata.pdk.apis.entity.ConnectorCapabilities;
import io.tapdata.pdk.apis.entity.ExecuteResult;
import io.tapdata.pdk.apis.entity.FilterResult;
import io.tapdata.pdk.apis.entity.QueryOperator;
import io.tapdata.pdk.apis.entity.TapAdvanceFilter;
import io.tapdata.pdk.apis.entity.TapExecuteCommand;
import io.tapdata.pdk.apis.entity.TapFilter;
import io.tapdata.pdk.apis.partition.splitter.TypeSplitterMap;
import io.tapdata.pdk.apis.entity.TestItem;
import io.tapdata.pdk.apis.entity.message.CommandInfo;
import io.tapdata.pdk.apis.functions.ConnectorFunctions;
import io.tapdata.pdk.apis.functions.PDKMethod;
import io.tapdata.pdk.apis.functions.connection.CheckTableNameResult;
import io.tapdata.pdk.apis.functions.connection.CharsetResult;
import io.tapdata.pdk.apis.functions.connection.ConnectionCheckItem;
import io.tapdata.pdk.apis.functions.connection.TableInfo;
import io.tapdata.pdk.apis.functions.connection.vo.Website;
import io.tapdata.pdk.apis.functions.connector.common.QueryHashByAdvanceFilterFunction;
import io.tapdata.pdk.apis.functions.connector.common.vo.TapHashResult;
import io.tapdata.pdk.apis.functions.connector.common.vo.TapPartitionResult;
import io.tapdata.pdk.apis.functions.connector.source.BatchCountFunction;
import io.tapdata.pdk.apis.functions.connector.source.BatchReadFunction;
import io.tapdata.pdk.apis.functions.connector.source.CountRawCommandFunction;
import io.tapdata.pdk.apis.functions.connector.source.ExecuteCommandFunction;
import io.tapdata.pdk.apis.functions.connector.source.GetCurrentTimestampFunction;
import io.tapdata.pdk.apis.functions.connector.source.GetReadPartitionsFunction;
import io.tapdata.pdk.apis.functions.connector.source.ConnectionConfigWithTables;
import io.tapdata.pdk.apis.functions.connector.source.CountByPartitionFilterFunction;
import io.tapdata.pdk.apis.functions.connector.source.GetReadPartitionOptions;
import io.tapdata.pdk.apis.functions.connector.source.GetStreamOffsetFunction;
import io.tapdata.pdk.apis.functions.connector.source.QueryFieldMinMaxValueFunction;
import io.tapdata.pdk.apis.functions.connector.source.QueryPartitionTablesByParentName;
import io.tapdata.pdk.apis.functions.connector.source.RunRawCommandFunction;
import io.tapdata.pdk.apis.functions.connector.source.StreamReadFunction;
import io.tapdata.pdk.apis.functions.connector.source.StreamReadMultiConnectionFunction;
import io.tapdata.pdk.apis.functions.connector.source.StreamReadOneByOneFunction;
import io.tapdata.pdk.apis.functions.connector.source.TimestampToStreamOffsetFunction;
import io.tapdata.pdk.apis.functions.connector.target.AfterInitialSyncFunction;
import io.tapdata.pdk.apis.functions.connector.target.AlterDatabaseTimeZoneFunction;
import io.tapdata.pdk.apis.functions.connector.target.AlterFieldAttributesFunction;
import io.tapdata.pdk.apis.functions.connector.target.AlterFieldNameFunction;
import io.tapdata.pdk.apis.functions.connector.target.AlterTableCharsetFunction;
import io.tapdata.pdk.apis.functions.connector.target.ClearTableFunction;
import io.tapdata.pdk.apis.functions.connector.target.ControlFunction;
import io.tapdata.pdk.apis.functions.connector.target.CreateConstraintFunction;
import io.tapdata.pdk.apis.functions.connector.target.CreateIndexFunction;
import io.tapdata.pdk.apis.functions.connector.target.CreatePartitionTableFunction;
import io.tapdata.pdk.apis.functions.connector.target.CreateTableOptions;
import io.tapdata.pdk.apis.functions.connector.target.CreateTableV2Function;
import io.tapdata.pdk.apis.functions.connector.target.DeleteIndexFunction;
import io.tapdata.pdk.apis.functions.connector.target.DropConstraintFunction;
import io.tapdata.pdk.apis.functions.connector.target.DropFieldFunction;
import io.tapdata.pdk.apis.functions.connector.target.DropPartitionTableFunction;
import io.tapdata.pdk.apis.functions.connector.target.DropTableFunction;
import io.tapdata.pdk.apis.functions.connector.target.ExportEventSqlFunction;
import io.tapdata.pdk.apis.functions.connector.target.FlushOffsetFunction;
import io.tapdata.pdk.apis.functions.connector.target.NewFieldFunction;
import io.tapdata.pdk.apis.functions.connector.target.QueryByAdvanceFilterFunction;
import io.tapdata.pdk.apis.functions.connector.target.QueryByFilterFunction;
import io.tapdata.pdk.apis.functions.connector.target.QueryConstraintsFunction;
import io.tapdata.pdk.apis.functions.connector.target.QueryIndexesFunction;
import io.tapdata.pdk.apis.functions.connector.target.TransactionBeginFunction;
import io.tapdata.pdk.apis.functions.connector.target.TransactionCommitFunction;
import io.tapdata.pdk.apis.functions.connector.target.TransactionRollbackFunction;
import io.tapdata.pdk.apis.functions.connector.target.WriteRecordFunction;
import io.tapdata.pdk.apis.functions.connection.CheckTableNameFunction;
import io.tapdata.pdk.apis.functions.connection.ConnectionCheckFunction;
import io.tapdata.pdk.apis.functions.connection.ConnectorWebsiteFunction;
import io.tapdata.pdk.apis.functions.connection.ErrorHandleFunction;
import io.tapdata.pdk.apis.functions.connection.ExecuteCommandV2Function;
import io.tapdata.pdk.apis.functions.connection.GetCharsetsFunction;
import io.tapdata.pdk.apis.functions.connection.GetTableInfoFunction;
import io.tapdata.pdk.apis.functions.connection.GetTableNamesFunction;
import io.tapdata.pdk.apis.functions.connection.RetryOptions;
import io.tapdata.pdk.apis.functions.connection.TableWebsiteFunction;
import io.tapdata.pdk.apis.functions.connection.CommandCallbackFunction;
import io.tapdata.pdk.apis.partition.FieldMinMaxValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 连接器通用集成测试基类。
 * <p>
 * 子类继承本类并实现 {@link #createContext()}（提供初始化好的 Connector、上下文、能力注册表与连接配置），
 * 即可自动运行本类中全部通用集成测试用例。测试方法通过 {@link ConnectorFunctions} 动态检测被测连接器
 * 注册的能力，对不支持的能力使用 {@code assumeTrue} 自动跳过。
 * <p>
 * 原则 3（声明式能力）：被测 connector 通过 {@link #requiredCapabilities()} 声明
 * 自己必须实现的接口（对外提供的能力清单，默认 = 当前已注册的全部接口实现），由框架级校验用例自动验证：
 * <ul>
 *   <li>声明的必实现能力 connector 未实现 → 测试失败（connector 不遗漏必实现接口）</li>
 *   <li>connector 已实现的能力（含未声明已实现）无任何用例覆盖 → 测试失败（实现功能必须被测到）</li>
 * </ul>
 * 通用用例上的 {@link UnderTest} 仅声明“本用例测哪个能力”，供覆盖校验汇总与旁路验证要求判定，
 * 不再承担必实现声明职责（必实现清单由子类 {@link #requiredCapabilities()} 声明）。
 * <p>
 * 生命周期与引擎一致：{@code init → 能力调用 → releaseExternal → stop}。
 * 测试表名随机生成、测试数据随机生成（{@link RandomDataFactory}），用例间相互隔离。
 * <p>
 * 依赖数据库默认经 DBForge 按需供应：子类在 {@link #createContext()} 中调用
 * {@link #readConnectionConfig(io.tapdata.dbforge.sdk.model.DbType, String)}，
 * 设 {@code DBF_IT_ENDPOINT} 即直连租约 {@code external_host:nodePort}（公网外加
 * {@code DBF_IT_HOST_MAPPING=true}）；不设则回退静态 {@code *-connection.json}，详见
 * {@link io.tapdata.it.config.DbForgeProvisioner}。
 */
public abstract class ConnectorIT {

    protected ConnectorTestContext context;
    protected TestTableSpec spec;
    protected TapTypeResolver typeResolver;
    /** 旁路验证器：直连对端数据源（不经过 connector read 能力），数据类用例的验证入口 */
    protected ConnectorVerifier verifier;
    /** 当前用例 @UnderTest 声明集合（requiresVerifier 旁路强校验依据）；必实现能力判定由 requiredCapabilities() 承担 */
    protected UnderTest[] currentUnderTests;
    /** 当前用例显示名（失败信息定位） */
    protected String currentTestName;
    /** 引擎编解码过滤器管理器（复用 connector codecRegistry，模拟引擎 Connector 边界 wrap/unwrap 语义） */
    protected TapCodecsFilterManager engineCodecsFilterManager;

    // ===================== 子类扩展点 =====================

    /**
     * 子类提供：初始化好的 Connector、NodeContext、能力注册表（registerCapabilities 产物）、连接配置。
     */
    protected abstract ConnectorTestContext createContext() throws Throwable;

    /**
     * 原则 3（声明式能力）：子类声明本 connector 必须实现的接口（对外提供的全部能力）。
     * <p>
     * 不同 connector 按角色（源/目标/源+目标）与业务需求实现 ConnectorFunctions 中不同的接口，
     * 因此必实现清单由被测试 connector 自己声明，而不是在通用集成用例上统一声明。框架据此自动校验：
     * <ul>
     *   <li>声明的必实现能力未实现 → 测试失败（connector 不遗漏必实现接口）</li>
     *   <li>已实现能力（含未声明已实现）无用例覆盖 → 测试失败（实现功能必须被测到）</li>
     * </ul>
     * 默认实现为当前 connector 实例已注册的全部接口实现（{@link #implementedCapabilities()}）：
     * 未显式覆写时，所有已实现能力都被视为必实现，必须有用例覆盖。
     * 建议按角色组合 {@link #SOURCE_ROLE_CAPABILITIES} / {@link #TARGET_ROLE_CAPABILITIES}，
     * 再追加本 connector 特有能力（能力名与 {@link #require(Supplier, String)} capability 参数一致，
     * 如 "writeRecord" / "batchRead"）。connectionTest / discoverSchema 为 TapConnectorNode
     * 接口能力（所有 connector 恒实现且恒有用例），可不必重复声明；
     * 也可在 {@link #implementedCapabilities()} 基础上剔除角色外能力来收窄必实现承诺。
     */
    protected Set<String> requiredCapabilities() {
        return implementedCapabilities();
    }

    /**
     * 原则 3 源角色（仅作数据源）必实现能力：读取侧。
     * 与目标角色、连接侧公共能力组合后返回 {@link #requiredCapabilities()}。
     */
    protected static final Set<String> SOURCE_ROLE_CAPABILITIES = Set.of(
            "discoverSchema", "getTableNames", "batchCount", "batchRead",
            "streamRead", "timestampToStreamOffset", "getStreamOffset", "flushOffset",
            "runRawCommand", "countRawCommand", "getReadPartitions",
            "countByPartitionFilter", "queryFieldMinMaxValue", "getCurrentTimestamp");

    /**
     * 原则 3 目标角色（仅作写入目标）必实现能力：写入侧。
     */
    protected static final Set<String> TARGET_ROLE_CAPABILITIES = Set.of(
            "createTableV2", "writeRecord", "clearTable", "dropTable",
            "newField", "dropField", "alterFieldName", "alterFieldAttributes",
            "createIndex", "deleteIndex", "queryIndexes",
            "createConstraint", "dropConstraint", "queryConstraints", "afterInitialSync",
            "transactionBegin", "transactionCommit", "transactionRollback");

    /** TapConnectorNode 接口能力（非 ConnectorFunctions getter），所有 connector 恒实现且恒有用例 */
    private static final Set<String> NODE_INTERFACE_CAPABILITIES = Set.of("connectionTest", "discoverSchema");

    /**
     * 引擎生命周期内部钩子能力（非业务能力），不参与“已实现必须被用例覆盖”校验：
     * <ul>
     *   <li>releaseExternal：每次用例 tearDown 自动调用（资源释放路径已被实际执行验证）</li>
     *   <li>memoryFetcher / memoryFetcherV2：引擎诊断注册（ConnectorNode 启动时自动读取），无业务用例语义</li>
     * </ul>
     * 子类可覆写追加例外（如引擎内部扩展钩子）。
     */
    protected Set<String> ignoredCapabilities() {
        return new HashSet<>(Arrays.asList("releaseExternal", "memoryFetcher", "memoryFetcherV2"));
    }

    /** 子类可覆写：测试表规格（字段集合/主键/类型），默认覆盖全通用类型（不含可选大对象类型） */
    protected TestTableSpec createTestTableSpec() {
        TestTableSpec spec = TestTableSpec.defaultAllTypesSpec();
        return enableOptionalLargeObjectTypes() ? spec.withOptionalLargeObjectTypes() : spec;
    }

    /**
     * 可选大对象类型（TEXT/BLOB）启用开关：默认关闭。
     * AS400 等不支持 CLOB/BLOB 的数据源保持关闭；支持的数据源覆写为 {@code true} 以覆盖大对象用例。
     */
    protected boolean enableOptionalLargeObjectTypes() {
        return false;
    }

    /**
     * 子类可覆写：方言数据类型解析器。
     * 默认从 Connector 类的 {@link TapConnectorClass} 注解读取 spec.json 文件名，
     * 自动加载 dataTypes 声明（与引擎 wrap 链路同源，无需手工维护方言映射）。
     */
    protected TapTypeResolver createTypeResolver() throws IOException {
        return TapTypeResolver.from(context.getConnector().getClass());
    }

    /** 子类可覆写：单表随机数据行数 */
    protected int defaultRecordCount() {
        return 100;
    }

    protected long streamReadTimeoutSeconds() {
        return 15L;
    }

    protected boolean waitForStreamReadCatchUp() {
        return false;
    }

    protected void prepareStreamReadTable() throws Exception {
    }

    /** 子类可覆写：测试表名前缀 */
    protected String tablePrefix() {
        return "_tap_it_";
    }

    /** 子类可覆写：false 时无建表 DDL（文件/消息类 Connector），B 组用例自动跳过 */
    protected boolean supportsTableDDL() {
        return true;
    }

    /** 子类可覆写：实际测试表名（无 DDL Connector 预建表场景） */
    protected String resolveTestTable() {
        return spec.getTableName();
    }

    /** 子类可覆写：runRawCommand 的查询命令 */
    protected String rawQueryCommand(String tableName) {
        return "select * from " + tableName;
    }

    /** 子类可覆写：countRawCommand 的计数命令 */
    protected String rawCountCommand(String tableName) {
        return "select count(*) from " + tableName;
    }

    /** 子类可覆写：表结构断言（无 schema 概念或类型无法映射时整体定制） */
    protected void assertSchema(TapTable table) {
        // NoSQL/schema-free 适配：允许隐式字段（_id）且不强制业务字段主键标记时走宽松断言
        if (context.isSchemaAllowsExtraFields() || !context.isSchemaPrimaryKeyStrict()) {
            TableAssert.assertFields(table, spec, typeResolver,
                    context.isSchemaAllowsExtraFields(), context.isSchemaPrimaryKeyStrict());
        } else {
            TableAssert.assertFields(table, spec, typeResolver);
        }
    }

    /** 子类可覆写：写入前的数据预处理钩子（构造依赖数据/序列、约束） */
    protected List<Map<String, Object>> beforeWrite(List<Map<String, Object>> rows) {
        return rows;
    }

    /**
     * 子类可覆写：旁路验证器。
     * 默认反射扫描 Connector 实例成员自动发现（JdbcContext → JdbcVerifier，MongoClient → MongoVerifier）；
     * 无法自动发现的数据源（如文件/消息类 Connector）覆写本方法提供专属直连实现。
     */
    protected ConnectorVerifier createVerifier() {
        return VerifierFactory.create(context.getConnector(), context.getConfig());
    }

    /** 旁路验证器访问器：数据类用例统一通过它做对端数据源验证 */
    protected ConnectorVerifier verifier() {
        return verifier;
    }

    /**
     * 子类可覆写：引擎编解码过滤器管理器。
     * 默认复用 connector registerCapabilities 产物的 codecRegistry 组装 TapCodecsFilterManager
     * （与引擎 TaskNodePdkConnector 边界数据流一致）；无 codecRegistry 时返回 null，转换契约用例自动跳过。
     */
    protected TapCodecsFilterManager createEngineCodecsFilterManager() {
        TapCodecsRegistry codecRegistry = context.getCodecRegistry();
        return codecRegistry == null ? null : EngineCodecs.createEngineCodecsFilterManager(codecRegistry);
    }

    /**
     * 子类可覆写：该类型字段 wrap 后是否应被包装为 TapValue。
     * 默认按引擎原生契约（仅 schema 专用类型 DATE/DATETIME/TIME/BINARY/MAP/ARRAY/YEAR 包装，
     * 其余保持原值）；注册了 Number/String 等自定义 ToTapValueCodec 的连接器覆写为 true。
     */
    protected boolean expectsWrap(TestDataType type) {
        return TapValueClassResolver.wrapsByDefault(type);
    }

    /** 子类可覆写：生成值被包装时的期望 TapValue 类集合（默认按引擎原生契约 + 自定义 codec 合法升级推导） */
    protected Set<Class<? extends TapValue<?, ?>>> expectedTapValueClasses(TestDataType type) {
        return TapValueClassResolver.expectedClassesForGenerated(type);
    }

    /** 子类可覆写：读回值被包装时的期望 TapValue 类集合（读回类型差异：时间列可稳定得到 TapDateValue/TapDateTimeValue） */
    protected Set<Class<? extends TapValue<?, ?>>> expectedTapValueClassesForReadBack(TestDataType type) {
        return TapValueClassResolver.expectedClassesForReadBack(type);
    }

    /** 子类可覆写：按 spec.json dataTypes 解析出的 TapType 推导读回值 wrap 后的期望 TapValue 类集合（U10 spec 声明驱动） */
    protected Set<Class<? extends TapValue<?, ?>>> expectedTapValueClassesForTapType(TapType tapType) {
        return TapValueClassResolver.expectedClassesForTapType(tapType);
    }

    /** 子类可覆写：按 spec.json 解析出的 TapType 族判定引擎是否应包装为 TapValue（U10 用，默认按专用 codec 契约） */
    protected boolean wrapsByTapType(TapType tapType) {
        return TapValueClassResolver.wrapsByTapType(tapType);
    }

    /** 子类可覆写：连接器特有的特殊值样本（如 MongoDB ObjectId/Binary/Decimal128），默认无 */
    protected Map<String, Object> specialValueSamples() {
        return Collections.emptyMap();
    }

    /** 子类可关闭：嵌套类型（MAP/ARRAY）转换契约用例（无 map/array 类型或类型映射不支持时返回 false） */
    protected boolean enableNestedTypes() {
        return true;
    }

    /** 边界值样本：0、空串、min/max、最小日期等（U7 用） */
    protected Object boundaryValue(TestDataType type) {
        switch (type) {
            case INT:
                return 0;
            case BIGINT:
                return Long.MAX_VALUE;
            case VARCHAR:
            case TEXT:
                return "";
            case DECIMAL:
                return BigDecimal.ZERO;
            case FLOAT:
                return 0.0F;
            case DOUBLE:
                return 0.0D;
            case BOOLEAN:
                return Boolean.FALSE;
            case DATE:
                return LocalDate.of(1, 1, 1);
            case DATETIME:
            case TIMESTAMP:
                return LocalDateTime.of(1, 1, 1, 0, 0, 0);
            case BLOB:
                return new byte[0];
            default:
                return null;
        }
    }

    /** 连接器输出 JSON 字符串时递归解析回结构化（unwrap 产物形态兼容）。
     * 部分连接器（如 MySQL 的 TapMapValue→toJson codec）unwrap 时会把嵌套 Map/List 递归字符串化，
     * 因此需对解析结果再做递归解析，恢复嵌套结构后做语义等价比较。 */
    @SuppressWarnings("unchecked")
    private static Object parseJsonIfNeeded(Object value) {
        if (value instanceof String) {
            try {
                Object parsed = new ObjectMapper().readValue((String) value, new TypeReference<Object>() {
                });
                return parseJsonIfNeeded(parsed);
            } catch (IOException e) {
                return value; // 非 JSON 文本，保持原样
            }
        }
        if (value instanceof Map) {
            Map<Object, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                normalized.put(entry.getKey(), parseJsonIfNeeded(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Collection) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                normalized.add(parseJsonIfNeeded(item));
            }
            return normalized;
        }
        return value;
    }

    // ===================== 生命周期 =====================

    @BeforeEach
    void setUp(TestInfo testInfo) throws Throwable {
        log(context, "[IT] ===== TEST START: {} =====", testInfo.getDisplayName());
        log(context, "[IT] heap used before init: {} MB", heapUsedMb());
        long start = System.currentTimeMillis();
        // 读取当前测试方法的 @UnderTest 声明集合（被测 function + 是否依赖旁路验证器，支持重复标注）
        currentTestName = testInfo.getDisplayName();
        currentUnderTests = testInfo.getTestMethod().map(m -> m.getAnnotationsByType(UnderTest.class)).orElse(new UnderTest[0]);
        context = createContext();
        spec = createTestTableSpec();
        typeResolver = createTypeResolver();
        // 补齐上下文运行时要素：stateMap / connectorCapabilities / tableMap / flushOffsetCallback
        prepareContext(context);
        // 引擎编解码过滤器：复用 connector registerCapabilities 产物的 codecRegistry（与 TaskNodePdkConnector 边界一致）
        engineCodecsFilterManager = createEngineCodecsFilterManager();
        // 生命周期：init（Connector 建立真实连接）→ 能力调用 → stop
        context.getConnector().init(context.connectionContextOrNode());
        log(context, "[IT] init done in {} ms, heap used: {} MB, test table: {}",
                elapsed(start), heapUsedMb(), spec.getTableName());
        // 旁路验证器自动装配：init 之后成员（连接池/MongoClient）才可用；返回 null 时由 requiresVerifier 用例兜底校验
        verifier = createVerifier();
        context.setVerifier(verifier);
        log(context, "[IT] verifier: {}", verifier == null ? "none (auto-discovery failed)" : verifierTypeName(verifier));
        // 原则 1 强约束：数据类用例声明 requiresVerifier 但无验证器可用 → 直接失败（禁止自洽验证）
        boolean requiresVerifier = Arrays.stream(currentUnderTests).anyMatch(UnderTest::requiresVerifier);
        if (requiresVerifier && verifier == null) {
            fail("@" + UnderTest.class.getSimpleName() + "(requiresVerifier=true) on " + currentTestName
                    + " but no verifier available; subclass must override createVerifier() to provide bypass verification");
        }
    }

    @AfterEach
    void tearDown(TestInfo testInfo) {
        log(context, "[IT] ----- TEST END: {} -----", testInfo.getDisplayName());
        if (context == null) {
            return;
        }
        long start = System.currentTimeMillis();
        // 兜底清理本次用例残留表（即使断言失败也执行；旁路 drop 不经过 connector）
        dropResidualTables();
        // 旁路验证器清理（连接池/MongoClient 归属 connector，close 仅释放旁路侧资源）
        if (verifier != null) {
            try {
                verifier.close();
            } catch (Throwable ignored) {
            }
        }
        try {
            // releaseExternal（若注册）→ stop
            if (context.getConnectorFunctions().getReleaseExternalFunction() != null) {
                context.getConnectorFunctions().getReleaseExternalFunction().release(context.getNodeContext());
            }
            context.getConnector().stop(context.connectionContextOrNode());
            log(context, "[IT] connector stop done in {} ms, heap used: {} MB", elapsed(start), heapUsedMb());
        } catch (Throwable t) {
            log(context, "[IT] tearDown stop failed: {} ({} ms)", t.getMessage(), elapsed(start));
        }
    }

    /** 补齐 TapConnectorContext 运行时要素，避免 Connector 内部 NPE（与引擎 PdkNode 一致） */
    protected void prepareContext(ConnectorTestContext ctx) {
        TapConnectorContext nodeContext = ctx.getNodeContext();
        if (nodeContext.getStateMap() == null) {
            nodeContext.setStateMap(new TestStateMap());
        }
        if (nodeContext.getConnectorCapabilities() == null) {
            ConnectorCapabilities capabilities = ConnectorCapabilities.create();
            // DML 策略：写入全部走 just_insert / ignore_on_nonexists，规避部分 Connector 默认"存在即更新"策略对 delete 用例的干扰
            capabilities.alternative(ConnectionOptions.DML_INSERT_POLICY, ConnectionOptions.DML_INSERT_POLICY_JUST_INSERT);
            capabilities.alternative(ConnectionOptions.DML_UPDATE_POLICY, ConnectionOptions.DML_UPDATE_POLICY_IGNORE_ON_NON_EXISTS);
            capabilities.alternative(ConnectionOptions.DML_DELETE_POLICY, ConnectionOptions.DML_DELETE_POLICY_IGNORE_ON_NON_EXISTS);
            nodeContext.setConnectorCapabilities(capabilities);
        }
        if (nodeContext.getTableMap() == null) {
            nodeContext.setTableMap(new TestTableMap());
        }
        TapConnectionContext connectionContext = ctx.getConnectionContext();
        // flushOffsetCallback 是 pdk-api 2.0.8 新增字段，低版本（2.0.5）环境下通过反射跳过，保证框架兼容
        if (connectionContext != null) {
            try {
                Method getter = TapConnectionContext.class.getMethod("getFlushOffsetCallback");
                if (getter.invoke(connectionContext) == null) {
                    Method setter = TapConnectionContext.class.getMethod("setFlushOffsetCallback", Consumer.class);
                    setter.invoke(connectionContext, (Consumer<Object>) offset -> log(ctx, "flushOffset called: {}", offset));
                }
            } catch (ReflectiveOperationException ignored) {
                // 低版本 pdk-api 无该回调字段，跳过
            }
        }
    }

    /**
     * 反射能力检测：部分能力（alterTableTTL / processControl 等）在低版本 pdk-api
     * 中不存在，通过反射调用 ConnectorFunctions 的 getter；
     * 低版本环境自动跳过（assumeTrue），高版本环境正常执行。
     * <p>
     * 原则 3 声明式能力：getter 存在但 connector 未实现，且该能力属于 connector 声明的
     * 必实现集合（{@link #requiredCapabilities()}）→ 断言失败；pdk-api 本身未暴露该能力
     * （版本限制，非 connector 责任）→ 维持跳过。
     */
    protected Object reflectFunction(String getterName, String capability) {
        try {
            Method getter = ConnectorFunctions.class.getMethod(getterName);
            Object fn = getter.invoke(functions());
            if (fn == null) {
                if (requiredCapabilities().contains(capability)) {
                    fail(getClass().getSimpleName() + " declares required capability " + capability
                            + " but connector does not implement it");
                }
                assumeTrue(false, () -> "Connector does not support " + capability + ", skip.");
                return null;
            }
            return fn;
        } catch (NoSuchMethodException e) {
            assumeTrue(false, () -> "pdk-api does not expose " + capability + " (newer pdk-api required), skip.");
            return null;
        } catch (Exception e) {
            assumeTrue(false, () -> "Cannot reflect " + capability + ": " + e.getMessage());
            return null;
        }
    }

    /** 反射调用目标函数的方法（配合 {@link #reflectFunction} 使用） */
    protected Object invokeFunction(Object target, String methodName, Object... args) throws Throwable {
        Method method = target.getClass().getMethod(methodName,
                java.util.Arrays.stream(args).map(Object::getClass).toArray(Class<?>[]::new));
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ===================== 能力检测工具 =====================

    /**
     * 能力检测（原则 3 声明式能力）：getter 返回 null 时——
     * 该 capability 属于 connector 声明的必实现集合（{@link #requiredCapabilities()}）→
     * 断言失败（declared but not implemented）；未声明 → assumeTrue 跳过（connector 未实现）；
     * 已实现 → 返回函数实例（自动测试）。
     */
    protected <F> F require(Supplier<F> getter, String capability) {
        F fn = getter.get();
        if (fn == null) {
            if (requiredCapabilities().contains(capability)) {
                fail(getClass().getSimpleName() + " declares required capability " + capability
                        + " but connector does not implement it");
            }
            log(context, "[IT] capability {} not registered by connector, test skipped", capability);
        }
        assumeTrue(fn != null, () -> "Connector does not support " + capability + ", skip.");
        return fn;
    }

    /**
     * 运行时已实现能力集合（原则 3 校验输入）：反射遍历 ConnectorFunctions（含父类）全部
     * getter，返回非 null 即视为已实现；能力名与 {@link UnderTest#value()} / capability 参数
     * 同构（getter 去 get/Function 前缀后缀 + 首字母小写，如 getWriteRecordFunction → writeRecord）。
     * connectionTest / discoverSchema 为 TapConnectorNode 接口能力（恒实现），静态并入；
     * {@link #ignoredCapabilities()} 中的引擎内部钩子不参与校验。
     */
    protected Set<String> implementedCapabilities() {
        Set<String> implemented = new LinkedHashSet<>(NODE_INTERFACE_CAPABILITIES);
        for (Method getter : ConnectorFunctions.class.getMethods()) {
            String name = getter.getName();
            if (name.length() <= 3 || !name.startsWith("get") || !name.endsWith("Function")
                    || getter.getParameterCount() != 0) {
                continue;
            }
            Object fn;
            try {
                fn = getter.invoke(functions());
            } catch (ReflectiveOperationException e) {
                continue;
            }
            if (fn != null) {
                String capability = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                capability = capability.substring(0, capability.length() - "Function".length());
                if (!ignoredCapabilities().contains(capability)) {
                    implemented.add(capability);
                }
            }
        }
        return implemented;
    }

    /**
     * 全部测试用例声明被测的能力集合（原则 3 校验输入）：扫描本类层级（含子类新增/覆写用例）
     * 所有 {@link Test} 方法的 {@link UnderTest#value()}。
     */
    protected Set<String> coveredCapabilities() {
        Set<String> covered = new LinkedHashSet<>();
        Class<?> clazz = getClass();
        while (clazz != null && clazz != Object.class) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class)) {
                    for (UnderTest underTest : m.getAnnotationsByType(UnderTest.class)) {
                        covered.add(underTest.value());
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return covered;
    }

    /** 格式化异常为摘要 + 完整堆栈（供断言失败信息定位 reader 线程等异步调用内部错误） */
    private static String describeThrowable(Throwable t) {
        if (t == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder(t.toString());
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\n\tat ").append(e);
        }
        return sb.toString();
    }

    // ===================== 工具方法 =====================

    protected ConnectorFunctions functions() {
        return context.getConnectorFunctions();
    }

    protected TapConnectorContext nodeContext() {
        return context.getNodeContext();
    }

    protected TapConnectionContext connectionContext() {
        return context.connectionContextOrNode();
    }

    /** 按 spec 构建 TapTable（tapType/主键/主键位置显式设置，不依赖 TableFieldTypesGenerator） */
    protected TapTable buildTapTable() {
        TapTable table = TapSimplify.table(spec.getTableName());
        int pos = 0;
        for (TestFieldSpec fieldSpec : spec.getFields()) {
            TapField field = TapSimplify.field(fieldSpec.getName(), fieldSpec.getDataType());
            field.tapType(tapTypeOf(fieldSpec.getTestDataType()));
            if (fieldSpec.isPrimaryKey()) {
                // 主键 pos 从 1 开始：pdk-api 2.0.5 的 primaryKeyPos(pos) 会忽略 <=0 的 pos（2.0.8 才允许 0）
                field.isPrimaryKey(true).primaryKeyPos(pos + 1);
            }
            table.add(field);
            pos++;
        }
        return table;
    }

    /** 将已构建的 TapTable 注册到 context tableMap（DDL 操作依赖 tableMap 查字段元数据） */
    protected void registerTable(TapTable table) {
        if (context.getNodeContext().getTableMap() instanceof KVMap) {
            ((KVMap<TapTable>) context.getNodeContext().getTableMap()).put(table.getId(), table);
        }
    }

    private TapType tapTypeOf(TestDataType testDataType) {
        switch (testDataType) {
            case INT:
                return TapSimplify.tapNumber().bit(32);
            case BIGINT:
                return TapSimplify.tapNumber().bit(64);
            case DECIMAL:
                return TapSimplify.tapNumber().fixed(true).precision(18).scale(4);
            case FLOAT:
                return TapSimplify.tapNumber().bit(32);
            case DOUBLE:
                return TapSimplify.tapNumber().bit(64);
            case VARCHAR:
            case TEXT:
                return TapSimplify.tapString();
            case BLOB:
                return TapSimplify.tapBinary();
            case BOOLEAN:
                return TapSimplify.tapBoolean();
            case DATE:
                return TapSimplify.tapDate();
            case DATETIME:
            case TIMESTAMP:
                return TapSimplify.tapDateTime();
            default:
                return TapSimplify.tapString();
        }
    }

    /** 建表：一律旁路验证器直连建表（准备不经过 connector createTableV2）；无验证器时直接失败（禁止降级到 connector 自身能力，避免建表动作与被测能力自洽） */
    protected void createTableIfNeeded() throws Throwable {
        long start = System.currentTimeMillis();
        if (verifier() == null) {
            // 文件/消息类 Connector（supportsTableDDL=false）无表概念，保留跳过语义；其余一律禁止降级
            if (!supportsTableDDL()) {
                assumeTrue(false, "no bypass verifier and no table DDL support, skip.");
                return;
            }
            fail("createTableIfNeeded requires a bypass verifier on " + currentTestName
                    + "; subclass must override createVerifier() to provide bypass table creation");
        }
        verifier().createTable(spec.getTableName(), spec.getFields());
        log(context, "[IT] bypass createTable done in {} ms, table: {}", elapsed(start), spec.getTableName());
    }

    /** 旁路写入 N 行并 count 确认（不经过 connector writeRecord；事实来源 = 对端库） */
    protected List<Map<String, Object>> bypassInsert(List<Map<String, Object>> rows) throws Exception {
        long start = System.currentTimeMillis();
        verifier().insert(spec.getTableName(), rows);
        assertEquals(rows.size(), verifyCount(), "bypass count should confirm inserted rows");
        log(context, "[IT] bypass insert {} rows done in {} ms, table: {}", rows.size(), elapsed(start), spec.getTableName());
        return rows;
    }

    /** 兜底删除残留表：优先旁路直连 drop（不经过 connector dropTable）；无验证器时退化为 connector dropTable */
    protected void dropResidualTables() {
        if (context == null || spec == null) {
            return;
        }
        long start = System.currentTimeMillis();
        if (verifier() != null) {
            try {
                verifier().dropTable(spec.getTableName());
                log(context, "[IT] dropResidualTables (bypass) done in {} ms, table: {}", elapsed(start), spec.getTableName());
            } catch (Throwable t) {
                log(context, "[IT] dropResidualTables (bypass) ignored failure: {} ({} ms)", t.getMessage(), elapsed(start));
            }
            // 兜底清理外键用例辅助父表（_tap_it_fkp_*）：用例准备/断言异常时 finally 可能未覆盖，
            // tearDown 按前缀批量清理，避免外键父表残留污染测试库（dropTable 只感知子表）
            try {
                verifier().dropTablesByPrefix(FOREIGN_KEY_PARENT_PREFIX);
            } catch (Throwable ignored) {
            }
            return;
        }
        DropTableFunction dropTable = functions().getDropTableFunction();
        if (dropTable != null) {
            try {
                dropTable.dropTable(nodeContext(), TapSimplify.dropTableEvent(spec.getTableName()));
                log(context, "[IT] dropResidualTables done in {} ms, table: {}", elapsed(start), spec.getTableName());
            } catch (Throwable t) {
                log(context, "[IT] dropResidualTables ignored failure: {} ({} ms)", t.getMessage(), elapsed(start));
            }
        }
    }

    /** discoverSchema 取回测试表（TapConnectorNode 接口能力，直接调用） */
    protected TapTable discoverTable() throws Throwable {
        long start = System.currentTimeMillis();
        List<TapTable> tables = new ArrayList<>();
        context.getConnector().discoverSchema(connectionContext(), List.of(resolveTestTable()), 1, tables::addAll);
        for (TapTable t : tables) {
            if (spec.getTableName().equals(t.getName())) {
                log(context, "[IT] discoverSchema done in {} ms, table: {}, fields: {}{}",
                        elapsed(start), t.getName(), t.getNameFieldMap() == null ? 0 : t.getNameFieldMap().size(),
                        nullTapTypeFields(t));
                return t;
            }
        }
        if (tables.isEmpty()) {
            log(context, "[IT] discoverSchema returned 0 tables in {} ms", elapsed(start));
        } else {
            log(context, "[IT] discoverSchema returned {} tables in {} ms, but {} not found, first: {}",
                    tables.size(), elapsed(start), spec.getTableName(), tables.get(0).getName());
        }
        return tables.isEmpty() ? null : tables.get(0);
    }

    /** getTableNames 读取表名清单 */
    protected List<String> tableNames() throws Throwable {
        GetTableNamesFunction tableNames = require(functions()::getGetTableNamesFunction, "getTableNames");
        List<String> names = new ArrayList<>();
        tableNames.tableNames(connectionContext(), 1000, names::addAll);
        return names;
    }

    /** 生成 N 行期望数据（主键 1..N，可预测） */
    protected List<Map<String, Object>> generateRows(int count) {
        return RandomDataFactory.generateRows(spec, count);
    }

    /** 建表 + 生成并旁路直连写入 N 行数据（不经过 connector writeRecord），返回期望数据（写入前经过 beforeWrite 钩子） */
    protected List<Map<String, Object>> prepareData(int count) throws Throwable {
        long start = System.currentTimeMillis();
        createTableIfNeeded();
        List<Map<String, Object>> rows = beforeWrite(generateRows(count));
        // 旁路直连写入并 count 确认（事实来源 = 对端库；数据准备不经过 connector 任何能力）
        bypassInsert(rows);
        log(context, "[IT] prepareData done: {} rows bypass inserted in {} ms", rows.size(), elapsed(start));
        return rows;
    }

    /** 批量写入 insert 事件（普通值 → codec 转换 → TapInsertRecordEvent，与引擎 PdkTargetNode 行为一致） */
    protected long writeInsertEvents(List<Map<String, Object>> rows, TapTable table) throws Throwable {
        long start = System.currentTimeMillis();
        WriteRecordFunction writeRecord = require(functions()::getWriteRecordFunction, "writeRecord");
        List<TapRecordEvent> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 与引擎一致：connector 收到的是 transformFromTapValueMap 后的普通值，无需再包 TapValue
            events.add(TapSimplify.insertRecordEvent(row, table.getId()));
        }
        long[] inserted = {0};
        writeRecord.writeRecord(nodeContext(), events, table, result -> inserted[0] += result.getInsertedCount());
        log(context, "[IT] writeInsertEvents: {} events, {} inserted in {} ms", rows.size(), inserted[0], elapsed(start));
        return inserted[0];
    }

    /**
     * 引擎侧 wrap：普通值 Map → TapValue Map（transformToTapValueMap，复用 connector codecRegistry）。
     * 引擎契约：仅 schema 专用类型（DATE/DATETIME/TIME/BINARY/MAP/ARRAY/YEAR）包装为 TapValue，
     * 其余类型（NUMBER/STRING/BOOLEAN）保持原值；包装失败的类型回落 TapRawValue 兜底。
     * 注意：wrap 会原地递归处理嵌套的 Map/List（AllLayerMapIterator），需要保留原始嵌套值时先做浅拷贝。
     */
    protected Map<String, Object> wrapRow(Map<String, Object> row, TapTable table) {
        TapTable t = table != null ? table : buildTapTable();
        Map<String, Object> working = new LinkedHashMap<>(row);
        engineCodecsFilterManager.transformToTapValueMap(working, t.getNameFieldMap());
        return working;
    }

    /**
     * 引擎侧 unwrap：TapValue Map → 普通值 Map（transformFromTapValueMap，复用 connector codecRegistry）。
     * 引擎契约：custom FromTapValueCodec 优先、默认 codec 兜底，输入 map 原地解包；
     * originValue 非 null 的 TapValue 同时保留在返回值中（U4 单独断言），本方法返回解包后的普通值 map。
     */
    protected Map<String, Object> unwrapRow(Map<String, Object> tapValueRow) {
        Map<String, Object> working = new HashMap<>(tapValueRow);
        engineCodecsFilterManager.transformFromTapValueMap(working);
        return working;
    }

    /** 引擎写路径：wrap → unwrap（引擎边界解包）→ TapInsertRecordEvent → writeRecord（模拟引擎 PdkTargetNode 数据流） */
    protected long writeInsertEventsViaEngineCodec(List<Map<String, Object>> rows, TapTable table) throws Throwable {
        long start = System.currentTimeMillis();
        WriteRecordFunction writeRecord = require(functions()::getWriteRecordFunction, "writeRecord");
        TapTable t = table != null ? table : buildTapTable();
        List<TapRecordEvent> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 上游节点产出 TapValue map（wrap）→ 引擎 Connector 边界解包为普通值 → connector.writeRecord
            Map<String, Object> tapValueRow = wrapRow(row, t);
            Map<String, Object> plainRow = unwrapRow(tapValueRow);
            events.add(TapSimplify.insertRecordEvent(plainRow, t.getId()));
        }
        long[] inserted = {0};
        writeRecord.writeRecord(nodeContext(), events, t, result -> inserted[0] += result.getInsertedCount());
        log(context, "[IT] writeInsertEventsViaEngineCodec: {} events, {} inserted in {} ms", rows.size(), inserted[0], elapsed(start));
        return inserted[0];
    }

    /** 批量写入 update 事件（before 仅含主键定位，after 为完整新值） */
    protected long writeUpdateEvents(List<Map<String, Object>> rows, TapTable table) throws Throwable {
        long start = System.currentTimeMillis();
        WriteRecordFunction writeRecord = require(functions()::getWriteRecordFunction, "writeRecord");
        String pkName = primaryKeyName();
        List<TapRecordEvent> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> before = new HashMap<>();
            before.put(pkName, row.get(pkName));
            Map<String, Object> after = new HashMap<>(row);
            events.add(new TapUpdateRecordEvent().init().table(table.getId()).before(before).after(after));
        }
        long[] modified = {0};
        writeRecord.writeRecord(nodeContext(), events, table, result -> modified[0] += result.getModifiedCount());
        log(context, "[IT] writeUpdateEvents: {} events, {} modified in {} ms", rows.size(), modified[0], elapsed(start));
        return modified[0];
    }

    /** 批量写入 delete 事件（before 含完整行，供按主键定位） */
    protected long writeDeleteEvents(List<Map<String, Object>> rows, TapTable table) throws Throwable {
        long start = System.currentTimeMillis();
        WriteRecordFunction writeRecord = require(functions()::getWriteRecordFunction, "writeRecord");
        List<TapRecordEvent> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> before = new HashMap<>(row);
            events.add(new TapDeleteRecordEvent().init().table(table.getId()).before(before));
        }
        long[] removed = {0};
        writeRecord.writeRecord(nodeContext(), events, table, result -> removed[0] += result.getRemovedCount());
        log(context, "[IT] writeDeleteEvents: {} events, {} removed in {} ms", rows.size(), removed[0], elapsed(start));
        return removed[0];
    }

    /** batchRead 全量读回（BatchReadFunction 为同步方法，一次性返回所有数据） */
    protected List<Map<String, Object>> batchReadAll(TapTable table) throws Throwable {
        long start = System.currentTimeMillis();
        BatchReadFunction batchRead = require(functions()::getBatchReadFunction, "batchRead");
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            batchRead.batchRead(nodeContext(), table, null, 100, (events, newOffset) -> {
                if (events != null && !events.isEmpty()) {
                    for (TapEvent e : events) {
                        if (e instanceof TapInsertRecordEvent) {
                            rows.add(((TapInsertRecordEvent) e).getAfter());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            // 部分 Connector 以异常结束批读；已读到数据则视为正常结束，否则视为失败
            if (rows.isEmpty()) {
                throw t;
            }
            log(context, "[IT] batchRead stopped by exception after {} rows: {}", rows.size(), t.getMessage());
        }
        log(context, "[IT] batchReadAll done: {} rows read back in {} ms", rows.size(), elapsed(start));
        return rows;
    }

    protected String primaryKeyName() {
        List<TestFieldSpec> pkFields = spec.primaryKeyFields();
        assertFalse(pkFields.isEmpty(), "test table spec must define a primary key");
        return pkFields.get(0).getName();
    }

    /** 逐行逐列断言：期望数据与读回数据一致（按主键索引，RecordAssert 分类型比较） */
    protected void assertRowsConsistent(List<Map<String, Object>> expected, List<Map<String, Object>> actual) {
        log(context, "[IT] assertRowsConsistent: expected {} rows, actual {} rows", expected.size(), actual.size());
        assertEquals(expected.size(), actual.size(),
                "row count mismatch, expected=" + expected.size() + ", actual=" + actual.size());
        String pkName = primaryKeyName();
        Map<String, Map<String, Object>> byPk = new HashMap<>();
        for (Map<String, Object> row : actual) {
            byPk.put(String.valueOf(row.get(pkName)), row);
        }
        for (Map<String, Object> expRow : expected) {
            Map<String, Object> actRow = byPk.get(String.valueOf(expRow.get(pkName)));
            assertNotNull(actRow, "row with pk=" + expRow.get(pkName) + " not found in read back");
            for (TestFieldSpec field : spec.getFields()) {
                RecordAssert.assertEquals(field.getTestDataType(),
                        expRow.get(field.getName()), actRow.get(field.getName()), field.getName());
            }
        }
    }

    // ===================== 旁路验证工具（原则 1：事实来源 = 对端库） =====================

    /** 取行集合的主键值列表（旁路 selectByPk 入参） */
    protected List<Object> pkValues(List<Map<String, Object>> rows) {
        String pkName = primaryKeyName();
        List<Object> values = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            values.add(row.get(pkName));
        }
        return values;
    }

    /** 旁路 count 当前测试表（requiresVerifier 用例专用；verifier 缺失由 setUp 兜底失败） */
    protected long verifyCount() throws Exception {
        long count = verifier().count(spec.getTableName());
        log(context, "[IT] bypass count: {}", count);
        return count;
    }

    /** 旁路按主键查询并断言与期望数据逐字段一致（事实来源 = 对端库，非期望值） */
    protected void verifyRowsByPk(List<Map<String, Object>> expectedRows) throws Exception {
        List<Map<String, Object>> actual = verifier().selectByPk(spec.getTableName(), primaryKeyName(), pkValues(expectedRows));
        assertRowsConsistent(expectedRows, actual);
    }

    // ===================== 连接配置读取工具 =====================

    /**
     * 读取连接配置 JSON（classpath 优先，其次文件系统），键名对齐 Connector spec.json 连接表单字段。
     * <p>
     * 配置加载已委托给 {@link io.tapdata.it.config.ConnectionConfigLoader}，支持：
     * <ol>
     *   <li>本地 JSON 文件（classpath 或文件系统）</li>
     *   <li>外部接口配置（CONNECTOR_IT_CONFIG_URL / -Dconnector.it.config.url）</li>
     *   <li>环境变量 CONNECTOR_IT_&lt;KEY&gt; 或系统属性 -Dconnector.it.&lt;key&gt; 逐项覆盖</li>
     * </ol>
     */
    protected static DataMap readConnectionConfig(String path) throws IOException {
        return io.tapdata.it.config.ConnectionConfigLoader.load(path);
    }

    /**
     * 读取连接配置（dbforge 优先）：默认经 {@link io.tapdata.it.config.DbForgeProvisioner} 向 DBForge
     * 控制面按需申请 {@code dbType} 类型的真实数据库，直接直连租约返回的
     * {@code external_host:nodePort}（回注配置与静态 JSON 同构，Mongo 重建内嵌凭证 uri、
     * AS400 补 journal 字段）；未启用（不设 {@code DBF_IT_ENDPOINT}，场景 4：用本地库）时
     * 回退 {@link #readConnectionConfig(String) 静态 JSON} 链路，默认构建不受影响。
     * <p>
     * 四类运行场景的连接地址适配（详见 {@link io.tapdata.it.config.DbForgeProvisioner}）：
     * <table border="1">
     *   <tr><th>场景</th><th>连接地址来源</th><th>是否转换</th></tr>
     *   <tr><td>#1 CI 自建 Runner（内网）/ #2 开发者在公司内网</td>
     *       <td>直连租约返回的内网 external_host:nodePort</td><td>否（默认）</td></tr>
     *   <tr><td>#3 开发者在公网外</td>
     *       <td>租约内网 host 经映射表转成公网 host，端口不变</td>
     *       <td>是，{@code DBF_IT_HOST_MAPPING} 启用</td></tr>
     *   <tr><td>#4 开发者用本地库</td><td>不设 {@code DBF_IT_ENDPOINT} → 回退 connection.json</td>
     *       <td>不涉及 dbforge</td></tr>
     * </table>
     *
     * @param dbType       本测试依赖的数据库类型（如 {@code DbType.MYSQL}）
     * @param fallbackPath 未启用 dbforge 时的静态 JSON 回退路径（classpath 优先，其次文件系统）
     * @return 连接配置（dbforge 租约回注或静态 JSON + 外部接口 + 逐项覆盖）
     */
    protected static DataMap readConnectionConfig(io.tapdata.dbforge.sdk.model.DbType dbType,
                                                  String fallbackPath) throws IOException {
        if (io.tapdata.it.config.DbForgeProvisioner.enabled()) {
            return io.tapdata.it.config.DbForgeProvisioner.provision(dbType);
        }
        return readConnectionConfig(fallbackPath);
    }

    /**
     * 加载连接器 spec json（classpath 优先，如 mysql-spec.json）：
     * 供 TapConnectorContext 构造使用；specification 的 dataTypesMap 是
     * schema 发现时 tapType 自动填充（TableFieldTypesGenerator.autoFill）的必要输入。
     */
    protected static TapNodeSpecification loadSpecification(String specPath) throws IOException {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(specPath);
        if (in == null) {
            in = new FileInputStream(specPath);
        }
        String json;
        try (InputStream resource = in) {
            json = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode properties = root.path("properties");
        TapNodeSpecification spec = new TapNodeSpecification();
        spec.setName(properties.path("name").asText());
        spec.setRealName(properties.path("realName").asText());
        spec.setId(properties.path("id").asText());
        spec.setVersion(properties.path("version").asText(null));
        spec.setGroup(properties.path("group").asText(null));
        spec.setIcon(properties.path("icon").asText(null));
        JsonNode dataTypes = root.get("dataTypes");
        if (dataTypes != null && dataTypes.isObject()) {
            spec.setDataTypesMap(DefaultExpressionMatchingMap.map(dataTypes.toString()));
        }
        return spec;
    }

    /** 系统属性（-Dconnector.it.xxx）或环境变量（CONNECTOR_IT_XXX）取值 */
    protected static String sysPropOrEnv(String key, String defaultValue) {
        String sysProp = System.getProperty("connector.it." + key);
        if (sysProp != null) {
            return sysProp;
        }
        String env = System.getenv("CONNECTOR_IT_" + key.toUpperCase());
        return env != null ? env : defaultValue;
    }

    protected void log(ConnectorTestContext ctx, String message, Object... params) {
        if (ctx != null && ctx.getLog() != null) {
            ctx.getLog().info(message, params);
        }
    }

    /** 距 start 的耗时（ms） */
    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /** 可读的验证器类型名：匿名内部类 getSimpleName() 返回空串，退化为父类名 */
    private String verifierTypeName(Object verifier) {
        Class<?> clazz = verifier.getClass();
        String simple = clazz.getSimpleName();
        if (simple == null || simple.isEmpty()) {
            Class<?> superclass = clazz.getSuperclass();
            simple = superclass != null ? superclass.getSimpleName() : clazz.getName();
        }
        return simple;
    }

    /** 当前已用堆内存（MB）：用例边界打印，用于跟踪连接/线程资源是否随用例释放 */
    private long heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1048576;
    }

    /** 返回 tapType 为 null 的字段名列表（空串表示全部有 tapType），辅助排查 schema 类型映射问题 */
    private String nullTapTypeFields(TapTable table) {
        if (table == null || table.getNameFieldMap() == null) {
            return ", table/fields is null";
        }
        StringBuilder sb = new StringBuilder();
        for (TapField f : table.getNameFieldMap().values()) {
            if (f.getTapType() == null) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(f.getName());
            }
        }
        return sb.length() > 0 ? ", null tapType fields: " + sb : "";
    }

    // ===================== A. 连接与元数据 =====================

    @Test
    @UnderTest(value = "connectionTest")
    void should_test_connection() throws Throwable {
        List<TestItem> items = new ArrayList<>();
        ConnectionOptions options = context.getConnector().connectionTest(connectionContext(), items::add);
        assertNotNull(options, "connectionTest should return ConnectionOptions");
        // capabilities 为可选信息（pdk-api 2.0.5 中为 List<Capability>，MySQL 不返回），不强制断言
    }

    @Test
    @UnderTest(value = "discoverSchema", requiresVerifier = true)
    void should_discover_schema_of_created_table() throws Throwable {
        createTableIfNeeded();
        if (context.isSchemaDiscoveryRequiresSampleData()) {
            // schema-free 库（如 MongoDB）空集合无字段可推断（仅隐式 _id），先旁路写入采样数据再 discoverSchema
            log(context, "[IT] schema discovery requires sample data, bypass writing {} rows first", 5);
            bypassInsert(beforeWrite(generateRows(5)));
        }
        TapTable table = discoverTable();
        assertNotNull(table, "discoverSchema should return the created table: " + resolveTestTable());
        assertSchema(table);
    }

    @Test
    @UnderTest(value = "getTableNames", requiresVerifier = true)
    void should_find_table_after_create() throws Throwable {
        require(functions()::getCreateTableV2Function, "createTableV2");
        createTableIfNeeded();
        // 旁路确认表真实存在（建表动作锚点，不经过 connector 能力）
        assertTrue(verifier().tableExists(spec.getTableName()), "bypass check: table should exist after create");
        List<String> names = tableNames();
        assertTrue(names.stream().anyMatch(n -> spec.getTableName().equalsIgnoreCase(n)),
                "table " + spec.getTableName() + " should be in table names, got: " + names);
    }

    @Test
    @UnderTest(value = "dropTable", requiresVerifier = true)
    void should_table_not_found_after_drop() throws Throwable {
        DropTableFunction dropTable = require(functions()::getDropTableFunction, "dropTable");
        createTableIfNeeded();
        assertTrue(verifier().tableExists(spec.getTableName()), "bypass check: table should exist before drop");
        dropTable.dropTable(nodeContext(), TapSimplify.dropTableEvent(spec.getTableName()));
        // 旁路 tableExists 验证删除真实生效（不经过 connector getTableNames 能力，避免自洽）
        assertFalse(verifier().tableExists(spec.getTableName()),
                "bypass check: table should not exist after drop");
    }

    @Test
    @UnderTest(value = "getTableInfo", requiresVerifier = true)
    void should_get_table_info() throws Throwable {
        GetTableInfoFunction getTableInfo = require(functions()::getGetTableInfoFunction, "getTableInfo");
        createTableIfNeeded();
        TableInfo tableInfo = getTableInfo.getTableInfo(connectionContext(), spec.getTableName());
        assertNotNull(tableInfo, "getTableInfo should return TableInfo");
        if (tableInfo.getNumOfRows() != null) {
            assertTrue(tableInfo.getNumOfRows() >= 0, "numOfRows should be non-negative");
        }
    }

    @Test
    @UnderTest(value = "checkTableName")
    void should_check_table_name() throws Throwable {
        CheckTableNameFunction checkTableName = require(functions()::getCheckTableNameFunction, "checkTableName");
        CheckTableNameResult result = checkTableName.check(connectionContext(), spec.getTableName());
        assertNotNull(result, "checkTableName should return CheckTableNameResult");
        assertTrue(result.isSupported(), "a valid table name should be supported: " + spec.getTableName());
    }

    @Test
    @UnderTest(value = "getCharsets")
    void should_get_charsets() throws Throwable {
        GetCharsetsFunction getCharsets = require(functions()::getGetCharsetsFunction, "getCharsets");
        CharsetResult result = getCharsets.charsets(connectionContext());
        assertNotNull(result, "getCharsets should return CharsetResult");
        assertNotNull(result.getCharsetMap(), "charset map should not be null");
        assertFalse(result.getCharsetMap().isEmpty(), "charset map should not be empty");
    }

    @Test
    @UnderTest(value = "connectionCheck")
    void should_connection_check() throws Throwable {
        ConnectionCheckFunction connectionCheck = require(functions()::getConnectionCheckFunction, "connectionCheck");
        List<ConnectionCheckItem> items = new ArrayList<>();
        connectionCheck.check(connectionContext(), Collections.emptyList(), items::add);
        assertFalse(items.isEmpty(), "connectionCheck should produce items");
    }

    // ===================== B. DDL（表级） =====================

    @Test
    @UnderTest(value = "createTableV2", requiresVerifier = true)
    void should_create_table_v2() throws Throwable {
        CreateTableV2Function createTableV2 = require(functions()::getCreateTableV2Function, "createTableV2");
        CreateTableOptions options = createTableV2.createTable(nodeContext(), TapSimplify.createTableEvent(buildTapTable()));
        assertNotNull(options, "createTableV2 should return CreateTableOptions");
        assertEquals(Boolean.FALSE, options.getTableExists(), "newly created table should report tableExists=false");
        // 旁路验证建表动作真实生效：表真实存在且为空（不依赖 createTableV2 返回值自证）
        assertTrue(verifier().tableExists(spec.getTableName()), "bypass check: table should exist after createTableV2");
        assertEquals(0, verifyCount(), "newly created table should be empty");
    }

    @Test
    @UnderTest(value = "createTableV2", requiresVerifier = true)
    void should_create_table_v2_when_exists() throws Throwable {
        assumeTrue(context.isCreateTableReportsTableExists(),
                "createTableV2 does not report existing table (NoSQL idempotent create), skip.");
        CreateTableV2Function createTableV2 = require(functions()::getCreateTableV2Function, "createTableV2");
        createTableV2.createTable(nodeContext(), TapSimplify.createTableEvent(buildTapTable()));
        // 幂等：二次创建不抛错且报告已存在
        CreateTableOptions options = createTableV2.createTable(nodeContext(), TapSimplify.createTableEvent(buildTapTable()));
        assertEquals(Boolean.TRUE, options.getTableExists(), "existing table should report tableExists=true");
        // 旁路确认表真实存在（建表动作锚点）
        assertTrue(verifier().tableExists(spec.getTableName()), "bypass check: table should exist");
    }

    @Test
    @UnderTest(value = "clearTable", requiresVerifier = true)
    void should_clear_table() throws Throwable {
        ClearTableFunction clearTable = require(functions()::getClearTableFunction, "clearTable");
        createTableIfNeeded();
        // 数据准备走旁路直连（不经过 connector writeRecord，避免与 clearTable 自洽）
        bypassInsert(beforeWrite(generateRows(defaultRecordCount())));
        clearTable.clearTable(nodeContext(), TapSimplify.clearTableEvent(spec.getTableName()));
        // 旁路 count 验证清空（事实来源 = 对端库）
        assertEquals(0, verifyCount(), "table should be empty after clear");
    }

    @Test
    @UnderTest(value = "dropTable", requiresVerifier = true)
    void should_drop_table() throws Throwable {
        DropTableFunction dropTable = require(functions()::getDropTableFunction, "dropTable");
        createTableIfNeeded();
        assertTrue(verifier().tableExists(spec.getTableName()), "bypass check: table should exist before drop");
        dropTable.dropTable(nodeContext(), TapSimplify.dropTableEvent(spec.getTableName()));
        // 旁路验证删除真实生效（事实来源 = 对端库）
        assertFalse(verifier().tableExists(spec.getTableName()),
                "bypass check: table should not exist after drop");
        // 幂等：重复删除不抛错
        dropTable.dropTable(nodeContext(), TapSimplify.dropTableEvent(spec.getTableName()));
    }

    @Test
    @UnderTest(value = "alterTableCharset", requiresVerifier = true)
    void should_alter_table_charset() throws Throwable {
        AlterTableCharsetFunction alter = require(functions()::getAlterTableCharsetFunction, "alterTableCharset");
        createTableIfNeeded();
        String charset = firstCharset();
        assumeTrue(charset != null, "no charset available from getCharsets, skip.");
        TapAlterTableCharsetEvent event = new TapAlterTableCharsetEvent().charset(charset);
        event.setTableId(spec.getTableName());
        alter.alterTableCharset(nodeContext(), event);
    }

    private String firstCharset() throws Throwable {
        GetCharsetsFunction getCharsets = functions().getGetCharsetsFunction();
        if (getCharsets == null) {
            return null;
        }
        CharsetResult result = getCharsets.charsets(connectionContext());
        for (List<String> charsets : result.getCharsetMap().values()) {
            if (charsets != null && !charsets.isEmpty()) {
                return charsets.get(0);
            }
        }
        return null;
    }

    @Test
    @UnderTest(value = "alterTableTTL", requiresVerifier = true)
    void should_alter_table_ttl() throws Throwable {
        // AlterTableTTLFunction 为 pdk-api 2.0.8 新增，低版本环境自动跳过
        Object alter = reflectFunction("getAlterTableTTLFunction", "alterTableTTL");
        createTableIfNeeded();
        TapAlterTableTTLEvent event = new TapAlterTableTTLEvent().duration(Duration.ofDays(30));
        event.setTableId(spec.getTableName());
        invokeFunction(alter, "alterTableTTL", nodeContext(), event);
    }

    @Test
    @UnderTest(value = "alterDatabaseTimeZone")
    void should_alter_database_timezone() throws Throwable {
        AlterDatabaseTimeZoneFunction alter = require(functions()::getAlterDatabaseTimeZoneFunction, "alterDatabaseTimeZone");
        TapAlterDatabaseTimezoneEvent event = new TapAlterDatabaseTimezoneEvent().timeZone(TimeZone.getTimeZone("UTC"));
        alter.alterDatabaseTimeZone(nodeContext(), event);
    }

    // ===================== C. 数据写入与读取（核心） =====================

    @Test
    @UnderTest(value = "writeRecord", requiresVerifier = true)
    void should_write_insert_records() throws Throwable {
        createTableIfNeeded();
        List<Map<String, Object>> rows = beforeWrite(generateRows(defaultRecordCount()));
        long inserted = writeInsertEvents(rows, buildTapTable());
        assertEquals(defaultRecordCount(), inserted, "inserted count mismatch");
        // 旁路验证落库：count 全量 + select 逐行比对（事实来源 = 对端库）
        assertEquals(rows.size(), verifyCount(), "bypass count should match inserted rows");
        verifyRowsByPk(rows);
    }

    @Test
    @UnderTest(value = "batchCount", requiresVerifier = true)
    void should_batch_count_match() throws Throwable {
        BatchCountFunction batchCount = require(functions()::getBatchCountFunction, "batchCount");
        prepareData(defaultRecordCount());
        // 被测 batchCount 与旁路 count 比对（事实来源 = 对端库）
        assertEquals(verifyCount(), batchCount.count(nodeContext(), buildTapTable()), "batchCount should match bypass count");
    }

    @Test
    @UnderTest(value = "batchRead", requiresVerifier = true)
    void should_batch_read_data_consistent() throws Throwable {
        // writeRecord 仅为数据准备（非被测），旁路 count 确认落库
        prepareData(defaultRecordCount());
        assertEquals(defaultRecordCount(), verifyCount(), "bypass count should confirm prepared rows");
        // 被测 batchRead：结果与旁路 select（事实来源 = 对端库）逐行比对
        List<Map<String, Object>> actual = batchReadAll(buildTapTable());
        List<Map<String, Object>> groundTruth = verifier().selectByPk(spec.getTableName(), primaryKeyName(), pkValues(actual));
        assertRowsConsistent(groundTruth, actual);
    }

    @Test
    @UnderTest(value = "writeRecord", requiresVerifier = true)
    void should_write_update_records() throws Throwable {
        prepareData(defaultRecordCount());
        TapTable table = buildTapTable();
        // 更新前 10 行：修改 c_varchar/c_int/c_decimal 三列
        int updateCount = Math.min(10, defaultRecordCount());
        List<Map<String, Object>> updatedRows = generateRows(updateCount);
        // 旁路取当前行（事实来源 = 对端库），生成数据主键 1..N 可预测，取前 updateCount 行
        List<Map<String, Object>> current = verifier().selectByPk(spec.getTableName(), primaryKeyName(),
                pkValues(updatedRows));
        assertEquals(updateCount, current.size(), "bypass select should return current rows");
        for (int i = 0; i < updateCount; i++) {
            Map<String, Object> row = current.get(i);
            row.put("c_varchar", updatedRows.get(i).get("c_varchar"));
            row.put("c_int", updatedRows.get(i).get("c_int"));
            row.put("c_decimal", updatedRows.get(i).get("c_decimal"));
        }
        long modified = writeUpdateEvents(current, table);
        assertEquals(updateCount, modified, "modified count mismatch");
        // 复核：旁路 select 与更新后的期望一致（事实来源 = 对端库）
        verifyRowsByPk(current);
    }

    @Test
    @UnderTest(value = "writeRecord", requiresVerifier = true)
    void should_write_delete_records() throws Throwable {
        List<Map<String, Object>> expected = prepareData(defaultRecordCount());
        TapTable table = buildTapTable();
        int deleteCount = Math.min(10, defaultRecordCount());
        long removed = writeDeleteEvents(expected.subList(0, deleteCount), table);
        assertEquals(deleteCount, removed, "removed count mismatch");
        // 旁路 count 验证删除生效（事实来源 = 对端库）
        assertEquals(defaultRecordCount() - deleteCount, verifyCount(),
                "bypass count should reflect deleted rows");
    }

    @Test
    @UnderTest(value = "queryByFilter", requiresVerifier = true)
    void should_query_by_filter() throws Throwable {
        QueryByFilterFunction query = require(functions()::getQueryByFilterFunction, "queryByFilter");
        List<Map<String, Object>> expected = prepareData(defaultRecordCount());
        TapTable table = buildTapTable();
        String pkName = primaryKeyName();
        Map<String, Object> target = expected.get(0);
        TapFilter filter = TapFilter.create();
        filter.setMatch(DataMap.create().kv(pkName, target.get(pkName)));
        List<FilterResult> results = new ArrayList<>();
        query.query(nodeContext(), List.of(filter), table, results::addAll);
        assertEquals(1, results.size(), "query by primary key should hit exactly 1 row");
        Map<String, Object> actual = results.get(0).getResult();
        assertNotNull(actual, "filter result should contain data");
        // 旁路 select 取对端库事实，逐字段比对
        List<Map<String, Object>> groundTruth = verifier().selectByPk(spec.getTableName(), pkName, List.of(target.get(pkName)));
        assertEquals(1, groundTruth.size(), "bypass select should hit exactly 1 row");
        for (TestFieldSpec field : spec.getFields()) {
            RecordAssert.assertEquals(field.getTestDataType(),
                    groundTruth.get(0).get(field.getName()), actual.get(field.getName()), field.getName());
        }
    }

    @Test
    @UnderTest(value = "queryByAdvanceFilter", requiresVerifier = true)
    void should_query_by_advance_filter() throws Throwable {
        QueryByAdvanceFilterFunction query = require(functions()::getQueryByAdvanceFilterFunction, "queryByAdvanceFilter");
        List<Map<String, Object>> expected = prepareData(defaultRecordCount());
        TapTable table = buildTapTable();
        // 阈值取 c_int 中位数（生成值可预测），保证过滤结果非空且有意义
        List<Long> cIntValues = new ArrayList<>();
        for (Map<String, Object> row : expected) {
            cIntValues.add(((Number) row.get("c_int")).longValue());
        }
        cIntValues.sort(null);
        long threshold = cIntValues.get(cIntValues.size() / 2);
        TapAdvanceFilter filter = new TapAdvanceFilter().limit(1000).op(QueryOperator.gt("c_int", threshold));
        List<Map<String, Object>> results = new ArrayList<>();
        query.query(nodeContext(), filter, table, r -> results.addAll(r.getResults()));
        // 旁路 select 全部行，按阈值过滤作为事实来源（对端库）
        List<Map<String, Object>> groundTruth = verifier().selectByPk(spec.getTableName(), primaryKeyName(), pkValues(expected));
        long expectedCount = groundTruth.stream()
                .filter(r -> ((Number) r.get("c_int")).longValue() > threshold).count();
        assertEquals(expectedCount, results.size(), "queryByAdvanceFilter result count mismatch");
        for (Map<String, Object> row : results) {
            assertTrue(((Number) row.get("c_int")).longValue() > threshold,
                    "row c_int should be greater than threshold: " + threshold);
        }
    }

    @Test
    @UnderTest(value = "afterInitialSync", requiresVerifier = true)
    void should_after_initial_sync() throws Throwable {
        AfterInitialSyncFunction afterInitialSync = require(functions()::getAfterInitialSyncFunction, "afterInitialSync");
        prepareData(defaultRecordCount());
        afterInitialSync.afterInitialSync(nodeContext(), buildTapTable());
    }

    // ===================== D. 索引与约束 =====================

    private TapIndex testIndex() {
        return new TapIndex().name("idx_c_int").unique(true)
                .indexField(new TapIndexField().name("c_int").fieldAsc(true));
    }

    private TapConstraint testUniqueConstraint() {
        return new TapConstraint("uq_c_int", TapConstraint.ConstraintType.UNIQUE)
                .add(new TapConstraintMapping().foreignKey("c_int").referenceKey("c_int"));
    }

    /** 外键约束：子表 c_bigint → 父表 id（bigint ↔ bigint 类型匹配，MySQL/PostgreSQL/Oracle 通用） */
    private TapConstraint testForeignKeyConstraint(String parentTable) {
        return new TapConstraint("fk_c_bigint", TapConstraint.ConstraintType.FOREIGN_KEY)
                .referencesTable(parentTable)
                .add(new TapConstraintMapping().foreignKey("c_bigint").referenceKey("id"));
    }

    @Test
    @UnderTest(value = "createIndex", requiresVerifier = true)
    void should_create_index() throws Throwable {
        CreateIndexFunction createIndex = require(functions()::getCreateIndexFunction, "createIndex");
        prepareData(defaultRecordCount());
        createIndex.createIndex(nodeContext(), buildTapTable(), TapSimplify.createIndexEvent(spec.getTableName(), List.of(testIndex())));
        // 旁路 listIndexes 验证索引真实创建（事实来源 = 对端库，不依赖 connector queryIndexes）
        assertTrue(verifier().listIndexes(spec.getTableName()).contains("idx_c_int"),
                "bypass check: index idx_c_int should exist after createIndex");
    }

    @Test
    @UnderTest(value = "queryIndexes", requiresVerifier = true)
    void should_query_indexes() throws Throwable {
        QueryIndexesFunction queryIndexes = require(functions()::getQueryIndexesFunction, "queryIndexes");
        prepareData(defaultRecordCount());
        // 索引准备走旁路直连（不经过 connector createIndex）
        verifier().createIndex(spec.getTableName(), "idx_c_int", "c_int");
        assertTrue(verifier().listIndexes(spec.getTableName()).contains("idx_c_int"),
                "bypass check: prepared index should exist");
        TapTable table = buildTapTable();
        List<TapIndex> indexes = new ArrayList<>();
        queryIndexes.query(nodeContext(), table, indexes::addAll);
        assertTrue(indexes.stream().anyMatch(i -> "idx_c_int".equals(i.getName())),
                "index idx_c_int should be visible, got: " + indexes);
    }

    @Test
    @UnderTest(value = "deleteIndex", requiresVerifier = true)
    void should_delete_index() throws Throwable {
        DeleteIndexFunction deleteIndex = require(functions()::getDeleteIndexFunction, "deleteIndex");
        prepareData(defaultRecordCount());
        // 索引准备走旁路直连（不经过 connector createIndex）
        verifier().createIndex(spec.getTableName(), "idx_c_int", "c_int");
        assertTrue(verifier().listIndexes(spec.getTableName()).contains("idx_c_int"),
                "bypass check: prepared index should exist");
        TapTable table = buildTapTable();
        deleteIndex.deleteIndex(nodeContext(), table, TapSimplify.deleteIndexEvent(spec.getTableName(), List.of("idx_c_int")));
        // 旁路 listIndexes 验证删除真实生效（不经过 connector queryIndexes，避免自洽）
        assertFalse(verifier().listIndexes(spec.getTableName()).contains("idx_c_int"),
                "bypass check: index idx_c_int should be removed");
    }

    @Test
    @UnderTest(value = "createConstraint", requiresVerifier = true)
    void should_create_constraint() throws Throwable {
        CreateConstraintFunction createConstraint = require(functions()::getCreateConstraintFunction, "createConstraint");
        prepareData(defaultRecordCount());
        createConstraint.createConstraint(nodeContext(), buildTapTable(),
                new TapCreateConstraintEvent().constraintList(List.of(testUniqueConstraint())), true);
        // 旁路 listConstraints 验证约束真实创建（事实来源 = 对端库，不依赖 connector queryConstraints）
        assertTrue(verifier().listConstraints(spec.getTableName()).contains("uq_c_int"),
                "bypass check: constraint uq_c_int should exist after createConstraint");
    }

    @Test
    @UnderTest(value = "queryConstraints", requiresVerifier = true)
    void should_query_constraints() throws Throwable {
        QueryConstraintsFunction queryConstraints = require(functions()::getQueryConstraintsFunction, "queryConstraints");
        prepareData(defaultRecordCount());
        // 约束准备走旁路直连（不经过 connector createConstraint）
        verifier().createConstraint(spec.getTableName(), "uq_c_int", "c_int");
        assertTrue(verifier().listConstraints(spec.getTableName()).contains("uq_c_int"),
                "bypass check: prepared constraint should exist");
        TapTable table = buildTapTable();
        List<TapConstraint> constraints = new ArrayList<>();
        queryConstraints.query(nodeContext(), table, constraints::addAll);
        assertTrue(constraints.stream().anyMatch(c -> "uq_c_int".equals(c.getName())),
                "constraint uq_c_int should be visible, got: " + constraints);
    }

    @Test
    @UnderTest(value = "dropConstraint", requiresVerifier = true)
    void should_drop_constraint() throws Throwable {
        DropConstraintFunction dropConstraint = require(functions()::getDropConstraintFunction, "dropConstraint");
        prepareData(defaultRecordCount());
        // 约束准备走旁路直连（不经过 connector createConstraint）
        verifier().createConstraint(spec.getTableName(), "uq_c_int", "c_int");
        assertTrue(verifier().listConstraints(spec.getTableName()).contains("uq_c_int"),
                "bypass check: prepared constraint should exist");
        TapTable table = buildTapTable();
        dropConstraint.dropConstraint(nodeContext(), table,
                new TapDropConstraintEvent().constraintList(List.of(testUniqueConstraint())));
        // 旁路 listConstraints 验证删除真实生效（不经过 connector queryConstraints，避免自洽）
        assertFalse(verifier().listConstraints(spec.getTableName()).contains("uq_c_int"),
                "bypass check: constraint uq_c_int should be removed");
    }

    /** 外键用例辅助父表名前缀（tearDown 兜底清理锚点，见 {@link #dropResidualTables()}） */
    private static final String FOREIGN_KEY_PARENT_PREFIX = "_tap_it_fkp_";

    /**
     * 外键用例公共准备：旁路创建子表（当前 spec 表）与父表（id bigint 主键，写入 id=1..N），
     * 子表 c_bigint 覆写为 1..N 保证每条引用命中父表 id，避免外键完整性校验失败，
     * 全程不经过 connector 任何能力。
     *
     * @return 父表名（随机生成，用例结束需 {@link #dropForeignKeyAuxTable(String)} 清理）
     */
    private String prepareForeignKeyData() throws Throwable {
        String parentTable = TestTableSpec.randomTableName(FOREIGN_KEY_PARENT_PREFIX);
        int count = defaultRecordCount();
        // 子表：与 prepareData 一致先建表再旁路写入（不经过 connector createTableV2）
        createTableIfNeeded();
        verifier().createTable(parentTable, List.of(TestFieldSpec.builder()
                .name("id").dataType("bigint").testDataType(TestDataType.BIGINT).primaryKey(true).build()));
        List<Map<String, Object>> parentRows = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            parentRows.add(Collections.singletonMap("id", (Object) (long) i));
        }
        verifier().insert(parentTable, parentRows);
        List<Map<String, Object>> rows = beforeWrite(generateRows(count));
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).put("c_bigint", (long) (i + 1));
        }
        bypassInsert(rows);
        return parentTable;
    }

    /**
     * 外键用例兜底清理：先删引用方（子表，tearDown 再删一次亦无害）再删父表，
     * 避免残留外键阻塞删表（tearDown 的 dropResidualTables 不感知父表）。
     */
    private void dropForeignKeyAuxTable(String parentTable) {
        if (verifier() == null || parentTable == null) {
            return;
        }
        try {
            verifier().dropTable(spec.getTableName());
        } catch (Throwable ignored) {
        }
        try {
            verifier().dropTable(parentTable);
        } catch (Throwable ignored) {
        }
    }

    @Test
    @UnderTest(value = "createConstraint", requiresVerifier = true)
    void should_create_foreign_key_constraint() throws Throwable {
        CreateConstraintFunction createConstraint = require(functions()::getCreateConstraintFunction, "createConstraint");
        String parentTable = null;
        try {
            parentTable = prepareForeignKeyData();
            createConstraint.createConstraint(nodeContext(), buildTapTable(),
                    new TapCreateConstraintEvent().constraintList(List.of(testForeignKeyConstraint(parentTable))), true);
            // 旁路 listConstraints 验证外键真实创建（事实来源 = 对端库，不依赖 connector queryConstraints）
            assertTrue(verifier().listConstraints(spec.getTableName()).contains("fk_c_bigint"),
                    "bypass check: foreign key fk_c_bigint should exist after createConstraint");
        } finally {
            dropForeignKeyAuxTable(parentTable);
        }
    }

    @Test
    @UnderTest(value = "queryConstraints", requiresVerifier = true)
    void should_query_foreign_key_constraints() throws Throwable {
        QueryConstraintsFunction queryConstraints = require(functions()::getQueryConstraintsFunction, "queryConstraints");
        String parentTable = null;
        try {
            parentTable = prepareForeignKeyData();
            // 外键准备走旁路直连（不经过 connector createConstraint）
            verifier().createForeignKeyConstraint(spec.getTableName(), "fk_c_bigint", "c_bigint", parentTable, "id");
            assertTrue(verifier().listConstraints(spec.getTableName()).contains("fk_c_bigint"),
                    "bypass check: prepared foreign key should exist");
            TapTable table = buildTapTable();
            List<TapConstraint> constraints = new ArrayList<>();
            queryConstraints.query(nodeContext(), table, constraints::addAll);
            final String refParentTable = parentTable;
            assertTrue(constraints.stream().anyMatch(c -> "fk_c_bigint".equals(c.getName())
                            && TapConstraint.ConstraintType.FOREIGN_KEY == c.getType()
                            && refParentTable.equals(c.getReferencesTableName())),
                    "foreign key fk_c_bigint should be visible with references " + parentTable + ", got: " + constraints);
        } finally {
            dropForeignKeyAuxTable(parentTable);
        }
    }

    @Test
    @UnderTest(value = "dropConstraint", requiresVerifier = true)
    void should_drop_foreign_key_constraint() throws Throwable {
        DropConstraintFunction dropConstraint = require(functions()::getDropConstraintFunction, "dropConstraint");
        String parentTable = null;
        try {
            parentTable = prepareForeignKeyData();
            // 外键准备走旁路直连（不经过 connector createConstraint）
            verifier().createForeignKeyConstraint(spec.getTableName(), "fk_c_bigint", "c_bigint", parentTable, "id");
            assertTrue(verifier().listConstraints(spec.getTableName()).contains("fk_c_bigint"),
                    "bypass check: prepared foreign key should exist");
            TapTable table = buildTapTable();
            dropConstraint.dropConstraint(nodeContext(), table,
                    new TapDropConstraintEvent().constraintList(List.of(testForeignKeyConstraint(parentTable))));
            // 旁路 listConstraints 验证删除真实生效（不经过 connector queryConstraints，避免自洽）
            assertFalse(verifier().listConstraints(spec.getTableName()).contains("fk_c_bigint"),
                    "bypass check: foreign key fk_c_bigint should be removed");
        } finally {
            dropForeignKeyAuxTable(parentTable);
        }
    }

    // ===================== E. 字段级 DDL =====================

    @Test
    @UnderTest(value = "newField", requiresVerifier = true)
    void should_new_field() throws Throwable {
        NewFieldFunction newField = require(functions()::getNewFieldFunction, "newField");
        createTableIfNeeded();
        TapField newColumn = TapSimplify.field("c_new_col", "varchar(64)").tapType(TapSimplify.tapString()).nullable(true);
        TapNewFieldEvent event = new TapNewFieldEvent().field(newColumn);
        event.setTableId(spec.getTableName());
        newField.newField(nodeContext(), event);
        // 旁路 tableColumns 验证新列真实存在（事实来源 = 对端库，不经过 connector discoverSchema）
        assertTrue(verifier().tableColumns(spec.getTableName()).stream()
                        .anyMatch(c -> "c_new_col".equals(c.get("name"))),
                "bypass check: new field c_new_col should exist in real table columns");
    }

    @Test
    @UnderTest(value = "dropField", requiresVerifier = true)
    void should_drop_field() throws Throwable {
        DropFieldFunction dropField = require(functions()::getDropFieldFunction, "dropField");
        createTableIfNeeded();
        // 选取非主键字段作为删除目标（c_text 为可选类型，不能假设存在）
        String targetField = spec.getFields().stream()
                .map(TestFieldSpec::getName)
                .filter(name -> !name.equals(primaryKeyName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("test table spec must have a non-primary-key field"));
        TapDropFieldEvent event = new TapDropFieldEvent().fieldName(targetField);
        event.setTableId(spec.getTableName());
        dropField.dropField(nodeContext(), event);
        // 旁路 tableColumns 验证列真实删除（事实来源 = 对端库，不经过 connector discoverSchema）
        assertTrue(verifier().tableColumns(spec.getTableName()).stream()
                        .noneMatch(c -> targetField.equals(c.get("name"))),
                "bypass check: dropped field " + targetField + " should not exist in real table columns");
    }

    @Test
    @UnderTest(value = "alterFieldName", requiresVerifier = true)
    void should_alter_field_name() throws Throwable {
        AlterFieldNameFunction alterFieldName = require(functions()::getAlterFieldNameFunction, "alterFieldName");
        createTableIfNeeded();
        prepareStreamReadTable();
        TapTable table = buildTapTable();
        registerTable(table);
        TapAlterFieldNameEvent event = new TapAlterFieldNameEvent()
                .nameChange(new ValueChange<>("c_varchar", "c_varchar_new"));
        event.setTableId(spec.getTableName());
        alterFieldName.alterFieldName(nodeContext(), event);
        // 旁路 tableColumns 验证重命名真实生效（事实来源 = 对端库，不经过 connector discoverSchema）
        List<Map<String, Object>> columns = verifier().tableColumns(spec.getTableName());
        assertTrue(columns.stream().anyMatch(c -> "c_varchar_new".equals(c.get("name"))),
                "bypass check: renamed field should exist in real table columns");
        assertTrue(columns.stream().noneMatch(c -> "c_varchar".equals(c.get("name"))),
                "bypass check: old field name should be gone from real table columns");
    }

    @Test
    @UnderTest(value = "alterFieldAttributes", requiresVerifier = true)
    void should_alter_field_attributes() throws Throwable {
        AlterFieldAttributesFunction alter = require(functions()::getAlterFieldAttributesFunction, "alterFieldAttributes");
        createTableIfNeeded();
        prepareStreamReadTable();
        TapTable table = buildTapTable();
        registerTable(table);
        TapAlterFieldAttributesEvent event = new TapAlterFieldAttributesEvent().fieldName("c_varchar")
                .dataType(new ValueChange<>("varchar(255)", "varchar(500)"));
        event.setTableId(spec.getTableName());
        alter.alterFieldAttributes(nodeContext(), event);
        // 旁路 tableColumns 验证列属性变更真实生效：字段仍存在且长度已扩为 500（事实来源 = 对端库）
        List<Map<String, Object>> columns = verifier().tableColumns(spec.getTableName());
        assertTrue(columns.stream().anyMatch(c -> "c_varchar".equals(c.get("name"))),
                "bypass check: altered field should still exist in real table columns");
        assertTrue(columns.stream().anyMatch(c -> "c_varchar".equals(c.get("name")) && Integer.valueOf(500).equals(c.get("size"))),
                "bypass check: c_varchar size should be 500 after alter, got: " + columns);
    }

    // ===================== F. 流式读取（增量） =====================

    @Test
    @UnderTest(value = "timestampToStreamOffset")
    void should_timestamp_to_stream_offset() throws Throwable {
        TimestampToStreamOffsetFunction fn = require(functions()::getTimestampToStreamOffsetFunction, "timestampToStreamOffset");
        Object offset = fn.timestampToStreamOffset(nodeContext(), System.currentTimeMillis());
        assertNotNull(offset, "timestampToStreamOffset should return non-null offset");
    }

    @Test
    @UnderTest(value = "streamRead", requiresVerifier = true)
    void should_stream_read_incremental() throws Throwable {
        StreamReadFunction streamRead = require(functions()::getStreamReadFunction, "streamRead");
        TimestampToStreamOffsetFunction ts2offset = require(functions()::getTimestampToStreamOffsetFunction, "timestampToStreamOffset");
        createTableIfNeeded();
        prepareStreamReadTable();
        TapTable table = buildTapTable();
        // 注册到 tableMap：引擎建表后 tableMap 必含该表；DB2 i 的 timestampToStreamOffset 依赖
        // tableMap 查系统表名（DISPLAY_JOURNAL 按真实 OBJECT_NAME 过滤），否则 offset 退化为 0
        // 导致 streamRead 从“当前最新位置”起读，可能跳过刚写入的增量数据
        registerTable(table);
        Object offset = ts2offset.timestampToStreamOffset(nodeContext(), System.currentTimeMillis());
        assertNotNull(offset, "timestampToStreamOffset should return non-null offset");
        int incrementalCount = 5;
        List<Map<String, Object>> expected = generateRows(incrementalCount);
        List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch catchUpLatch = new CountDownLatch(1);
        long catchUpTimestampSeconds = System.currentTimeMillis() / 1000L;
        StreamReadConsumer consumer = StreamReadConsumer.create((events, off) -> {
            if (off instanceof Number && ((Number) off).longValue() >= catchUpTimestampSeconds) {
                catchUpLatch.countDown();
            }
            for (TapEvent e : events) {
                if (e instanceof TapInsertRecordEvent) {
                    received.add(((TapInsertRecordEvent) e).getAfter());
                }
            }
            if (received.size() >= incrementalCount) {
                latch.countDown();
            }
        });
        log(context, "[IT] streamRead thread starting, offset: {}, incremental count: {}", offset, incrementalCount);
        AtomicReference<Throwable> streamReadError = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                streamRead.streamRead(nodeContext(), List.of(table.getId()), offset, 100, consumer);
            } catch (Throwable t) {
                streamReadError.set(t);
                log(context, "[IT] streamRead thread terminated: {}", t.getMessage());
            }
        }, "tap-it-stream-read");
        reader.setDaemon(true);
        reader.start();
        long startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(streamReadTimeoutSeconds());
        while (consumer.getState() != StreamReadConsumer.STATE_STREAM_READ_STARTED
                && streamReadError.get() == null && System.nanoTime() < startDeadline) {
            Thread.sleep(50L);
        }
        assertNull(streamReadError.get(), () -> "streamRead thread terminated before startup: " + describeThrowable(streamReadError.get()));
        assertEquals(StreamReadConsumer.STATE_STREAM_READ_STARTED, consumer.getState(), "streamRead did not report started state");
        if (waitForStreamReadCatchUp()) {
            assertTrue(catchUpLatch.await(streamReadTimeoutSeconds(), TimeUnit.SECONDS),
                    "streamRead did not catch up to " + catchUpTimestampSeconds + " before incremental writes");
        }
        // 写增量数据：旁路直连写入并 count 确认（不经过 connector writeRecord；事实来源 = 对端库）
        bypassInsert(expected);
        boolean done = latch.await(streamReadTimeoutSeconds(), TimeUnit.SECONDS);
        // 断言 reader 线程未捕获异常：streamRead 内部错误（如 MySQL handleDatetime 拆箱 NPE）必须直接暴露为用例失败，
        // 而不是被吞掉后表现为“收不到增量”的超时失败，导致真实根因丢失
        assertNull(streamReadError.get(), () -> "streamRead thread terminated with error: " + describeThrowable(streamReadError.get()));
        assertTrue(done, "streamRead did not receive " + incrementalCount + " incremental records within 15s, got: " + received.size());
        assertTrue(received.size() >= incrementalCount, "received records should be >= " + incrementalCount);
        // 数据一致性：以旁路 select 的对端库事实为基准，与 streamRead 读回的行逐字段比对
        List<Map<String, Object>> actual = new ArrayList<>(received);
        Map<String, Map<String, Object>> byPk = new HashMap<>();
        for (Map<String, Object> row : actual) {
            byPk.put(String.valueOf(row.get(primaryKeyName())), row);
        }
        List<Map<String, Object>> groundTruth = verifier().selectByPk(spec.getTableName(), primaryKeyName(), pkValues(expected));
        Map<String, Map<String, Object>> truthByPk = new HashMap<>();
        for (Map<String, Object> row : groundTruth) {
            truthByPk.put(String.valueOf(row.get(primaryKeyName())), row);
        }
        for (Map<String, Object> expRow : expected) {
            Map<String, Object> actRow = byPk.get(String.valueOf(expRow.get(primaryKeyName())));
            assertNotNull(actRow, "incremental row pk=" + expRow.get(primaryKeyName()) + " not received");
            Map<String, Object> truthRow = truthByPk.get(String.valueOf(expRow.get(primaryKeyName())));
            assertNotNull(truthRow, "ground truth row pk=" + expRow.get(primaryKeyName()) + " missing");
            for (TestFieldSpec field : spec.getFields()) {
                RecordAssert.assertEquals(field.getTestDataType(),
                        truthRow.get(field.getName()), actRow.get(field.getName()), field.getName());
            }
        }
    }

    @Test
    @UnderTest(value = "streamReadOneByOne", requiresVerifier = true)
    void should_stream_read_one_by_one() throws Throwable {
        StreamReadOneByOneFunction streamRead = require(functions()::getStreamReadOneByOneFunction, "streamReadOneByOne");
        TimestampToStreamOffsetFunction ts2offset = functions().getTimestampToStreamOffsetFunction();
        assumeTrue(ts2offset != null, "timestampToStreamOffset not supported, skip.");
        createTableIfNeeded();
        TapTable table = buildTapTable();
        // 注册到 tableMap：与 streamRead 用例同理，保证 timestampToStreamOffset 拿到写前位置
        registerTable(table);
        Object offset = ts2offset.timestampToStreamOffset(nodeContext(), System.currentTimeMillis());
        int incrementalCount = 3;
        List<Map<String, Object>> expected = generateRows(incrementalCount);
        List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        StreamReadOneByOneConsumer consumer = StreamReadOneByOneConsumer.create((event, off) -> {
            if (event instanceof TapInsertRecordEvent) {
                received.add(((TapInsertRecordEvent) event).getAfter());
            }
            if (received.size() >= incrementalCount) {
                latch.countDown();
            }
        });
        AtomicReference<Throwable> streamReadError = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                streamRead.streamRead(nodeContext(), List.of(spec.getTableName()), offset, consumer);
            } catch (Throwable t) {
                streamReadError.set(t);
                log(context, "streamReadOneByOne thread terminated: {}", t.getMessage());
            }
        }, "tap-it-stream-read-1x1");
        reader.setDaemon(true);
        reader.start();
        // 写增量数据：旁路直连写入并 count 确认（不经过 connector writeRecord；事实来源 = 对端库）
        bypassInsert(expected);
        boolean done = latch.await(streamReadTimeoutSeconds(), TimeUnit.SECONDS);
        // 断言 reader 线程未捕获异常（同 should_stream_read_incremental：异步内部错误必须直接暴露）
        assertNull(streamReadError.get(), () -> "streamReadOneByOne thread terminated with error: " + describeThrowable(streamReadError.get()));
        assertTrue(done, "streamReadOneByOne did not receive " + incrementalCount + " records within 15s, got: " + received.size());
    }

    @Test
    @UnderTest(value = "streamReadMultiConnection", requiresVerifier = true)
    void should_stream_read_multi_connection() throws Throwable {
        StreamReadMultiConnectionFunction streamRead = require(functions()::getStreamReadMultiConnectionFunction, "streamReadMultiConnection");
        TimestampToStreamOffsetFunction ts2offset = require(functions()::getTimestampToStreamOffsetFunction, "timestampToStreamOffset");
        createTableIfNeeded();
        TapTable table = buildTapTable();
        // 注册到 tableMap + 写前位置 offset：与 should_stream_read_incremental 同理——
        // 若传 null，BinaryLogClient 从“当前最新位置”起读，旁路写入已完成的增量会被跳过（got: 0）
        registerTable(table);
        // 多连接（multi）模式：MysqlReaderV2 withSchema=true 时以 "database.table" 复合 key 查 tableMap
        // （引擎多库合并场景的 tableMap 行为），纯表名注册不满足该查找，行事件会被 prepareRowChangeContext 丢弃
        if (context.getNodeContext().getTableMap() instanceof KVMap) {
            ((KVMap<TapTable>) context.getNodeContext().getTableMap())
                    .put(context.getConfig().getString("database") + "." + table.getId(), table);
        }
        Object offset = ts2offset.timestampToStreamOffset(nodeContext(), System.currentTimeMillis());
        List<Map<String, Object>> expected = generateRows(3);
        List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        StreamReadConsumer consumer = StreamReadConsumer.create((events, off) -> {
            for (TapEvent e : events) {
                if (e instanceof TapInsertRecordEvent) {
                    received.add(((TapInsertRecordEvent) e).getAfter());
                }
            }
            if (received.size() >= 3) {
                latch.countDown();
            }
        });
        ConnectionConfigWithTables connectionWithTables =
                new ConnectionConfigWithTables()
                        .connectionConfig(context.getConfig()).table(spec.getTableName());
        AtomicReference<Throwable> streamReadError = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                streamRead.streamRead(nodeContext(), List.of(connectionWithTables), offset, 100, consumer);
            } catch (Throwable t) {
                streamReadError.set(t);
                log(context, "streamReadMultiConnection thread terminated: {}", t.getMessage());
            }
        }, "tap-it-stream-read-multi");
        reader.setDaemon(true);
        reader.start();
        // 写增量数据：旁路直连写入并 count 确认（不经过 connector writeRecord；事实来源 = 对端库）
        bypassInsert(expected);
        boolean done = latch.await(streamReadTimeoutSeconds(), TimeUnit.SECONDS);
        // 断言 reader 线程未捕获异常（同 should_stream_read_incremental：异步内部错误必须直接暴露）
        assertNull(streamReadError.get(), () -> "streamReadMultiConnection thread terminated with error: " + describeThrowable(streamReadError.get()));
        assertTrue(done, "streamReadMultiConnection did not receive records within 15s, got: " + received.size());
    }

    // ===================== G. 事务 =====================

    @Test
    @UnderTest(value = "transactionBegin", requiresVerifier = true)
    @UnderTest(value = "transactionCommit", requiresVerifier = true)
    void should_transaction_commit() throws Throwable {
        TransactionBeginFunction begin = require(functions()::getTransactionBeginFunction, "transactionBegin");
        TransactionCommitFunction commit = require(functions()::getTransactionCommitFunction, "transactionCommit");
        createTableIfNeeded();
        TapTable table = buildTapTable();
        begin.begin(nodeContext());
        long inserted;
        try {
            inserted = writeInsertEvents(beforeWrite(generateRows(5)), table);
        } finally {
            commit.commit(nodeContext());
        }
        assertEquals(5, inserted, "inserted count mismatch");
        // 旁路 count：commit 后行可见（事实来源 = 对端库）
        assertEquals(5, verifyCount(), "committed rows should be visible");
    }

    @Test
    @UnderTest(value = "transactionBegin", requiresVerifier = true)
    @UnderTest(value = "transactionRollback", requiresVerifier = true)
    void should_transaction_rollback() throws Throwable {
        TransactionBeginFunction begin = require(functions()::getTransactionBeginFunction, "transactionBegin");
        TransactionRollbackFunction rollback = require(functions()::getTransactionRollbackFunction, "transactionRollback");
        createTableIfNeeded();
        TapTable table = buildTapTable();
        begin.begin(nodeContext());
        try {
            writeInsertEvents(beforeWrite(generateRows(5)), table);
        } finally {
            rollback.rollback(nodeContext());
        }
        // 旁路 count：rollback 后行不可见（事实来源 = 对端库）
        assertEquals(0, verifyCount(), "rolled back rows should not be visible");
    }

    // ===================== H. 命令与原始操作 =====================

    @Test
    @UnderTest(value = "executeCommand", requiresVerifier = true)
    void should_execute_command() throws Throwable {
        assumeTrue(context.isExecuteCommandSupportsPing(),
                "executeCommand does not support SQL-style ping command, skip.");
        ExecuteCommandFunction execute = require(functions()::getExecuteCommandFunction, "executeCommand");
        createTableIfNeeded();
        TapExecuteCommand command = new TapExecuteCommand().command("ping");
        List<ExecuteResult<?>> results = new ArrayList<>();
        execute.execute(nodeContext(), command, results::add);
        assertFalse(results.isEmpty(), "executeCommand should invoke consumer");
    }

    @Test
    @UnderTest(value = "executeCommandV2")
    void should_execute_command_v2() throws Throwable {
        ExecuteCommandV2Function execute = require(functions()::getExecuteCommandV2Function, "executeCommandV2");
        List<Map<String, Object>> results = new ArrayList<>();
        execute.execute(connectionContext(), "query", "select 1", r -> {
            if (r != null) {
                results.addAll(r);
            }
        });
        assertFalse(results.isEmpty(), "executeCommandV2 should return result rows");
    }

    @Test
    @UnderTest(value = "runRawCommand", requiresVerifier = true)
    void should_run_raw_command() throws Throwable {
        RunRawCommandFunction run = require(functions()::getRunRawCommandFunction, "runRawCommand");
        prepareData(defaultRecordCount());
        TapTable table = buildTapTable();
        List<TapEvent> events = new ArrayList<>();
        run.run(nodeContext(), rawQueryCommand(spec.getTableName()), table, 100,
                receivedEvents -> receivedEvents.forEach(e -> {
                    if (e instanceof TapInsertRecordEvent) {
                        events.add(e);
                    }
                }));
        // 旁路 count 比对：raw 命令读回全部行（事实来源 = 对端库）
        assertEquals(verifyCount(), events.size(),
                "runRawCommand should return all rows, bypass count=" + verifyCount());
    }

    @Test
    @UnderTest(value = "countRawCommand", requiresVerifier = true)
    void should_count_raw_command() throws Throwable {
        CountRawCommandFunction count = require(functions()::getCountRawCommandFunction, "countRawCommand");
        prepareData(defaultRecordCount());
        long rawCount = count.count(nodeContext(), rawCountCommand(spec.getTableName()), buildTapTable());
        // 旁路 count 比对（事实来源 = 对端库）
        assertEquals(verifyCount(), rawCount, "raw count should match bypass count");
    }

    @Test
    @UnderTest(value = "exportEventSql", requiresVerifier = true)
    void should_export_event_sql() throws Throwable {
        ExportEventSqlFunction export = require(functions()::getExportEventSqlFunction, "exportEventSql");
        createTableIfNeeded();
        TapTable table = buildTapTable();
        Map<String, Object> row = generateRows(1).get(0);
        TapInsertRecordEvent event = TapSimplify.insertRecordEvent(row, table.getId());
        String sql = export.exportEventSql(nodeContext(), event, table);
        assertNotNull(sql, "exportEventSql should return non-null SQL");
        assertFalse(sql.trim().isEmpty(), "exportEventSql should return non-empty SQL");
    }

    // ===================== I. 其他能力 =====================

    @Test
    @UnderTest(value = "getCurrentTimestamp")
    void should_get_current_timestamp() throws Throwable {
        GetCurrentTimestampFunction fn = require(functions()::getGetCurrentTimestampFunction, "getCurrentTimestamp");
        long now = fn.now(nodeContext());
        long diff = Math.abs(System.currentTimeMillis() - now);
        assertTrue(diff < 5 * 60 * 1000, "current timestamp should be close to system time, diff=" + diff + "ms");
    }

    @Test
    @UnderTest(value = "queryHashByAdvanceFilter", requiresVerifier = true)
    void should_query_hash_by_advance_filter() throws Throwable {
        QueryHashByAdvanceFilterFunction query = require(functions()::getQueryHashByAdvanceFilterFunction, "queryHashByAdvanceFilter");
        prepareData(defaultRecordCount());
        List<TapHashResult<String>> results = new ArrayList<>();
        query.query(nodeContext(), new TapAdvanceFilter(), buildTapTable(), results::add);
        assertFalse(results.isEmpty(), "queryHashByAdvanceFilter should invoke consumer");
        assertNotNull(results.get(0).getHash(), "hash result should be non-null");
    }

    @Test
    @UnderTest(value = "control")
    void should_control() throws Throwable {
        ControlFunction control = require(functions()::getControlFunction, "control");
        control.control(nodeContext(), new HeartbeatEvent());
    }

    @Test
    @UnderTest(value = "processControl")
    void should_process_control() throws Throwable {
        // ProcessControlFunction 为 pdk-api 2.0.8 新增，低版本环境自动跳过
        Object processControl = reflectFunction("getProcessControlFunction", "processControl");
        invokeFunction(processControl, "processControl", nodeContext(), new HeartbeatEvent());
    }

    @Test
    @UnderTest(value = "getStreamOffset")
    void should_get_stream_offset() throws Throwable {
        GetStreamOffsetFunction getStreamOffset = require(functions()::getGetStreamOffsetFunction, "getStreamOffset");
        Object offset = getStreamOffset.getStreamOffset(nodeContext(), null);
        assertNotNull(offset, "getStreamOffset should return non-null offset");
    }

    @Test
    @UnderTest(value = "flushOffset")
    void should_flush_offset() throws Throwable {
        FlushOffsetFunction flushOffset = require(functions()::getFlushOffsetFunction, "flushOffset");
        // getStreamOffset 仅为准备（非被测）：有则取真实 offset，无则以 null 调用 flushOffset
        GetStreamOffsetFunction getStreamOffset = functions().getGetStreamOffsetFunction();
        Object offset = getStreamOffset != null ? getStreamOffset.getStreamOffset(nodeContext(), null) : null;
        flushOffset.flushOffset(nodeContext(), offset);
    }

    @Test
    @UnderTest(value = "errorHandle")
    void should_error_handle() throws Throwable {
        ErrorHandleFunction errorHandle = require(functions()::getErrorHandleFunction, "errorHandle");
        RetryOptions retryOptions = errorHandle.needRetry(connectionContext(), PDKMethod.INIT, new RuntimeException("tap-it test error"));
        assertNotNull(retryOptions, "errorHandle should return RetryOptions");
    }

    @Test
    @UnderTest(value = "createPartitionTable", requiresVerifier = true)
    void should_create_partition_table() throws Throwable {
        CreatePartitionTableFunction createPartition = require(functions()::getCreatePartitionTableFunction, "createPartitionTable");
        CreateTableOptions options = createPartition.createTable(nodeContext(), TapSimplify.createTableEvent(buildTapTable()));
        assertNotNull(options, "createPartitionTable should return CreateTableOptions");
        // 旁路确认分区表真实存在（建表动作锚点）
        assertTrue(verifier().tableExists(spec.getTableName()),
                "bypass check: partition table should exist after createPartitionTable");
    }

    @Test
    @UnderTest(value = "queryPartitionTablesByParentName")
    void should_query_partition_tables() throws Throwable {
        QueryPartitionTablesByParentName queryPartitions = require(functions()::getQueryPartitionTablesByParentName, "queryPartitionTablesByParentName");
        // createPartitionTable 仅为准备（非被测）
        CreatePartitionTableFunction createPartition = functions().getCreatePartitionTableFunction();
        assumeTrue(createPartition != null, "createPartitionTable not supported, skip.");
        TapTable table = buildTapTable();
        createPartition.createTable(nodeContext(), TapSimplify.createTableEvent(table));
        List<TapPartitionResult> results = new ArrayList<>();
        queryPartitions.query(nodeContext(), List.of(table), results::addAll);
        assertFalse(results.isEmpty(), "queryPartitionTablesByParentName should return partition info");
    }

    @Test
    @UnderTest(value = "dropPartitionTable")
    void should_drop_partition_table() throws Throwable {
        DropPartitionTableFunction dropPartition = require(functions()::getDropPartitionTableFunction, "dropPartitionTable");
        // createPartitionTable 仅为准备（非被测）
        CreatePartitionTableFunction createPartition = functions().getCreatePartitionTableFunction();
        assumeTrue(createPartition != null, "createPartitionTable not supported, skip.");
        createPartition.createTable(nodeContext(), TapSimplify.createTableEvent(buildTapTable()));
        dropPartition.dropTable(nodeContext(), TapSimplify.dropTableEvent(spec.getTableName()));
    }

    @Test
    @UnderTest(value = "countByPartitionFilter", requiresVerifier = true)
    void should_count_by_partition_filter() throws Throwable {
        CountByPartitionFilterFunction count = require(functions()::getCountByPartitionFilterFunction, "countByPartitionFilter");
        prepareData(defaultRecordCount());
        long countByPartition = count.countByPartitionFilter(nodeContext(), buildTapTable(), new TapAdvanceFilter());
        // 旁路 count 比对（事实来源 = 对端库）
        assertEquals(verifyCount(), countByPartition,
                "countByPartitionFilter without filter should match bypass count");
    }

    @Test
    @UnderTest(value = "getReadPartitions", requiresVerifier = true)
    void should_get_read_partitions() throws Throwable {
        GetReadPartitionsFunction getReadPartitions = require(functions()::getGetReadPartitionsFunction, "getReadPartitions");
        prepareData(defaultRecordCount());
        // typeSplitterMap/minMaxSplitPieces/completedRunnable 由引擎在调用前注入，Connector 侧可能注册自定义 splitter（如 MongoDB ObjectIdSplitter）
        List<String> partitionIds = new ArrayList<>();
        GetReadPartitionOptions options = new GetReadPartitionOptions()
                .maxRecordInPartition(1000L)
                .minMaxSplitPieces(100)
                .typeSplitterMap(new TypeSplitterMap())
                .completedRunnable(() -> log(context, "[IT] read partitions completed"))
                .consumer(partition -> {
                    partitionIds.add(partition.getId());
                    log(context, "read partition: {}", partition);
                });
        getReadPartitions.getReadPartitions(nodeContext(), buildTapTable(), options);
        // 分区消费真实发生：consumer 至少收到 1 个分区（空表无数据时也应有最小分区集）
        assertFalse(partitionIds.isEmpty(), "getReadPartitions should produce at least one partition");
    }

    @Test
    @UnderTest(value = "queryFieldMinMaxValue", requiresVerifier = true)
    void should_query_field_min_max() throws Throwable {
        QueryFieldMinMaxValueFunction fn = require(functions()::getQueryFieldMinMaxValueFunction, "queryFieldMinMaxValue");
        List<Map<String, Object>> expected = prepareData(defaultRecordCount());
        TapTable table = buildTapTable();
        if (context.isFieldMinMaxRequiresPartitionIndex()) {
            // 索引驱动 min/max 的库（如 MongoDB）：目标字段必须在分区索引中，模拟引擎分区读取时设置分区索引
            table.setPartitionIndex(new TapIndexEx(new TapIndex().name("idx_c_int").unique(false)
                    .indexField(new TapIndexField().name("c_int").fieldAsc(true))));
        }
        FieldMinMaxValue minMax = fn.minMaxValue(nodeContext(), table, new TapAdvanceFilter(), "c_int");
        assertNotNull(minMax, "queryFieldMinMaxValue should return FieldMinMaxValue");
        assertNotNull(minMax.getMin(), "min value should be non-null");
        assertNotNull(minMax.getMax(), "max value should be non-null");
        // 期望 min/max 由旁路 select 计算（事实来源 = 对端库）
        List<Map<String, Object>> groundTruth = verifier().selectByPk(spec.getTableName(), primaryKeyName(), pkValues(expected));
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (Map<String, Object> row : groundTruth) {
            long v = ((Number) row.get("c_int")).longValue();
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertEquals(min, ((Number) minMax.getMin()).longValue(), "min value mismatch");
        assertEquals(max, ((Number) minMax.getMax()).longValue(), "max value mismatch");
    }

    @Test
    @UnderTest(value = "connectorWebsite")
    void should_connector_website() throws Throwable {
        ConnectorWebsiteFunction fn = require(functions()::getConnectorWebsiteFunction, "connectorWebsite");
        Website website = fn.getUrl(connectionContext());
        assertNotNull(website, "connectorWebsite should return Website");
        assertNotNull(website.getUrl(), "website url should be non-null");
    }

    @Test
    @UnderTest(value = "tableWebsite", requiresVerifier = true)
    void should_table_website() throws Throwable {
        TableWebsiteFunction fn = require(functions()::getTableWebsiteFunction, "tableWebsite");
        createTableIfNeeded();
        Website website = fn.getUrl(connectionContext(), List.of(spec.getTableName()));
        assertNotNull(website, "tableWebsite should return Website");
        assertNotNull(website.getUrl(), "website url should be non-null");
    }

    @Test
    @UnderTest(value = "commandCallback")
    void should_command_callback() throws Throwable {
        CommandCallbackFunction fn = require(functions()::getCommandCallbackFunction, "commandCallback");
        CommandInfo commandInfo = new CommandInfo();
        commandInfo.setCommand("ping");
        CommandResult result = fn.filter(connectionContext(), commandInfo);
        assertNotNull(result, "commandCallback should return CommandResult");
    }

    // ===================== K. TapValue 转换契约（引擎 wrap/unwrap 语义） =====================

    /**
     * 转换契约用例（U1~U10）：验证引擎 Connector 边界的数据类型转换语义。
     * wrap = 普通值 → TapValue（transformToTapValueMap），unwrap = TapValue → 普通值（transformFromTapValueMap），
     * 均复用 connector registerCapabilities 的 codecRegistry，与引擎 TaskNodePdkConnector 边界行为一致。
     * U5 标注 @UnderTest("writeRecord")（写侧引擎 codec 路径），U10 标注 @UnderTest("batchRead")
     * （经库读回 → 统一转换 → spec 声明断言），其余用例不标注：验证的是 codec 转换契约而非 Connector 能力接口。
     */
    @Test
    void should_wrap_generated_values_to_tap_value() throws Throwable {
        // U1：引擎 wrap 契约 —— schema 专用类型（BINARY/DATE/DATETIME/MAP/ARRAY）包装为 TapValue
        // （类/值/origin 元数据正确），其余类型（NUMBER/STRING/BOOLEAN）保持原值不包装
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        List<Map<String, Object>> rows = beforeWrite(generateRows(defaultRecordCount()));
        TapTable table = buildTapTable();
        for (Map<String, Object> row : rows) {
            Map<String, Object> wrapped = wrapRow(row, table);
            for (TestFieldSpec field : spec.getFields()) {
                String name = field.getName();
                Object plain = row.get(name);
                if (plain == null) {
                    continue;
                }
                Object wrappedValue = wrapped.get(name);
                if (wrappedValue instanceof TapValue) {
                    TapValue<?, ?> tapValue = (TapValue<?, ?>) wrappedValue;
                    // 包装契约：类 ∈ 期望集合、值语义等价、origin 元数据（值变换时保留 originValue，originType = schema dataType）
                    TapValueAssert.assertValueClass(field.getTestDataType(), tapValue,
                            expectedTapValueClasses(field.getTestDataType()), name);
                    TapValueAssert.assertValueEquals(field.getTestDataType(), plain, tapValue, name);
                    TapValueAssert.assertOriginMetadata(field.getTestDataType(), plain, tapValue,
                            field.getDataType(), name);
                } else {
                    // 不包装契约：引擎对无专用 codec 的类型保持原值（值保真，不包 TapRawValue）
                    assertFalse(expectsWrap(field.getTestDataType()),
                            "field[" + name + "] should be wrapped into TapValue for type " + field.getTestDataType()
                                    + " but engine kept plain value: " + wrappedValue);
                    RecordAssert.assertEquals(field.getTestDataType(), plain, wrappedValue, name);
                }
            }
        }
    }

    @Test
    void should_unwrap_tap_value_to_plain_values() throws Throwable {
        // U2：unwrap 契约 —— TapValue 解包为普通值（custom codec 优先、默认 codec 兜底）。
        // 非时间字段严格语义等价；时间字段断言非 null（unwrap 输出形态随连接器自定义 codec 而异：
        // 字符串/Date/DateTime 等，其墙钟参照系由连接器决定，无法通用 epoch 化）
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        List<Map<String, Object>> rows = beforeWrite(generateRows(defaultRecordCount()));
        TapTable table = buildTapTable();
        for (Map<String, Object> row : rows) {
            Map<String, Object> unwrapped = unwrapRow(wrapRow(row, table));
            for (TestFieldSpec field : spec.getFields()) {
                String name = field.getName();
                Object plain = row.get(name);
                if (plain == null) {
                    continue;
                }
                switch (field.getTestDataType()) {
                    case DATE:
                    case DATETIME:
                    case TIMESTAMP:
                        assertNotNull(unwrapped.get(name),
                                "field[" + name + "] should unwrap to non-null plain value");
                        break;
                    default:
                        TapValueAssert.assertValueEquals(field.getTestDataType(), plain, unwrapped.get(name), name);
                }
            }
        }
    }

    @Test
    void should_wrap_unwrap_round_trip_keep_values() throws Throwable {
        // U3：wrap → unwrap → wrap 往返后值语义等价（引擎同构还原；
        // 期望以第一轮 unwrap 产物为准：unwrap 输出形态随连接器自定义 codec 而异）
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        List<Map<String, Object>> rows = beforeWrite(generateRows(defaultRecordCount()));
        TapTable table = buildTapTable();
        for (Map<String, Object> row : rows) {
            Map<String, Object> unwrapped = unwrapRow(wrapRow(row, table));
            Map<String, Object> second = wrapRow(unwrapped, table);
            for (TestFieldSpec field : spec.getFields()) {
                String name = field.getName();
                if (row.get(name) == null) {
                    continue;
                }
                Object unwrappedValue = unwrapped.get(name);
                Object wrappedValue = second.get(name);
                if (wrappedValue instanceof TapValue) {
                    TapValueAssert.assertValueEquals(field.getTestDataType(), unwrappedValue, (TapValue<?, ?>) wrappedValue, name);
                } else {
                    TapValueAssert.assertValueEquals(field.getTestDataType(), unwrappedValue, wrappedValue, name);
                }
            }
        }
    }

    @Test
    void should_preserve_origin_value_when_unwrapping() throws Throwable {
        // U4：wrap 时值被变换（value != 原值）的字段，unwrap 后保留 TapValue（含 originValue），
        // 供引擎同构还原源值；originValue 与原始生成值语义等价
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        List<Map<String, Object>> rows = beforeWrite(generateRows(defaultRecordCount()));
        TapTable table = buildTapTable();
        for (Map<String, Object> row : rows) {
            Map<String, Object> wrapped = wrapRow(row, table);
            // transformFromTapValueMap 返回 originValue 保留字段集合（输入 map 原地解包）
            Map<String, TapValue<?, ?>> originRetained =
                    engineCodecsFilterManager.transformFromTapValueMap(new HashMap<>(wrapped));
            for (TestFieldSpec field : spec.getFields()) {
                String name = field.getName();
                Object plain = row.get(name);
                if (plain == null || !(wrapped.get(name) instanceof TapValue)) {
                    continue;
                }
                TapValue<?, ?> tapValue = (TapValue<?, ?>) wrapped.get(name);
                if (tapValue.getOriginValue() == null) {
                    continue;
                }
                // wrap 值被变换 → unwrap 后该字段必须保留同一个 TapValue（origin 同构还原依据）
                assertTrue(originRetained.get(name) == tapValue,
                        "field[" + name + "] should be retained as TapValue after unwrap (originValue="
                                + tapValue.getOriginValue() + ")");
                // originValue == 原始生成值（值变换前后的精确还原依据）
                RecordAssert.assertEquals(field.getTestDataType(), plain, tapValue.getOriginValue(), name);
            }
            // 引擎契约：DATETIME/TIMESTAMP 生成值（LocalDateTime → DateTime）wrap 必发生值变换
            boolean hasTemporal = spec.getFields().stream().anyMatch(f ->
                    f.getTestDataType() == TestDataType.DATETIME || f.getTestDataType() == TestDataType.TIMESTAMP);
            if (hasTemporal) {
                assertFalse(originRetained.isEmpty(),
                        "wrap-transformed fields should be retained as TapValue on unwrap");
            }
        }
    }

    @Test
    @UnderTest(value = "writeRecord", requiresVerifier = true)
    void should_write_records_via_engine_codec_roundtrip() throws Throwable {
        // U5：完整引擎写路径（wrap → 引擎边界解包 → insertRecordEvent → writeRecord）后旁路读回，值语义等价
        createTableIfNeeded();
        List<Map<String, Object>> rows = beforeWrite(generateRows(defaultRecordCount()));
        long inserted = writeInsertEventsViaEngineCodec(rows, buildTapTable());
        assertEquals(rows.size(), inserted, "engine codec write path should insert all rows");
        List<Map<String, Object>> readBack = batchReadAll(buildTapTable());
        assertRowsConsistent(rows, readBack);
    }

    @Test
    void should_wrap_read_back_values_to_tap_value() throws Throwable {
        // U6：数据库读回值（batchRead 产物）的 wrap 契约 —— 时间列读回 Timestamp/Date 被专用 codec 识别包装，
        // 数值/字符串/布尔列读回后引擎不包装（保持原值）
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        prepareData(defaultRecordCount());
        List<Map<String, Object>> readBack = batchReadAll(buildTapTable());
        assertFalse(readBack.isEmpty(), "batchRead should return prepared rows");
        TapTable table = buildTapTable();
        int checked = 0;
        for (Map<String, Object> row : readBack) {
            if (checked++ >= 5) {
                break;
            }
            Map<String, Object> wrapped = wrapRow(row, table);
            for (TestFieldSpec field : spec.getFields()) {
                String name = field.getName();
                Object plain = row.get(name);
                if (plain == null) {
                    continue;
                }
                Object wrappedValue = wrapped.get(name);
                if (wrappedValue instanceof TapValue) {
                    TapValue<?, ?> tapValue = (TapValue<?, ?>) wrappedValue;
                    TapValueAssert.assertValueClass(field.getTestDataType(), tapValue,
                            expectedTapValueClassesForReadBack(field.getTestDataType()), name);
                    TapValueAssert.assertValueEquals(field.getTestDataType(), plain, tapValue, name);
                } else {
                    assertFalse(expectsWrap(field.getTestDataType()),
                            "field[" + name + "] should be wrapped into TapValue for type " + field.getTestDataType());
                }
            }
        }
    }

    @Test
    void should_wrap_null_and_boundary_values() throws Throwable {
        // U7：null 与边界值（空串、0、min/max、最小日期等）的 wrap 契约 —— null 保持 null、边界值不丢值不异常
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        TapTable table = buildTapTable();
        // a. 全 null 行（主键除外）：null 值不经过 codec，wrap 后保持 null
        Map<String, Object> nullRow = new HashMap<>();
        nullRow.put(primaryKeyName(), 1L);
        for (TestFieldSpec field : spec.getFields()) {
            if (!field.isPrimaryKey()) {
                nullRow.put(field.getName(), null);
            }
        }
        Map<String, Object> wrappedNull = wrapRow(nullRow, table);
        for (TestFieldSpec field : spec.getFields()) {
            if (field.isPrimaryKey()) {
                continue;
            }
            Object v = wrappedNull.get(field.getName());
            if (v != null) {
                assertNull(((TapValue<?, ?>) v).getValue(),
                        "field[" + field.getName() + "] null should keep null wrapped value");
            }
        }
        // b. 边界值行：0/空串/min/max/最小日期等不丢值、不异常
        Map<String, Object> boundaryRow = new HashMap<>();
        boundaryRow.put(primaryKeyName(), 2L);
        for (TestFieldSpec field : spec.getFields()) {
            if (!field.isPrimaryKey()) {
                boundaryRow.put(field.getName(), boundaryValue(field.getTestDataType()));
            }
        }
        Map<String, Object> wrappedBoundary = wrapRow(boundaryRow, table);
        for (TestFieldSpec field : spec.getFields()) {
            if (field.isPrimaryKey()) {
                continue;
            }
            String name = field.getName();
            Object plain = boundaryRow.get(name);
            if (plain == null) {
                continue;
            }
            Object wrappedValue = wrappedBoundary.get(name);
            if (wrappedValue instanceof TapValue) {
                TapValueAssert.assertValueClass(field.getTestDataType(), (TapValue<?, ?>) wrappedValue,
                        expectedTapValueClasses(field.getTestDataType()), name);
                TapValueAssert.assertValueEquals(field.getTestDataType(), plain, (TapValue<?, ?>) wrappedValue, name);
            } else {
                assertFalse(expectsWrap(field.getTestDataType()),
                        "field[" + name + "] should be wrapped into TapValue for type " + field.getTestDataType());
                RecordAssert.assertEquals(field.getTestDataType(), plain, wrappedValue, name);
            }
        }
    }

    @Test
    void should_wrap_special_value_samples() throws Throwable {
        // U8：连接器特有特殊值（ObjectId/Decimal128/JSON 等）wrap 后必须被连接器自定义 codec 识别
        // （不得落在 TapRawValue 兜底），unwrap 后还原为可消费的普通值
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        Map<String, Object> samples = specialValueSamples();
        assumeTrue(!samples.isEmpty(), "no special value samples, skip.");
        // 纯内存 TapTable：样本字段统一为 string 类型（仅验证特殊值的 wrap/unwrap，不落库）
        TapTable table = TapSimplify.table(spec.getTableName() + "_special");
        for (String name : samples.keySet()) {
            table.add(TapSimplify.field(name, "string").tapType(TapSimplify.tapString()));
        }
        Map<String, Object> row = new LinkedHashMap<>(samples);
        Map<String, Object> wrapped = wrapRow(row, table);
        for (String name : samples.keySet()) {
            Object wrappedValue = wrapped.get(name);
            assertNotNull(wrappedValue, "field[" + name + "] should be wrapped into TapValue");
            assertTrue(wrappedValue instanceof TapValue,
                    "field[" + name + "] special value should be wrapped by connector codec, got plain: " + wrappedValue);
            TapValue<?, ?> tapValue = (TapValue<?, ?>) wrappedValue;
            assertFalse(tapValue instanceof TapRawValue,
                    "field[" + name + "] special value should be recognized by connector codec, got TapRawValue fallback");
            // unwrap 还原为普通值（输入 map 原地解包）
            Map<String, Object> single = new HashMap<>();
            single.put(name, tapValue);
            engineCodecsFilterManager.transformFromTapValueMap(single);
            assertNotNull(single.get(name), "field[" + name + "] should unwrap to non-null plain value");
        }
    }

    @Test
    void should_wrap_unwrap_nested_types() throws Throwable {
        // U9：嵌套 MAP/ARRAY 的 wrap/unwrap 契约 —— 包装为 TapMapValue/TapArrayValue（内部递归处理），
        // unwrap 后语义等价（容忍连接器输出 JSON 字符串形态）
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        assumeTrue(enableNestedTypes(), "nested types not enabled, skip.");
        Map<String, Object> mapValue = new LinkedHashMap<>();
        mapValue.put("k1", 1);
        mapValue.put("k2", "v2");
        mapValue.put("k3", Arrays.asList(1, 2, 3));
        List<Object> arrayValue = new ArrayList<>();
        arrayValue.add(1);
        arrayValue.add("two");
        arrayValue.add(true);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("n", 1);
        arrayValue.add(nested);

        // wrap 会原地递归处理嵌套值（AllLayerMapIterator），先浅拷贝期望值
        Map<String, Object> mapExpected = new LinkedHashMap<>(mapValue);
        List<Object> arrayExpected = new ArrayList<>(arrayValue);

        // 纯内存 TapTable（不落库）：追加 c_map/c_array 嵌套字段
        TapTable table = buildTapTable();
        table.add(TapSimplify.field("c_map", "map").tapType(TapSimplify.tapMap()));
        table.add(TapSimplify.field("c_array", "array").tapType(TapSimplify.tapArray()));

        Map<String, Object> row = new HashMap<>();
        row.put(primaryKeyName(), 1L);
        row.put("c_map", mapValue);
        row.put("c_array", arrayValue);

        Map<String, Object> wrapped = wrapRow(row, table);
        TapValue<?, ?> wrappedMap = (TapValue<?, ?>) wrapped.get("c_map");
        TapValue<?, ?> wrappedArray = (TapValue<?, ?>) wrapped.get("c_array");
        TapValueAssert.assertValueClass(TestDataType.MAP, wrappedMap,
                expectedTapValueClasses(TestDataType.MAP), "c_map");
        TapValueAssert.assertValueClass(TestDataType.ARRAY, wrappedArray,
                expectedTapValueClasses(TestDataType.ARRAY), "c_array");
        TapValueAssert.assertValueEquals(TestDataType.MAP, mapExpected, wrappedMap, "c_map");
        TapValueAssert.assertValueEquals(TestDataType.ARRAY, arrayExpected, wrappedArray, "c_array");

        // unwrap：连接器可能输出结构化 Map/List 或 JSON 字符串，统一语义比较
        Map<String, Object> unwrapped = unwrapRow(wrapped);
        TapValueAssert.assertNestedEquals(mapExpected, parseJsonIfNeeded(unwrapped.get("c_map")), "c_map");
        TapValueAssert.assertNestedEquals(arrayExpected, parseJsonIfNeeded(unwrapped.get("c_array")), "c_array");
    }

    @Test
    @UnderTest(value = "batchRead", requiresVerifier = true)
    void should_validate_wrap_types_against_spec_through_database() throws Throwable {
        // U10：经库全链路 spec 契约验证 —— 生成数据旁路直连写入（不经 connector writeRecord，
        // 规避写侧 codec 缺陷干扰读侧验证），batchRead 读回后经 codecsFilterManager 统一转换，
        // 断言各字段转换结果类型族符合 connector spec.json dataTypes 声明的规则
        // （以 discoverSchema 返回的方言 dataType 为解析键，与引擎 TableFieldTypesGenerator.autoFill 同源；
        // spec 未声明的字段类型直接失败暴露缺口）
        assumeTrue(engineCodecsFilterManager != null, "no codec registry, skip.");
        prepareData(defaultRecordCount());
        TapTable table = buildTapTable();
        // 数据库真实 schema：字段方言 dataType 是 spec.json 声明解析的事实来源（discover 返回真实方言）
        TapTable discovered = discoverTable();
        assertNotNull(discovered, "discoverSchema should return the created table");
        List<Map<String, Object>> readBack = batchReadAll(table);
        assertFalse(readBack.isEmpty(), "batchRead should return inserted rows");
        Map<String, TapField> discoveredFields = discovered.getNameFieldMap();
        for (Map<String, Object> row : readBack) {
            Map<String, Object> wrapped = wrapRow(row, table);
            for (TestFieldSpec field : spec.getFields()) {
                String name = field.getName();
                Object plain = row.get(name);
                if (plain == null) {
                    continue;
                }
                TapField discoveredField = discoveredFields.get(name);
                assertNotNull(discoveredField, "field[" + name + "] should be present in discovered schema");
                // spec.json 声明规则：方言 dataType → TapType（spec 未声明 → 失败，暴露 spec 缺口）
                TapType declaredTapType = typeResolver.resolve(discoveredField.getDataType());
                assertNotNull(declaredTapType,
                        "field[" + name + "] dataType '" + discoveredField.getDataType()
                                + "' is not declared in connector spec.json");
                Object wrappedValue = wrapped.get(name);
                if (wrappedValue instanceof TapValue) {
                    TapValueAssert.assertValueClass(field.getTestDataType(), (TapValue<?, ?>) wrappedValue,
                            expectedTapValueClassesForTapType(declaredTapType), name);
                    TapValueAssert.assertValueEquals(field.getTestDataType(), plain, (TapValue<?, ?>) wrappedValue, name);
                } else {
                    assertFalse(wrapsByTapType(declaredTapType),
                            "field[" + name + "] should be wrapped into TapValue for spec type "
                                    + declaredTapType.getClass().getSimpleName());
                }
            }
        }
    }

    // ===================== J. 能力覆盖校验（原则 3：声明式能力） =====================

    /**
     * 原则 3（声明式能力）框架级校验用例，所有子类自动继承执行：
     * <ul>
     *   <li>a. connector 声明的必实现能力（{@link #requiredCapabilities()}）必须被实现
     *       —— 确保 connector 不遗漏必实现接口；</li>
     *   <li>b. connector 已实现的能力（含未声明已实现）必须被至少一个用例覆盖
     *       —— 确保实现功能全部被测到，杜绝“实现了但无人测试”。</li>
     * </ul>
     */
    @Test
    void should_all_capabilities_implemented_and_covered() throws Throwable {
        Set<String> declared = requiredCapabilities();
        Set<String> implemented = implementedCapabilities();
        Set<String> covered = coveredCapabilities();
        log(context, "[IT] declared required capabilities: {}", declared);
        log(context, "[IT] implemented capabilities: {}", implemented);
        log(context, "[IT] covered capabilities ({} test cases): {}", covered.size(), covered);

        // 原则 3a：声明的必实现接口必须被实现（connector 不遗漏必实现接口）
        Set<String> declaredButNotImplemented = new LinkedHashSet<>(declared);
        declaredButNotImplemented.removeAll(implemented);
        assertTrue(declaredButNotImplemented.isEmpty(),
                getClass().getSimpleName() + " declares required capabilities but connector does not implement: "
                        + declaredButNotImplemented);

        // 原则 3b：已实现接口（含未声明已实现）必须被用例覆盖（确保实现功能正确）
        Set<String> implementedButNotCovered = new LinkedHashSet<>(implemented);
        implementedButNotCovered.removeAll(covered);
        assertTrue(implementedButNotCovered.isEmpty(),
                getClass().getSimpleName() + " implements capabilities without test coverage: "
                        + implementedButNotCovered
                        + "; add @UnderTest test cases or remove the unused registration");
    }
}
