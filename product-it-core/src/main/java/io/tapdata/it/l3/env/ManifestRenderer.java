package io.tapdata.it.l3.env;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 清单渲染器：从 classpath 读取某部署模式目录下的 YAML 模板，按上下文把 {@code ${VAR}} 占位符
 * 替换为运行时动态值（命名空间、镜像、Mongo 连接串、NodePort 等），产出可直接被 fabric8 apply 的清单。
 * <p>
 * 采用最简占位符方案（无 Helm/Go 模板依赖），保持清单为纯静态 YAML + 变量注入。
 * 未提供取值的占位符视为配置缺失，<b>快速失败</b>并点名缺失变量，便于排查。
 */
public final class ManifestRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Z0-9_]+)}");

    private ManifestRenderer() {
    }

    /**
     * 读取并渲染 classpath 下的清单模板。
     *
     * @param resourcePath 相对 classpath 根的路径，如 {@code deployments/single-node/20-tm.yaml}
     * @param context      变量名 -> 值
     * @return 渲染后的 YAML 文本
     */
    public static String render(String resourcePath, Map<String, String> context) {
        String template = readClasspath(resourcePath);
        return substitute(template, context, resourcePath);
    }

    static String substitute(String template, Map<String, String> context, String origin) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = context.get(key);
            if (value == null) {
                throw new IllegalStateException(
                        "Manifest " + origin + " references undefined variable ${" + key + "}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String readClasspath(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ManifestRenderer.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Deployment manifest not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Read manifest failed: " + resourcePath + " (" + e.getMessage() + ")", e);
        }
    }
}
