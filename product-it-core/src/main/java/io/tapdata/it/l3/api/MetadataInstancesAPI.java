package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmPage;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 元数据实例 API —— 对应 TM {@code MetadataInstancesController}（base：{@code /api/MetadataInstances}）。
 * <p>
 * 用途：连接建模（loadFields）完成后，从元数据库侧核验「表是否被发现、字段是否建模成功」，
 * 这是 L3 用例中「产品视角」的结构断言入口（数据内容正确性仍走旁路验证器，见
 * {@code tapdata-it-common} 的 {@code ConnectorVerifier}）。
 * <p>
 * {@code MetadataInstancesDto} 含 {@code fields}/{@code index} 等复杂结构，读侧统一以 {@code Map} 承载。
 */
public class MetadataInstancesAPI extends AbstractTmApi {

    private static final String BASE = "/MetadataInstances";

    /** TM 元数据表类型：关系库表/视图、MongoDB 集合。 */
    private static final List<String> TABLE_META_TYPES = Arrays.asList("table", "view", "collection");

    private static final TypeReference<TmResponse<TmPage<Map<String, Object>>>> RESP_PAGE_MAP =
            new TypeReference<TmResponse<TmPage<Map<String, Object>>>>() {
            };
    private static final TypeReference<TmResponse<Map<String, Object>>> RESP_MAP =
            new TypeReference<TmResponse<Map<String, Object>>>() {
            };

    public MetadataInstancesAPI(TmApiContext ctx) {
        super(ctx);
    }

    /** 按条件分页查询元数据（{@code GET /MetadataInstances?filter=}）。 */
    public List<Map<String, Object>> list(TmFilter filter) {
        String path = withQuery(BASE, "filter", filter == null ? null : filter.toJson());
        TmPage<Map<String, Object>> page = data(HttpMethod.GET, path, null, RESP_PAGE_MAP);
        return page == null ? Collections.emptyList() : page.getItems();
    }

    /** 查询某连接下已发现的全部表/集合元数据。 */
    public List<Map<String, Object>> listByConnection(String connectionId) {
        return list(tableFilter(connectionId, null));
    }

    /** 查询某连接下已发现的表名列表（用于校验「表发现」结果）。 */
    public List<String> listTableNames(String connectionId) {
        return listByConnection(connectionId).stream()
                .map(row -> tableName(row))
                .filter(name -> name != null)
                .collect(Collectors.toList());
    }

    /** 按连接 + 表名精确查询单表元数据；不存在返回 {@code null}。 */
    public Map<String, Object> findByTable(String connectionId, String tableName) {
        List<Map<String, Object>> rows = list(tableFilter(connectionId, tableName));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 某连接下该表是否已被建模发现。 */
    public boolean tableExists(String connectionId, String tableName) {
        return findByTable(connectionId, tableName) != null;
    }

    /** 单表元数据的字段名列表。 */
    @SuppressWarnings("unchecked")
    public List<String> fieldNames(Map<String, Object> metadata) {
        if (metadata == null) {
            return Collections.emptyList();
        }
        Object fields = metadata.get("fields");
        if (!(fields instanceof List)) {
            return Collections.emptyList();
        }
        return ((List<Object>) fields).stream()
                .filter(f -> f instanceof Map)
                .map(f -> ((Map<String, Object>) f).get("name"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toList());
    }

    /** 查询单表元数据详情（{@code GET /MetadataInstances/{id}}）。 */
    public Map<String, Object> findById(String id) {
        return data(HttpMethod.GET, BASE + "/" + id, null, RESP_MAP);
    }

    // ---- 内部：filter 口径 ----

    private static TmFilter tableFilter(String connectionId, String tableName) {
        TmFilter filter = TmFilter.where("source.id", connectionId)
                .and("meta_type", "in", TABLE_META_TYPES)
                .and("is_deleted", false);
        if (tableName != null) {
            // original_name = 数据源中的真实表名；name = TM 内部 qualified 名，故按前者匹配
            filter.and("original_name", tableName);
        }
        return filter.limit(200);
    }

    private static String tableName(Map<String, Object> metadata) {
        Object original = metadata.get("originalName");
        if (original == null) {
            original = metadata.get("original_name");
        }
        if (original == null) {
            original = metadata.get("name");
        }
        return original == null ? null : String.valueOf(original);
    }
}
