package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmApiException;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.http.HttpMethod;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源类型（connector 定义）API —— 对应 TM {@code DataSourceDefinitionController}
 * （base：{@code /api/DatabaseTypes}）。
 * <p>
 * 该 Controller 返回的 {@code DataSourceTypeDto} 位于 {@code tm-api} Web 模块，框架以
 * {@code Map<String,Object>} 承载；本类的核心价值是提供 <b>pdkHash 解析</b>：
 * 新建连接（{@link DataSourceAPI#create}）与任务节点 {@code attrs.pdkHash} 都依赖它，
 * 而它<b>只能</b>来自此处（{@code GET /v2/ds/databaseTypes} 是对已建连接的聚合，建连接前取不到）。
 * <p>
 * 对齐 A5（connector jar 由 TM 首启默认上传）：用例无需上传 jar，但可用
 * {@link #pdkHash(String)} / {@link #isRegistered(String)} 做「connector 是否已注册」预检。
 */
public class DataSourceDefinitionAPI extends AbstractTmApi {

    private static final String BASE = "/DatabaseTypes";

    private static final TypeReference<TmResponse<List<Map<String, Object>>>> RESP_LIST_MAP =
            new TypeReference<TmResponse<List<Map<String, Object>>>>() {
            };
    private static final TypeReference<TmResponse<Map<String, Object>>> RESP_MAP =
            new TypeReference<TmResponse<Map<String, Object>>>() {
            };

    public DataSourceDefinitionAPI(TmApiContext ctx) {
        super(ctx);
    }

    /** 按条件查询数据源类型列表（{@code GET /DatabaseTypes?filter=}）。 */
    public List<Map<String, Object>> list(TmFilter filter) {
        String path = withQuery(BASE, "filter", filter == null ? null : filter.toJson());
        List<Map<String, Object>> data = data(HttpMethod.GET, path, null, RESP_LIST_MAP);
        return data == null ? Collections.emptyList() : data;
    }

    /** 全量数据源类型列表（TM 侧一次返回全部已注册 connector）。 */
    public List<Map<String, Object>> listAll() {
        return list(null);
    }

    /** 按 pdkHash 查询单个数据源定义（{@code GET /DatabaseTypes/pdkHash/{pdkHash}}）。 */
    public Map<String, Object> getByPdkHash(String pdkHash) {
        return data(HttpMethod.GET, BASE + "/pdkHash/" + pdkHash, null, RESP_MAP);
    }

    /**
     * 按库类型解析数据源定义：在 {@link #listAll()} 结果中依次匹配 {@code name} / {@code type} /
     * {@code pdkId} / {@code realName}（大小写无关，如 {@code "mysql"}、{@code "mongodb"}）。
     *
     * @param databaseType 库类型标识
     * @return 匹配的定义；未找到返回 {@code null}
     */
    public Map<String, Object> find(String databaseType) {
        if (databaseType == null) {
            return null;
        }
        String want = databaseType.toLowerCase(Locale.ROOT);
        for (Map<String, Object> item : listAll()) {
            if (item == null) {
                continue;
            }
            for (String key : new String[]{"name", "type", "pdkId", "realName"}) {
                Object v = item.get(key);
                if (v != null && want.equals(String.valueOf(v).toLowerCase(Locale.ROOT))) {
                    return item;
                }
            }
        }
        return null;
    }

    /** 该库类型的 connector 是否已在 TM 注册（A5 预检）。 */
    public boolean isRegistered(String databaseType) {
        return find(databaseType) != null;
    }

    /**
     * 解析库类型对应的 {@code pdkHash}。
     *
     * @throws TmApiException 未注册该库类型（通常意味着 connector jar 未上传/未加载）
     */
    public String pdkHash(String databaseType) {
        Map<String, Object> definition = find(databaseType);
        Object pdkHash = definition == null ? null : definition.get("pdkHash");
        if (pdkHash == null || String.valueOf(pdkHash).isEmpty()) {
            throw new TmApiException("Connector of database type [" + databaseType
                    + "] not registered in TM (no pdkHash); check TM connector upload");
        }
        return String.valueOf(pdkHash);
    }

    /** 解析库类型对应的 {@code pdkId}（部分连接配置项校验会引用）。 */
    public String pdkId(String databaseType) {
        Map<String, Object> definition = find(databaseType);
        Object pdkId = definition == null ? null : definition.get("pdkId");
        return pdkId == null ? null : String.valueOf(pdkId);
    }
}
