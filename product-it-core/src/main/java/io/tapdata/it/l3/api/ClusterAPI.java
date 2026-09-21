package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmPage;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.http.HttpMethod;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 集群/引擎节点 API —— 对应 TM {@code ClusterStateController}（base：{@code /api/clusterStates}）。
 * <p>
 * 首批骨架：提供集群节点状态列表，后续按用例扩展（如分布式 HA 模式下校验在线引擎数、节点角色）。
 */
public class ClusterAPI extends AbstractTmApi {

    private static final String BASE = "/clusterStates";

    private static final TypeReference<TmResponse<TmPage<Map<String, Object>>>> RESP_PAGE_MAP =
            new TypeReference<TmResponse<TmPage<Map<String, Object>>>>() {
            };

    public ClusterAPI(TmApiContext ctx) {
        super(ctx);
    }

    /** 集群节点状态列表（{@code GET /clusterStates?filter=}）。 */
    public List<Map<String, Object>> list(TmFilter filter) {
        String path = withQuery(BASE, "filter", filter == null ? null : filter.toJson());
        TmPage<Map<String, Object>> page = data(HttpMethod.GET, path, null, RESP_PAGE_MAP);
        return page == null ? Collections.emptyList() : page.getItems();
    }
}
