package io.tapdata.it.l3.base;

import com.tapdata.tm.commons.task.dto.TaskDto;
import io.tapdata.it.l3.api.DataSourceAPI;
import io.tapdata.it.l3.api.MetadataInstancesAPI;
import io.tapdata.it.l3.api.TaskAPI;
import io.tapdata.it.l3.api.model.TmApiException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * TM 异步语义等待器：把「提交后轮询直到终态」这一 L3 通用模式内聚到框架，用例只描述期望结果。
 * <p>
 * 覆盖三个异步链路点：
 * <ol>
 *   <li>连接 {@code submit=true} 后的引擎测连与模型加载（{@link #connectionLoaded}）；</li>
 *   <li>模型加载完成后表/字段在元数据库可见（{@link #tableDiscovered}）；</li>
 *   <li>任务启动后运行状态收敛（{@link #taskReached}）。</li>
 * </ol>
 * 失败态立即抛错并附带服务端可见的错误上下文，避免用 {@code Thread.sleep} + 断言造成的假通过与长等待。
 */
public final class TmAwaits {

    /** 连接建模终态（{@code loadFieldsStatus}）。 */
    public static final Set<String> LOAD_FIELDS_DONE = unmodifiableSet("finished", "invalid", "error");

    /**
     * 任务状态取值以 {@link TaskDto} 常量为准（与 TM {@code TaskStatusEnum} 同源）：
     * <ul>
     *   <li>{@link #TASK_SUCCESS}——全量同步（{@code type=initial_sync}）跑完进入 {@code complete}；</li>
     *   <li>{@link #TASK_IN_PROGRESS}——启动/调度/运行中的过渡态，继续轮询；</li>
     *   <li>{@link #TASK_FAILURE}——错误、被停止、调度/重置/删除失败，立即判失败。</li>
     * </ul>
     * 注：{@code schedule_failed}/{@code paused} 在 TM 列表接口会被映射为 {@code error}/{@code stop}，
     * 这里按原始存储值一并列出，避免直读任务文档时漏判。
     */
    public static final Set<String> TASK_SUCCESS = unmodifiableSet(TaskDto.STATUS_COMPLETE);
    public static final Set<String> TASK_IN_PROGRESS = unmodifiableSet(
            TaskDto.STATUS_EDIT, TaskDto.STATUS_WAIT_START, TaskDto.STATUS_SCHEDULING,
            TaskDto.STATUS_WAIT_RUN, TaskDto.STATUS_RUNNING, TaskDto.STATUS_STOPPING);
    public static final Set<String> TASK_FAILURE = unmodifiableSet(
            TaskDto.STATUS_ERROR, TaskDto.STATUS_STOP, TaskDto.STATUS_SCHEDULE_FAILED,
            TaskDto.STATUS_RENEW_FAILED, TaskDto.STATUS_DELETE_FAILED);
    /** 任务全部终态。 */
    public static final Set<String> TASK_TERMINAL = union(TASK_SUCCESS, TASK_FAILURE);

    /** 任务成功终态字面量（供用例断言/日志直接引用）。 */
    public static final String STATUS_COMPLETE = TaskDto.STATUS_COMPLETE;
    public static final String STATUS_RUNNING = TaskDto.STATUS_RUNNING;
    public static final String STATUS_ERROR = TaskDto.STATUS_ERROR;

    private static final int MAX_DIAGNOSTIC_FIELDS = 6;

    private TmAwaits() {
    }

    /**
     * 等待连接模型加载进入终态。TM 在 {@code POST /v2/ds} 带 {@code submit=true} 后自动测连并加载模型，
     * 期间 {@code loadFieldsStatus} 为 {@code loading}。
     *
     * @param api          {@link DataSourceAPI}
     * @param connectionId 连接 id
     * @param timeoutSec   超时秒数
     * @param intervalMs   轮询间隔毫秒
     * @return 终态 {@code loadFieldsStatus}
     * @throws AssertionError 超时，或终态为 {@code invalid}/{@code error}（模型加载失败）
     */
    public static String connectionLoaded(DataSourceAPI api, String connectionId, long timeoutSec, long intervalMs) {
        String loadStatus = until(() -> {
                    String status = api.status(connectionId);
                    if ("invalid".equals(status) || "error".equals(status)) {
                        throw new AssertionError("Schema loading of connection " + connectionId
                                + " failed early (status=" + status + "); check connection config / connector / agent availability");
                    }
                    return api.loadFieldsStatus(connectionId);
                },
                LOAD_FIELDS_DONE::contains, timeoutSec, intervalMs,
                "connection " + connectionId + " loadFieldsStatus terminal");
        if (!"finished".equals(loadStatus)) {
            throw new AssertionError("Schema loading of connection " + connectionId + " ended with status=" + loadStatus
                    + "; check connection config / connector / agent availability");
        }
        return loadStatus;
    }

    /**
     * 等待某连接下指定表被元数据发现（建模结果可见）。
     *
     * @param api          {@link MetadataInstancesAPI}
     * @param connectionId 连接 id
     * @param tableNames   期望出现的表名
     * @param timeoutSec   超时秒数
     * @param intervalMs   轮询间隔毫秒
     */
    public static void tableDiscovered(MetadataInstancesAPI api, String connectionId, List<String> tableNames,
                                       long timeoutSec, long intervalMs) {
        if (tableNames == null || tableNames.isEmpty()) {
            return;
        }
        for (String table : tableNames) {
            until(() -> api.tableExists(connectionId, table), Boolean.TRUE::equals, timeoutSec, intervalMs,
                    "metadata of connection " + connectionId + " table " + table + " discovered");
        }
    }

    /**
     * 等待任务进入指定终态集合之一。
     *
     * @param api         {@link TaskAPI}
     * @param taskId      任务 id
     * @param expect      期望终态（如 {@link #TASK_SUCCESS}）
     * @param reject      视为失败的终态（如 {@link #TASK_FAILURE}）；命中标 {@link AssertionError}
     * @param timeoutSec  超时秒数
     * @param intervalMs  轮询间隔毫秒
     * @return 命中的终态
     */
    public static String taskReached(TaskAPI api, String taskId, Set<String> expect, Set<String> reject,
                                     long timeoutSec, long intervalMs) {
        final Map<String, Object>[] lastSeen = new Map[1];
        String status = until(() -> {
            Map<String, Object> task = api.findById(taskId);
            lastSeen[0] = task;
            return task == null ? null : str(task.get("status"));
        }, s -> s != null && (expect.contains(s) || (reject != null && reject.contains(s))),
                timeoutSec, intervalMs, "task " + taskId + " terminal status in " + expect);
        if (reject != null && reject.contains(status)) {
            throw new AssertionError("Task " + taskId + " reached failure status=" + status
                    + diagnostic(lastSeen[0]));
        }
        return status;
    }

    /**
     * 等待任务进入 {@code complete}：过渡态（{@link #TASK_IN_PROGRESS}）继续轮询，
     * 命中 {@link #TASK_FAILURE} 即刻抛错并附带服务端诊断字段。
     */
    public static String taskCompleted(TaskAPI api, String taskId, long timeoutSec, long intervalMs) {
        return taskReached(api, taskId, TASK_SUCCESS, TASK_FAILURE, timeoutSec, intervalMs);
    }

    /** 等待任务进入运行态（{@code running}），用于需要「跑起来后再注入增量数据」的用例。 */
    public static String taskRunning(TaskAPI api, String taskId, long timeoutSec, long intervalMs) {
        return taskReached(api, taskId, unmodifiableSet(TaskDto.STATUS_RUNNING), TASK_FAILURE, timeoutSec, intervalMs);
    }

    // ---- 通用轮询 ----

    /**
     * 通用轮询：反复取值直到 {@code done} 命中，或超时抛 {@link AssertionError}。
     * 取值过程中的 {@link TmApiException}（如 TM 短暂 503）视为「本轮未命中」继续重试。
     */
    public static <T> T until(Supplier<T> probe, java.util.function.Predicate<T> done,
                              long timeoutSec, long intervalMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        T last = null;
        RuntimeException lastError = null;
        while (true) {
            try {
                last = probe.get();
                lastError = null;
                if (done.test(last)) {
                    return last;
                }
            } catch (RuntimeException e) {
                lastError = e;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            sleep(Math.max(200L, intervalMs));
        }
        String suffix = lastError == null ? " (last value=" + last + ")" : " (last error=" + lastError.getMessage() + ")";
        throw new AssertionError("Timeout after " + timeoutSec + "s waiting for " + what + suffix,
                lastError);
    }

    /** 提取任务详情中的排障字段，拼成一句诊断信息（失败断言用）。 */
    private static String diagnostic(Map<String, Object> task) {
        if (task == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : Arrays.asList("errorMessage", "errorMsg", "message", "subStatus", "progress",
                "syncStatus", "currentSyncTime", "offsetMax")) {
            Object v = task.get(key);
            if (v != null && sb.length() < 800) {
                sb.append(' ').append(key).append('=').append(v).append(';');
            }
        }
        return sb.length() == 0 ? "" : " |" + sb;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Set<String> unmodifiableSet(String... values) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return java.util.Collections.unmodifiableSet(all);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Await interrupted", e);
        }
    }
}
