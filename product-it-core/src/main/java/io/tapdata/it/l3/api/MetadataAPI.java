package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmPage;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.http.HttpMethod;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 元数据（Model/MetaData）API —— 对应 TM {@code MetadataController}（base：{@code /api/MetaData}）。
 * <p>
 * 首批骨架：提供列表查询，后续按用例扩展（如按连接/表拉取建模元数据、构造 qualifiedName 等）。
 */
public class MetadataAPI extends AbstractTmApi {

    private static final String BASE = "/MetaData";

    private static final TypeReference<TmResponse<TmPage<Map<String, Object>>>> RESP_PAGE_MAP =
            new TypeReference<TmResponse<TmPage<Map<String, Object>>>>() {
            };

    public MetadataAPI(TmApiContext ctx) {
        super(ctx);
    }

    /** 列表查询（{@code GET /MetaData?filter=}）。 */
    public List<Map<String, Object>> list(TmFilter filter) {
        String path = withQuery(BASE, "filter", filter == null ? null : filter.toJson());
        TmPage<Map<String, Object>> page = data(HttpMethod.GET, path, null, RESP_PAGE_MAP);
        return page == null ? Collections.emptyList() : page.getItems();
    }
}
