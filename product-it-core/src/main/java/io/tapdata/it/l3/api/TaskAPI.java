package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmPage;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

/**
 * 任务 API —— 对应 TM {@code TaskController}（base：{@code /api/task}）。
 * <p>
 * 写侧（{@link #save}/{@link #confirm}/{@link #updateById}）请求体既接受 tm-common
 * {@code com.tapdata.tm.commons.task.dto.TaskDto}，也接受版本无关的 <b>JSON 模板字符串</b>
 * （复杂带多态 DAG 的建任务体推荐用模板，框架不臆测服务端节点类型判定）。
 * 读侧统一以 {@code Map<String,Object>} 承载，规避 {@code DAG}/{@code Node} 抽象类型的 Jackson 反序列化风险，
 * 用例通过 {@link #status(String)}、{@link #findById(String)} 提取所需字段。
 */
public class TaskAPI extends AbstractTmApi {

    private static final String BASE = "/task";

    private static final TypeReference<TmResponse<Map<String, Object>>> RESP_MAP =
            new TypeReference<TmResponse<Map<String, Object>>>() {
            };
    private static final TypeReference<TmResponse<TmPage<Map<String, Object>>>> RESP_PAGE_MAP =
            new TypeReference<TmResponse<TmPage<Map<String, Object>>>>() {
            };
    private static final TypeReference<TmResponse<Object>> RESP_VOID =
            new TypeReference<TmResponse<Object>>() {
            };

    public TaskAPI(TmApiContext ctx) {
        super(ctx);
    }

    /**
     * 新建任务（{@code POST /task}）。
     *
     * @param task tm-common {@code TaskDto} 或建任务 JSON 模板字符串
     * @return 新建任务 id
     */
    public String save(Object task) {
        Map<String, Object> data = data(HttpMethod.POST, BASE, task, RESP_MAP);
        return extractId(data);
    }

    /** 更新已存在任务的 DAG（{@code PATCH /task}，请求体需含 {@code id}）。 */
    public String updateById(Object task) {
        Map<String, Object> data = data(HttpMethod.PATCH, BASE, task, RESP_MAP);
        return extractId(data);
    }

    /**
     * 确认任务（保存草稿并生成正式 DAG，{@code PATCH /task/confirm/{id}}）。
     *
     * @param id      任务 id
     * @param task    待确认内容（{@code TaskDto} 或 JSON 模板）
     * @param confirm 是否执行表结构变更确认（对应服务端 {@code confirm} 查询参数）
     * @return 确认后的任务 id
     */
    public String confirm(String id, Object task, boolean confirm) {
        String path = BASE + "/confirm/" + id + (confirm ? "?confirm=true" : "");
        Map<String, Object> data = data(HttpMethod.PATCH, path, task, RESP_MAP);
        return extractId(data);
    }

    /** 确认任务并返回原始响应字符串（用于调试/日志）。 */
    public String confirm(String id, Object task) {
        return ctx.exchange(HttpMethod.PATCH, BASE + "/confirm/" + id, TmJson.write(task));
    }

    /**
     * 确认并启动任务（{@code PATCH /task/confirmStart/{id}}）—— 一次往返完成 confirm + start。
     *
     * @param id      任务 id
     * @param task    任务体（{@code TaskDto} 或 JSON 模板，需与保存后的 DAG 一致）
     * @param confirm 是否执行表结构变更确认
     * @return 任务 id
     */
    public String confirmStart(String id, Object task, boolean confirm) {
        String path = BASE + "/confirmStart/" + id + (confirm ? "?confirm=true" : "");
        Map<String, Object> data = data(HttpMethod.PATCH, path, task, RESP_MAP);
        return extractId(data);
    }

    public String confirmStart(String id, Object task) {
        return confirmStart(id, task, false);
    }

    /** 查询任务详情（{@code GET /task/{id}}），返回原始字段 Map（含 status/dag 等）。 */
    public Map<String, Object> findById(String id) {
        return data(HttpMethod.GET, BASE + "/" + id, null, RESP_MAP);
    }

    /** 读取任务运行状态字符串（{@code status} 字段：processing/error/running/wait/... ）。 */
    public String status(String id) {
        Map<String, Object> task = findById(id);
        return task == null ? null : stringifyId(task.get("status"));
    }

    /** 列表查询（{@code GET /task?filter=}）。 */
    public List<Map<String, Object>> list(TmFilter filter) {
        String path = withQuery(BASE, "filter", filter == null ? null : filter.toJson());
        TmPage<Map<String, Object>> page = data(HttpMethod.GET, path, null, RESP_PAGE_MAP);
        return page == null ? java.util.Collections.emptyList() : page.getItems();
    }

    /** 启动任务（{@code PUT /task/batchStart?taskIds={id}}）。 */
    public String start(String id) {
        String path = BASE + "/batchStart?taskIds=" + id;
        return ctx.exchange(HttpMethod.PUT, path, null);
    }

    /**
     * 便捷：保存并启动一个任务（需求用法 {@code tmApi.taskApi().startTask(taskDto)}）。
     *
     * @param task {@code TaskDto} 或 JSON 模板
     * @return 任务 id
     */
    public String startTask(Object task) {
        String id = save(task);
        start(id);
        return id;
    }

    /** 停止任务（{@code PUT /task/stop/{id}}）。 */
    public void stop(String id) {
        call(HttpMethod.PUT, BASE + "/stop/" + id, null, RESP_VOID);
    }

    /** 删除任务（{@code DELETE /task/{id}}）。 */
    public void delete(String id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null, RESP_VOID);
    }
}
