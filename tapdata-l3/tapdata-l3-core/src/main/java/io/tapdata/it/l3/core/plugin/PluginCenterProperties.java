package io.tapdata.it.l3.core.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * PluginCenter + MavenPluginResolver configuration — bound from
 * {@code l3.plugin.*} in application.properties / YAML / CLI args / system props / env vars.
 * <p>
 * Spring Boot relaxed binding means ALL of these work equivalently:
 * <pre>
 *   # application.properties
 *   l3.plugin.remote-repos=https://nexus.internal.com/repository/maven-public
 *   l3.plugin.local-repo-dir=${user.home}/.m2/repository
 *
 *   # YAML
 *   l3:
 *     plugin:
 *       remote-repos:
 *         - https://repo.maven.apache.org/maven2/
 *         - https://nexus.internal.com/repository/public
 *
 *   # CLI
 *   java -jar l3-cli-fat.jar run --dir cases \
 *     --l3.plugin.remote-repos=https://nexus.internal.com/repository/maven-public \
 *     --l3.plugin.local-repo-dir=/tmp/l3-m2
 *
 *   # System property
 *   -Dl3.plugin.remote-repos=https://nexus.internal.com/repository/public
 * </pre>
 */
@ConfigurationProperties(prefix = "l3.plugin")
public class PluginCenterProperties {

    /** PF4J plugins dir (plugin.properties zip/jar packages — legacy compatibility). */
    private String pluginsDir = System.getProperty("user.home") + "/.tap-l3/plugins";

    /**
     * 远端 Maven 仓库列表 —— 用于 MavenPluginResolver 下载 step 模块 jar 和 runtime deps。
     * <p>
     * 顺序决定 lookup 优先级（先命中先用）。默认只有 Maven Central。
     * <p>
     * CLI 传递时用逗号分隔：{@code --l3.plugin.remote-repos=https://a.com,https://b.com}
     */
    private List<String> remoteRepos = new ArrayList<>(List.of("https://repo.maven.apache.org/maven2/"));

    /**
     * 本地 Maven 仓库路径（artifact 缓存目录）。
     * 默认 {@code ~/.m2/repository}（标准 Maven 本地仓库 — 与开发环境共享）。
     */
    private String localRepoDir = System.getProperty("user.home") + "/.m2/repository";

    /**
     * 下载依赖的并发线程数 —— 控制 MavenPluginResolver 批量下载 artifact 时的并发度。
     * <p>
     * 默认 4 —— 对 Nexus/私服和 Maven Central 已经足够快，同时避免并发太高触发对端限流。
     * 内网私服 Nexus 性能好可以调到 8，慢速外网或低带宽环境建议降到 2。
     * <p>
     * 配置方式：
     * <pre>
     *   # CLI
     *   --l3.plugin.download-concurrency=8
     *
     *   # application.properties
     *   l3.plugin.download-concurrency=8
     *
     *   # 环境变量（Spring Boot relaxed binding）
     *   L3_PLUGIN_DOWNLOAD_CONCURRENCY=8
     * </pre>
     */
    private int downloadConcurrency = 4;

    // ----- 兼容旧字段 nexusUrl —— 如果用户只配了 nexusUrl，合并进 remoteRepos -----

    /** @deprecated 请用 {@link #remoteRepos} — 单 URL 时通过 YAML/CLI 列表只写一项即可。 */
    @Deprecated
    private String nexusUrl;

    public String getPluginsDir() { return pluginsDir; }
    public void setPluginsDir(String pluginsDir) { this.pluginsDir = pluginsDir; }

    public List<String> getRemoteRepos() {
        // 兼容 legacy nexusUrl —— 如果 remoteRepos 只含默认 Central 但 nexusUrl 被单独设置过，合并
        if (remoteRepos != null && remoteRepos.size() == 1
                && remoteRepos.get(0).equals("https://repo.maven.apache.org/maven2/")
                && nexusUrl != null && !nexusUrl.isBlank()) {
            List<String> merged = new ArrayList<>(remoteRepos);
            merged.add(nexusUrl);
            return merged;
        }
        return remoteRepos;
    }
    public void setRemoteRepos(List<String> remoteRepos) { this.remoteRepos = remoteRepos; }

    public String getLocalRepoDir() { return localRepoDir; }
    public void setLocalRepoDir(String localRepoDir) { this.localRepoDir = localRepoDir; }

    public int getDownloadConcurrency() { return downloadConcurrency; }
    public void setDownloadConcurrency(int downloadConcurrency) { this.downloadConcurrency = downloadConcurrency; }

    /** @deprecated Use {@link #getRemoteRepos()} instead. */
    @Deprecated
    public String getNexusUrl() { return nexusUrl; }
    @Deprecated
    public void setNexusUrl(String nexusUrl) { this.nexusUrl = nexusUrl; }
}
