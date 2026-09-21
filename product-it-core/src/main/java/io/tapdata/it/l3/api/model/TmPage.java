package io.tapdata.it.l3.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页结果的框架侧映射（对齐 {@code com.tapdata.tm.base.dto.Page}）。
 * 仅映射列表接口关心的字段，其余忽略，避免与 TM Web 层耦合。
 *
 * @param <T> 条目类型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmPage<T> {

    private List<T> items = new ArrayList<>();

    private long total;

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }
}
