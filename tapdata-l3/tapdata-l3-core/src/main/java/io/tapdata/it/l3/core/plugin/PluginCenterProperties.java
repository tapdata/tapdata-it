package io.tapdata.it.l3.core.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PluginCenter + MavenPluginResolver configuration — bound from
 * {@code l3.plugin.*} in application.properties / CLI args.
 */
@ConfigurationProperties(prefix = "l3.plugin")
public class PluginCenterProperties {

    /** PF4J plugins dir (plugin.properties zip/jar packages — legacy compatibility). */
    private String pluginsDir = System.getProperty("user.home") + "/.tap-l3/plugins";

    /** Nexus repo URL for MavenPluginResolver dynamic download (optional). */
    private String nexusUrl;

    /**
     * Local Maven repository for MavenPluginResolver artifact caching.
     * Defaults to ~/.m2/repository (standard Maven local repo — shares with dev environment).
     */
    private String localRepoDir = System.getProperty("user.home") + "/.m2/repository";

    public String getPluginsDir() { return pluginsDir; }
    public void setPluginsDir(String pluginsDir) { this.pluginsDir = pluginsDir; }

    public String getNexusUrl() { return nexusUrl; }
    public void setNexusUrl(String nexusUrl) { this.nexusUrl = nexusUrl; }

    public String getLocalRepoDir() { return localRepoDir; }
    public void setLocalRepoDir(String localRepoDir) { this.localRepoDir = localRepoDir; }
}
