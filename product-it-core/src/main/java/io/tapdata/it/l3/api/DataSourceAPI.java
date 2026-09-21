package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmPage;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.http.HttpMethod;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源（连接）API —— 对应 TM {@code DataSourceController}（base：{@code /api/v2/ds}，别名 {@code /api/Connections}）。
 * <p>
 * {@code DataSourceDto} 位于重量级 {@code tm-api} Web 模块，框架不直接依赖；建/改连接的请求体以
 * JSON 模板字符串或 {@link Map}（{@link DataSourcePayload} 辅助构造）传入，读侧统一以 {@code Map} 承载。
 */
public class DataSourceAPI extends AbstractTmApi {

    private static final String BASE = "/v2/ds";

    private static final TypeReference<TmResponse<Map<String, Object>>> RESP_MAP =
            new TypeReference<TmResponse<Map<String, Object>>>() {
            };
    private static final TypeReference<TmResponse<TmPage<Map<String, Object>>>> RESP_PAGE_MAP =
            new TypeReference<TmResponse<TmPage<Map<String, Object>>>>() {
            };
    private static final TypeReference<TmResponse<Object>> RESP_VOID =
            new TypeReference<TmResponse<Object>>() {
            };

    public DataSourceAPI(TmApiContext ctx) {
        super(ctx);
    }

    /**
     * 创建数据源（{@code POST /v2/ds}）。
     *
     * @param dataSource 连接定义（JSON 模板字符串或 {@link Map}/{@link DataSourcePayload}）
     * @return 新建数据源 id
     */
    public String create(Object dataSource) {
        Map<String, Object> data = data(HttpMethod.POST, BASE, dataSource, RESP_MAP);
        return extractId(data);
    }

    /** 更新数据源（{@code PATCH /v2/ds/{id}}）。 */
    public String update(String id, Object dataSource) {
        Map<String, Object> data = data(HttpMethod.PATCH, BASE + "/" + id, dataSource, RESP_MAP);
        return extractId(data);
    }

    /** 查询数据源详情（{@code GET /v2/ds/{id}}）。 */
    public Map<String, Object> findById(String id) {
        return data(HttpMethod.GET, BASE + "/" + id, null, RESP_MAP);
    }

    /**
     * 查询数据源详情但不带回 schema（{@code GET /v2/ds/{id}?noSchema=true}）。
     * 轮询连接状态/建模进度时用此方法，避免每次传输整库表结构。
     */
    public Map<String, Object> findByIdNoSchema(String id) {
        return data(HttpMethod.GET, withQuery(BASE + "/" + id, "noSchema", "true"), null, RESP_MAP);
    }

    /**
     * 重提交测连 + 加载模型（{@code PATCH /v2/ds/{id}} 带 {@code submit=true}）。
     * <p>注意：TM 侧 {@code DataSourceServiceImpl.sendTestConnection} 首行
     * {@code if (!submit) return;}——只有 {@code submit=true} 才会向 engine 下发 testConnection
     * 指令并自动加载模型，因此建连接的请求体也必须带 {@code submit=true}。
     */
    public String testConnection(String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("submit", true);
        return update(id, body);
    }

    /**
     * 读取测连状态（{@code status}：{@code ready}/{@code testing}/{@code invalid}/{@code untest} 等）。
     * 本版 TM 已改为 {@code ready}（可用）和 {@code invalid}（测连失败）口径。
     */
    public String status(String id) {
        return stringValue(findByIdNoSchema(id), "status");
    }

    /**
     * 读取模型加载状态（{@code loadFieldsStatus}：{@code loading}/{@code finished}/{@code invalid}/{@code error}）。
     * 返回 {@code null} 表示尚未触发加载（建连接时未带 {@code submit=true}，或测连失败导致未进入建模）。
     */
    public String loadFieldsStatus(String id) {
        return stringValue(findByIdNoSchema(id), "loadFieldsStatus");
    }

    /**
     * 读取连接器能力清单（{@code capabilities}）：由引擎测连/建模时回写到连接文档，
     * 建任务时需要把它带入节点 {@code attrs.capabilities}（与 Web 端建任务口径一致）。
     */
    @SuppressWarnings("unchecked")
    public List<Object> capabilities(String id) {
        Object capabilities = findByIdNoSchema(id).get("capabilities");
        return capabilities instanceof List ? (List<Object>) capabilities : Collections.emptyList();
    }

    /** 读取连接名（任务节点 {@code name}/{@code attrs.connectionName} 用）。 */
    public String name(String id) {
        return stringValue(findByIdNoSchema(id), "name");
    }

    /** 读取连接用途（{@code connection_type}：{@code source}/{@code target}/{@code source_and_target}）。 */
    public String connectionType(String id) {
        String type = stringValue(findByIdNoSchema(id), "connection_type");
        return type != null ? type : stringValue(findByIdNoSchema(id), "connectionType");
    }

    /** 列表查询（{@code GET /v2/ds?filter=}）。 */
    public List<Map<String, Object>> list(TmFilter filter) {
        String path = withQuery(BASE, "filter", filter == null ? null : filter.toJson());
        TmPage<Map<String, Object>> page = data(HttpMethod.GET, path, null, RESP_PAGE_MAP);
        return page == null ? Collections.emptyList() : page.getItems();
    }

    /** 删除数据源（{@code DELETE /v2/ds/{id}}）。 */
    public void delete(String id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null, RESP_VOID);
    }

    private static String stringValue(Map<String, Object> data, String field) {
        if (data == null) {
            return null;
        }
        Object value = data.get(field);
        return value == null ? null : String.valueOf(value);
    }
}
