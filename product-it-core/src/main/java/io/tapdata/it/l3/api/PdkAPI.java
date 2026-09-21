package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDK（connector 插件）API —— 对应 TM {@code PdkController}（base：{@code /api/pdk}）。
 * <p>
 * 设计取向（对齐 A5：connector jar 由 TM 侧提供、首次启动默认上传，engine 运行时自动下载）：
 * 常规 L3 用例 <b>无需</b>显式上传 jar；{@link #uploadJar} 仅用于确保 TM 具备特定 connector 的前置补齐。
 */
public class PdkAPI extends AbstractTmApi {

    private static final String BASE = "/pdk";

    private static final TypeReference<TmResponse<Object>> RESP_OBJECT =
            new TypeReference<TmResponse<Object>>() {
            };

    public PdkAPI(TmApiContext ctx) {
        super(ctx);
    }

    /**
     * 上传 connector jar（{@code POST /pdk/upload/source}，{@code multipart/form-data}）。
     *
     * @param jars        jar 文件资源（表单字段名 {@code file}，可多个）
     * @param sourceJsons 与 jar 对应的 PDK source 描述 JSON（表单字段名 {@code source}，可多个）
     * @param latest      是否标记为最新版本
     */
    public void uploadJar(List<Resource> jars, List<String> sourceJsons, boolean latest) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("file", jars);
        parts.put("source", sourceJsons);
        parts.put("latest", latest);
        String body = ctx.exchangeMultipart(HttpMethod.POST, BASE + "/upload/source", parts);
        assertOk(body, "POST " + BASE + "/upload/source");
    }

    /** 便捷：上传单个 jar 文件 + 其 source 描述。 */
    public void uploadJar(Path jar, String sourceJson, boolean latest) {
        uploadJar(Collections.singletonList(new FileSystemResource(jar.toFile())),
                Collections.singletonList(sourceJson), latest);
    }

    /**
     * 查询指定 PDK 的 jar MD5（{@code GET /pdk/checkMd5/v3}），engine 据此判断是否需重新下载。
     *
     * @param pdkHash        PDK 哈希
     * @param pdkBuildNumber 构建号（可空）
     * @return MD5 字符串；服务端无数据时返回 {@code null}
     */
    public String checkMd5(String pdkHash, Integer pdkBuildNumber) {
        String path = withQuery(BASE + "/checkMd5/v3", "pdkHash", pdkHash);
        if (pdkBuildNumber != null) {
            path = withQuery(path, "pdkBuildNumber", String.valueOf(pdkBuildNumber));
        }
        Object data = data(HttpMethod.GET, path, null, RESP_OBJECT);
        return data == null ? null : String.valueOf(data);
    }

    private static void assertOk(String rawBody, String what) {
        if (rawBody == null || rawBody.trim().isEmpty()) {
            return; // 无体视为成功
        }
        TmResponse<Object> response = TmJson.read(rawBody, RESP_OBJECT);
        if (!response.isSuccess()) {
            throw new io.tapdata.it.l3.api.model.TmApiException(
                    "TM API error [" + response.getCode() + "]: " + response.getMessage() + " @ " + what,
                    response.getCode());
        }
    }
}
