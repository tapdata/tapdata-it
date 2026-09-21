package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmPage;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.http.HttpMethod;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 校验（Inspect）API —— 对应 TM {@code InspectController}（base：{@code /api/Inspects}）。
 * <p>
 * 首批骨架：仅提供列表/删除，后续按用例扩展具体语义方法（如任务保存前的表结构校验结果查询）。
 */
public class InspectAPI extends AbstractTmApi {

    private static final String BASE = "/Inspects";

    private static final TypeReference<TmResponse<TmPage<Map<String, Object>>>> RESP_PAGE_MAP =
            new TypeReference<TmResponse<TmPage<Map<String, Object>>>>() {
            };
    private static final TypeReference<TmResponse<Object>> RESP_VOID =
            new TypeReference<TmResponse<Object>>() {
            };

    public InspectAPI(TmApiContext ctx) {
        super(ctx);
    }

    /** 列表查询（{@code GET /Inspects?filter=}）。 */
    public List<Map<String, Object>> list(TmFilter filter) {
        String path = withQuery(BASE, "filter", filter == null ? null : filter.toJson());
        TmPage<Map<String, Object>> page = data(HttpMethod.GET, path, null, RESP_PAGE_MAP);
        return page == null ? Collections.emptyList() : page.getItems();
    }

    /** 删除校验记录（{@code DELETE /Inspects/{id}}）。 */
    public void delete(String id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null, RESP_VOID);
    }
}
