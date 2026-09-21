package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmApiException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TM 列表接口 {@code ?filter=} 查询条件的框架侧轻量构造器（对齐 {@code com.tapdata.tm.base.dto.Filter} 的
 * 线上传输结构：{@code where}/{@code fields}/{@code sort}/{@code limit}/{@code skip}）。
 * <p>
 * 不复用 tm-api 的 {@code Filter}（属 Web 层），仅以 {@link Map} 组装并序列化，与 TM Web 层解耦。
 * 用法：{@code tm.taskApi().list(TmFilter.where("status", "processing").limit(50))}。
 */
public final class TmFilter {

    private final Map<String, Object> node = new LinkedHashMap<>();

    private TmFilter() {
    }

    public static TmFilter create() {
        return new TmFilter();
    }

    /** 以单个等值条件起步：{@code where = {key: value}}。 */
    public static TmFilter where(String key, Object value) {
        TmFilter f = new TmFilter();
        return f.and(key, value);
    }

    /** 追加等值条件 {@code where[key] = value}。 */
    public TmFilter and(String key, Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> where = (Map<String, Object>) node.computeIfAbsent("where", k -> new LinkedHashMap<String, Object>());
        where.put(key, value);
        return this;
    }

    /** 追加操作符条件：{@code where[key] = {op: value}}（如 {@code gt}/{@code in} 等 Mongo 风格）。 */
    public TmFilter and(String key, String op, Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> where = (Map<String, Object>) node.computeIfAbsent("where", k -> new LinkedHashMap<String, Object>());
        Object existing = where.get(key);
        if (existing instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> opMap = (Map<String, Object>) existing;
            opMap.put(op, value);
        } else {
            Map<String, Object> opMap = new LinkedHashMap<>();
            opMap.put(op, value);
            where.put(key, opMap);
        }
        return this;
    }

    /** 指定返回字段：{@code fields[key] = true/false}。 */
    public TmFilter field(String key, boolean include) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) node.computeIfAbsent("fields", k -> new LinkedHashMap<String, Object>());
        fields.put(key, include);
        return this;
    }

    /** 排序：{@code sort[key] = 1(升)/-1(降)}。 */
    public TmFilter sort(String key, int direction) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sort = (Map<String, Object>) node.computeIfAbsent("sort", k -> new LinkedHashMap<String, Object>());
        sort.put(key, direction);
        return this;
    }

    /** 分页上限。 */
    public TmFilter limit(int limit) {
        node.put("limit", limit);
        return this;
    }

    /** 分页跳过。 */
    public TmFilter skip(int skip) {
        node.put("skip", skip);
        return this;
    }

    public boolean isEmpty() {
        return node.isEmpty();
    }

    /** 序列化为 filter JSON 串；空条件返回 {@code null}（调用方据此省略查询参数）。 */
    public String toJson() {
        if (node.isEmpty()) {
            return null;
        }
        try {
            return TmJson.mapper().writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new TmApiException("Serialize TmFilter failed: " + e.getMessage(), null, e);
        }
    }

    /** filter JSON 反解为 Map（便于用例断言/复用），空串返回空 Map。 */
    public static Map<String, Object> parse(String filterJson) {
        if (filterJson == null || filterJson.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        return TmJson.read(filterJson, new TypeReference<Map<String, Object>>() {
        });
    }
}
