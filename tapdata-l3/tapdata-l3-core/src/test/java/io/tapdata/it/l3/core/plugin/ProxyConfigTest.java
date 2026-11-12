package io.tapdata.it.l3.core.plugin;

import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代理配置解析单测 —— 覆盖环境变量、系统属性优先级和 bypass 语义。
 */
class ProxyConfigTest {

    @Test
    void envProxyIsParsedWithDefaultPort() {
        ProxyConfig config = ProxyConfig.fromEnv(Map.of("HTTP_PROXY", "proxy.corp.example"));

        assertTrue(config.isConfigured());
        Proxy proxy = config.proxyFor(URI.create("http://repo.maven.apache.org/maven2/"));
        assertNotNull(proxy);
        assertEquals("proxy.corp.example", ((java.net.InetSocketAddress) proxy.address()).getHostString());
        assertEquals(80, ((java.net.InetSocketAddress) proxy.address()).getPort());
    }

    @Test
    void singleEnvProxyAppliesToBothHttpAndHttps() {
        ProxyConfig config = ProxyConfig.fromEnv(Map.of("HTTPS_PROXY", "https://proxy.corp.example:8443"));

        assertNotNull(config.proxyFor(URI.create("https://repo.maven.apache.org/maven2/")));
        // 只配了 HTTPS 时，HTTP 流量也走同一个代理（企业代理常见配置）
        assertNotNull(config.proxyFor(URI.create("http://nexus.corp.example/repository/maven-public/")));
    }

    @Test
    void allProxyIsUsedAsFallback() {
        ProxyConfig config = ProxyConfig.fromEnv(Map.of("ALL_PROXY", "socks-host:1080"));

        Proxy proxy = config.proxyFor(URI.create("https://repo.maven.apache.org/maven2/"));
        assertNotNull(proxy);
        assertEquals(1080, ((java.net.InetSocketAddress) proxy.address()).getPort());
    }

    @Test
    void proxyCredentialsAreUrlDecoded() {
        ProxyConfig config = ProxyConfig.fromEnv(Map.of(
                "HTTP_PROXY", "http://user%40corp:p%40ss%20word@proxy.corp.example:8080"));

        String summary = config.summary();
        assertTrue(summary.contains("proxy.corp.example:8080"), summary);
        assertTrue(summary.contains("(auth)"), summary);
    }

    @Test
    void noProxyHostsBypassMatchingHosts() {
        ProxyConfig config = ProxyConfig.fromEnv(Map.of(
                "HTTPS_PROXY", "http://proxy.corp.example:8080",
                "NO_PROXY", "localhost,127.0.0.1,.corp.example,*.internal.example"));

        assertNull(config.proxyFor(URI.create("https://localhost/maven2/")));
        assertNull(config.proxyFor(URI.create("https://nexus.corp.example/maven2/")));
        assertNull(config.proxyFor(URI.create("https://repo.internal.example/maven2/")));
        assertNotNull(config.proxyFor(URI.create("https://repo.maven.apache.org/maven2/")));
    }

    @Test
    void systemPropertiesTakePrecedenceOverEnvironment() {
        Properties props = new Properties();
        props.setProperty("https.proxyHost", "sys-proxy.corp.example");
        props.setProperty("https.proxyPort", "9443");

        ProxyConfig config = ProxyConfig.load(
                Map.of("HTTPS_PROXY", "http://env-proxy.corp.example:8080"), props);

        Proxy proxy = config.proxyFor(URI.create("https://repo.maven.apache.org/maven2/"));
        assertNotNull(proxy);
        assertEquals("sys-proxy.corp.example", ((java.net.InetSocketAddress) proxy.address()).getHostString());
        assertEquals(9443, ((java.net.InetSocketAddress) proxy.address()).getPort());
    }

    @Test
    void nonHttpSchemesAreNeverProxied() {
        ProxyConfig config = ProxyConfig.fromEnv(Map.of("HTTP_PROXY", "http://proxy.corp.example:8080"));

        // file: 本地仓库不能被 HTTP 代理劫持
        assertNull(config.proxyFor(URI.create("file:///tmp/repo/")));
    }

    @Test
    void emptyEnvMeansNoProxy() {
        ProxyConfig config = ProxyConfig.fromEnv(Map.of());
        assertFalse(config.isConfigured());
        assertEquals("DIRECT", config.summary());
        assertNull(config.proxyFor(URI.create("https://repo.maven.apache.org/maven2/")));
    }

    @Test
    void malformedEnvValueIsIgnored() {
        ProxyConfig config = ProxyConfig.fromEnv(Map.of("HTTP_PROXY", "http://"));
        assertFalse(config.isConfigured());
    }
}
