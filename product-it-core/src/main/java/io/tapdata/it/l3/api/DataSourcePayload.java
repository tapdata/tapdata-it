package io.tapdata.it.l3.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源（连接）请求体的轻量装配件：继承 {@link LinkedHashMap}，因此既能作为
 * {@link DataSourceAPI#create(Object)} 的 {@link Map} 载荷，也能被 {@link TmJson} 直接序列化为
 * TM 期望的连接 JSON，无需依赖位于 {@code tm-api} 的重量级 {@code DataSourceDto}。
 * <p>
 * 典型结构（对齐 TM 连接模型）：
 * <pre>
 * {
 *   "name": "mysql_src",
 *   "source": true,          // 是否为源端连接
 *   "type": "mysql",         // 连接器/库类型
 *   "database_type": "mysql",
 *   "config": { "host": "..", "port": 3306, "database": "..", "user": "..", "password": ".." }
 * }
 * </pre>
 * 用例：{@code DataSourcePayload.source("mysql_src","mysql").config("host", ip).config("port", 3306)}。
 */
public class DataSourcePayload extends LinkedHashMap<String, Object> {

    private DataSourcePayload() {
    }

    public static DataSourcePayload create(String name, String type) {
        DataSourcePayload p = new DataSourcePayload();
        p.put("name", name);
        p.put("type", type);
        p.put("database_type", type);
        return p;
    }

    /** 源端连接（{@code source=true}）。 */
    public static DataSourcePayload source(String name, String type) {
        DataSourcePayload p = create(name, type);
        p.put("source", true);
        return p;
    }

    /** 目标端连接（{@code source=false}）。 */
    public static DataSourcePayload target(String name, String type) {
        DataSourcePayload p = create(name, type);
        p.put("source", false);
        return p;
    }

    /**
     * 旁路专用/元信息键：它们描述"怎么连"或"什么库类型"，属于请求体顶层或测试进程自用，
     * 不属于连接器 {@code config}（PDK 连接属性），构造 TM 请求体时剔除。
     */
    private static final java.util.Set<String> NON_CONFIG_KEYS =
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    "database_type", "type", "connection_type", "name", "jdbcUrl", "url", "_meta"));

    /**
     * 由"一条连接的两用配置"（既用于建 TM 连接、又用于直连旁路验证）构造请求体：
     * <ul>
     *   <li>{@code database_type}（缺省取 {@code type}）提升为顶层 {@code type}/{@code database_type}；</li>
     *   <li>其余键进 {@code config}，{@code port} 归一为整数（环境变量覆盖会把数值变成字符串）；</li>
     *   <li>{@code jdbcUrl}/{@code url} 等旁路专用键不进 {@code config}。</li>
     * </ul>
     *
     * @param name     连接名
     * @param node     连接配置节点（如用例资源 JSON 的 {@code source}/{@code target}）
     * @param databaseType 库类型；为 {@code null} 时从 {@code node} 的 {@code database_type} 读取
     * @param source   是否作为源端连接（{@code connection_type = source/target}）
     */
    public static DataSourcePayload fromNode(String name, Map<String, Object> node, String databaseType, boolean source) {
        Map<String, Object> values = new LinkedHashMap<>(node == null ? Map.of() : node);
        String dbType = databaseType != null ? databaseType
                : String.valueOf(values.getOrDefault("database_type", values.get("type")));
        DataSourcePayload payload = (source ? source(name, dbType) : target(name, dbType))
                .connectionType(source ? "source" : "target")
                .submit(true)
                .updateSchema(true)
                .loadAllTables(true);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (NON_CONFIG_KEYS.contains(entry.getKey())) {
                continue;
            }
            payload.config(entry.getKey(), normalize(entry.getKey(), entry.getValue()));
        }
        return payload;
    }

    /** {@code port} 归一为 int（{@code CONNECTOR_IT_*} 环境覆盖只会给字符串）。 */
    private static Object normalize(String key, Object value) {
        if ("port".equals(key) && value instanceof String && !((String) value).trim().isEmpty()) {
            return Integer.parseInt(((String) value).trim());
        }
        return value;
    }

    /** 旁路验证器可用的配置视图：与 {@link #fromNode} 互补，保留 {@code jdbcUrl}/{@code uri} 等连接键。 */
    public static Map<String, Object> verifierConfig(Map<String, Object> node) {
        return new LinkedHashMap<>(node);
    }

    public DataSourcePayload name(String name) {
        put("name", name);
        return this;
    }

    public DataSourcePayload type(String type) {
        put("type", type);
        put("database_type", type);
        return this;
    }

    public DataSourcePayload source(boolean isSource) {
        put("source", isSource);
        return this;
    }

    /** 设置连接配置项（{@code config.<key>}）。 */
    @SuppressWarnings("unchecked")
    public DataSourcePayload config(String key, Object value) {
        Map<String, Object> config = (Map<String, Object>) computeIfAbsent("config", k -> new LinkedHashMap<String, Object>());
        config.put(key, value);
        return this;
    }

    /** 批量设置连接配置项（直接把用例造数用的连接 JSON 整体作为 {@code config}）。 */
    @SuppressWarnings("unchecked")
    public DataSourcePayload config(Map<String, Object> values) {
        Map<String, Object> config = (Map<String, Object>) computeIfAbsent("config", k -> new LinkedHashMap<String, Object>());
        if (values != null) {
            config.putAll(values);
        }
        return this;
    }

    /** 连接器哈希（来自 {@link DataSourceDefinitionAPI#pdkHash}），TM 据此定位 PDK。 */
    public DataSourcePayload pdkHash(String pdkHash) {
        put("pdkHash", pdkHash);
        put("pdkType", "pdk");
        return this;
    }

    /** 连接用途：{@code source} / {@code target} / {@code source_and_target}。 */
    public DataSourcePayload connectionType(String connectionType) {
        put("connection_type", connectionType);
        return this;
    }

    /** 读取库类型（{@code database_type}）：解析 pdkHash 的依据。 */
    public String databaseType() {
        Object type = get("database_type");
        return type == null ? null : String.valueOf(type);
    }

    /**
     * 是否提交给 engine 做测连 + 模型加载（缺省 {@code false} 时 TM 直接短路返回，模型永不加载）。
     * 用例建连接几乎总应传 {@code true}。语义详见 {@link DataSourceAPI#testConnection}。
     */
    public DataSourcePayload submit(boolean submit) {
        put("submit", submit);
        return this;
    }

    /** 测连通过后是否同步加载（建模）表结构，默认随 {@link #submit(boolean)} 一并置 true。 */
    public DataSourcePayload updateSchema(boolean updateSchema) {
        put("updateSchema", updateSchema);
        return this;
    }

    /** 是否加载库内全量表（{@code false} 时配合 {@code table_filter} 做部分加载）。 */
    public DataSourcePayload loadAllTables(boolean loadAllTables) {
        put("loadAllTables", loadAllTables);
        return this;
    }

    /** 追加任意顶层字段。 */
    public DataSourcePayload put(String key, Object value) {
        super.put(key, value);
        return this;
    }
}
