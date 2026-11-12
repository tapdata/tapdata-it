package io.tapdata.it.l3.core.plugin;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 统一代理配置：手动下载（HttpURLConnection）与 Maven Resolver（Aether）共用。
 * <p>
 * 解析优先级：
 * <ol>
 *   <li>JVM 系统属性：{@code -Dhttps.proxyHost/Port/User/Password}、{@code -Dhttp.proxyHost/...}</li>
 *   <li>环境变量：{@code HTTPS_PROXY} / {@code HTTP_PROXY} / {@code ALL_PROXY}（含小写）+ {@code NO_PROXY}</li>
 *   <li>{@code ~/.m2/settings.xml}（可用 {@code -Dmaven.settings=...} 覆盖路径）里 active 的 {@code <proxy>}</li>
 *   <li>JVM 全局 {@link ProxySelector}（配合 {@code -Djava.net.useSystemProxies=true} 可读系统代理）</li>
 * </ol>
 * 只配置了一个协议时，会同时用于 http / https（企业代理常见配置）。
 */
final class ProxyConfig {

    private static final Logger log = LoggerFactory.getLogger(ProxyConfig.class);

    private final Entry http;
    private final Entry https;
    private final List<String> nonProxyHosts;

    private ProxyConfig(Entry http, Entry https, List<String> nonProxyHosts) {
        this.http = http;
        this.https = https;
        this.nonProxyHosts = List.copyOf(nonProxyHosts);
        // JDK 默认禁止 CONNECT 隧道使用 Basic 认证；有代理凭据时提前放开
        // （HttpURLConnection 静态初始化时读取，必须在建立连接前设置）
        if ((http != null && http.hasAuth()) || (https != null && https.hasAuth())) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    /** 一个协议对应的代理入口。 */
    record Entry(String host, int port, String username, String password, String source) {
        boolean hasAuth() {
            return username != null && !username.isBlank() && password != null;
        }

        String describe() {
            return host + ":" + port + (hasAuth() ? " (auth)" : "") + " via " + source;
        }
    }

    static ProxyConfig none() {
        return new ProxyConfig(null, null, List.of());
    }

    /** 按优先级解析代理配置；没有任何配置时返回 {@link #none()}。 */
    static ProxyConfig load() {
        return load(System.getenv(), System.getProperties());
    }

    /**
     * 可注入环境变量与系统属性的解析入口（单元测试用）。
     *
     * @param env      环境变量视图，通常传 {@code System.getenv()}；可为空 Map
     * @param sysProps 系统属性视图，通常传 {@code System.getProperties()}；可为空
     */
    static ProxyConfig load(java.util.Map<String, String> env, java.util.Properties sysProps) {
        java.util.Map<String, String> safeEnv = env == null ? java.util.Map.of() : env;
        java.util.Properties safeProps = sysProps == null ? new java.util.Properties() : sysProps;

        // 1) JVM 系统属性
        Entry https = fromSystemProperty(safeProps, "https");
        Entry http = fromSystemProperty(safeProps, "http");
        if (https != null || http != null) {
            return balanced(http, https, mergeNonProxyHosts(
                    safeProps.getProperty("https.nonProxyHosts"),
                    safeProps.getProperty("http.nonProxyHosts")));
        }

        // 2) 环境变量
        ProxyConfig fromEnv = fromEnv(safeEnv);
        if (fromEnv.isConfigured()) {
            return fromEnv;
        }

        // 3) Maven settings.xml
        Path settings = defaultSettingsPath(safeProps);
        if (Files.isRegularFile(settings)) {
            try {
                ProxyConfig fromSettings = fromSettingsXml(settings);
                if (fromSettings != null) return fromSettings;
            } catch (Exception e) {
                log.warn("[ProxyConfig] Failed to parse {}: {}", settings, e.getMessage());
            }
        }

        // 4) JVM 全局 ProxySelector（系统代理）
        https = fromDefaultProxySelector("https");
        http = fromDefaultProxySelector("http");
        if (https != null || http != null) {
            return balanced(http, https, List.of());
        }

        return none();
    }

    /**
     * 只从环境变量构建代理配置，忽略系统属性 / settings.xml / 系统 ProxySelector。
     * <p>
     * 支持 {@code HTTPS_PROXY} / {@code HTTP_PROXY} / {@code ALL_PROXY}（含小写）与 {@code NO_PROXY}，
     * 值可以是 {@code http://host:port}、{@code host:port} 或带 basic auth 的
     * {@code http://user:pass@host:port}。
     */
    static ProxyConfig fromEnv(java.util.Map<String, String> env) {
        if (env == null || env.isEmpty()) return none();
        Entry https = fromEnv(env, "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy");
        Entry http = fromEnv(env, "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy");
        List<String> bypass = mergeNonProxyHosts(env.get("NO_PROXY"), env.get("no_proxy"));
        if (https == null && http == null) return none();
        return balanced(http, https, bypass);
    }

    boolean isConfigured() {
        return http != null || https != null;
    }

    String summary() {
        if (!isConfigured()) return "DIRECT";
        return "http=" + (http == null ? "DIRECT" : http.describe())
                + ", https=" + (https == null ? "DIRECT" : https.describe())
                + ", nonProxyHosts=" + nonProxyHosts;
    }

    /** 把代理配置注入 Aether session（Maven Resolver 路径）。 */
    void applyTo(DefaultRepositorySystemSession session) {
        if (!isConfigured()) return;
        DefaultProxySelector selector = new DefaultProxySelector();
        String bypass = String.join("|", nonProxyHosts);
        addAetherProxy(selector, "http", http, bypass);
        addAetherProxy(selector, "https", https, bypass);
        session.setProxySelector(selector);
        log.info("[ProxyConfig] Maven Resolver proxy enabled: {}", summary());
    }

    /** 返回本次请求应使用的代理；未配置、非 http(s) 协议或命中 nonProxyHosts 时返回 null。 */
    java.net.Proxy proxyFor(URI uri) {
        String scheme = uri.getScheme();
        // file: 等本地仓库协议不能走 HTTP 代理
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        Entry entry = "https".equalsIgnoreCase(scheme) ? https : http;
        if (entry == null) return null;
        if (shouldBypass(uri.getHost())) {
            log.debug("[ProxyConfig] Bypass proxy for host {}", uri.getHost());
            return null;
        }
        log.debug("[ProxyConfig] Manual download via proxy {} for {}", entry.describe(), uri);
        return new java.net.Proxy(java.net.Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(entry.host(), entry.port()));
    }

    /** 为代理认证配置本次连接的 Authenticator（Java 9+，不污染全局 Authenticator）。 */
    void applyAuth(HttpURLConnection conn, URI uri) {
        Entry entry = "https".equalsIgnoreCase(uri.getScheme()) ? https : http;
        if (entry == null || !entry.hasAuth() || shouldBypass(uri.getHost())) return;
        conn.setAuthenticator(new java.net.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY) {
                    return new PasswordAuthentication(entry.username(), entry.password().toCharArray());
                }
                return null;
            }
        });
    }

    private static void addAetherProxy(DefaultProxySelector selector, String type, Entry entry, String bypass) {
        if (entry == null) return;
        Authentication auth = entry.hasAuth()
                ? new AuthenticationBuilder().addUsername(entry.username()).addPassword(entry.password()).build()
                : null;
        selector.add(new org.eclipse.aether.repository.Proxy(type, entry.host(), entry.port(), auth), bypass);
    }

    // ------------------------------------------------------------------
    // 各来源解析
    // ------------------------------------------------------------------

    private static Entry fromSystemProperty(java.util.Properties props, String scheme) {
        String host = props.getProperty(scheme + ".proxyHost");
        if (host == null || host.isBlank()) return null;
        int port = parseInt(props.getProperty(scheme + ".proxyPort"), "https".equals(scheme) ? 443 : 80);
        return new Entry(host.trim(), port,
                blankToNull(props.getProperty(scheme + ".proxyUser")),
                blankToNull(props.getProperty(scheme + ".proxyPassword")),
                "-D" + scheme + ".proxyHost");
    }

    private static Entry fromEnv(java.util.Map<String, String> env, String... names) {
        for (String name : names) {
            String raw = env.get(name);
            if (raw == null || raw.isBlank()) continue;
            try {
                return parseProxyUrl(raw.trim(), "env " + name);
            } catch (Exception e) {
                log.warn("[ProxyConfig] Ignore malformed {}={}: {}", name, raw, e.getMessage());
            }
        }
        return null;
    }

    private static Entry parseProxyUrl(String raw, String source) {
        String value = raw.contains("://") ? raw : "http://" + raw;
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("no host in " + raw);
        }
        int port = uri.getPort() > 0 ? uri.getPort()
                : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        String username = null;
        String password = null;
        if (uri.getRawUserInfo() != null) {
            String[] parts = uri.getRawUserInfo().split(":", 2);
            username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (parts.length > 1) {
                password = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return new Entry(host, port, blankToNull(username), blankToNull(password), source);
    }

    /**
     * Aether 的 {@code DefaultProxySelector} 默认是 DIRECT，不会读 JVM 系统属性；
     * 这里显式查询 JVM 全局 selector，让 {@code java.net.useSystemProxies} 生效。
     */
    private static Entry fromDefaultProxySelector(String scheme) {
        try {
            ProxySelector selector = ProxySelector.getDefault();
            if (selector == null) return null;
            for (java.net.Proxy proxy : selector.select(
                    URI.create(scheme + "://repo.maven.apache.org/maven2/"))) {
                if (proxy.type() == java.net.Proxy.Type.HTTP
                        && proxy.address() instanceof InetSocketAddress address) {
                    return new Entry(address.getHostString(), address.getPort(), null, null,
                            "system ProxySelector");
                }
            }
        } catch (Exception e) {
            log.debug("[ProxyConfig] System ProxySelector probe failed for {}: {}", scheme, e.getMessage());
        }
        return null;
    }

    private static ProxyConfig fromSettingsXml(Path settings) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 防 XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(settings.toFile());

        NodeList nodes = document.getElementsByTagName("proxy");
        Entry httpEntry = null;
        Entry httpsEntry = null;
        List<String> bypass = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (!"true".equalsIgnoreCase(text(element, "active").trim())) continue;

            String protocol = text(element, "protocol").trim().toLowerCase(Locale.ROOT);
            if (!protocol.isEmpty() && !"http".equals(protocol) && !"https".equals(protocol)) {
                log.debug("[ProxyConfig] Skip settings.xml proxy with unsupported protocol '{}'", protocol);
                continue;
            }
            String host = text(element, "host").trim();
            if (host.isEmpty()) continue;

            Entry entry = new Entry(host,
                    parseInt(text(element, "port"), "https".equals(protocol) ? 443 : 80),
                    blankToNull(text(element, "username")),
                    blankToNull(text(element, "password")),
                    "settings.xml");
            if ("https".equals(protocol)) {
                if (httpsEntry == null) httpsEntry = entry;
            } else if ("http".equals(protocol)) {
                if (httpEntry == null) httpEntry = entry;
            } else if (httpEntry == null && httpsEntry == null) {
                // 未写 protocol：先当作两个协议共用
                httpEntry = entry;
                httpsEntry = entry;
            }
            bypass.addAll(mergeNonProxyHosts(text(element, "nonProxyHosts")));
        }

        if (httpEntry == null && httpsEntry == null) return null;
        return balanced(httpEntry, httpsEntry, bypass);
    }

    private static Path defaultSettingsPath(java.util.Properties props) {
        String explicit = props.getProperty("maven.settings");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        return Path.of(props.getProperty("user.home", "."), ".m2", "settings.xml");
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static ProxyConfig balanced(Entry http, Entry https, List<String> bypass) {
        // 只配了一个协议时，同时用于另一个协议
        if (https == null) https = http;
        if (http == null) http = https;
        return new ProxyConfig(http, https, bypass);
    }

    private static List<String> mergeNonProxyHosts(String... raw) {
        List<String> result = new ArrayList<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) continue;
            for (String part : value.split("[|,]")) {
                String pattern = part.trim();
                if (!pattern.isEmpty() && !result.contains(pattern)) {
                    result.add(pattern);
                }
            }
        }
        return result;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 匹配 java.net / Maven 的 nonProxyHosts 语义（支持精确、.suffix、*.suffix 和通配符）。 */
    boolean shouldBypass(String host) {
        if (host == null || host.isBlank()) return true;
        String target = host.toLowerCase(Locale.ROOT);
        for (String raw : nonProxyHosts) {
            String pattern = raw.toLowerCase(Locale.ROOT);
            if ("*".equals(pattern)) return true;
            if (pattern.startsWith("*.") || pattern.startsWith(".")) {
                String suffix = pattern.startsWith("*.") ? pattern.substring(2) : pattern.substring(1);
                if (target.equals(suffix) || target.endsWith("." + suffix)) return true;
            } else if (pattern.contains("*")) {
                String regex = pattern.replace(".", "\\.").replace("*", ".*");
                if (target.matches(regex)) return true;
            } else if (target.equals(pattern)) {
                return true;
            }
        }
        return false;
    }
}
