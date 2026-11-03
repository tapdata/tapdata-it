package io.tapdata.it.l3.steps.dbforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight HTTP client for dbforge control-plane API.
 * <p>
 * Reads URL + token from config file (~/.dbforge) or env vars.
 * All responses are parsed through Jackson into typed DTOs.
 * <p>
 * <b>Important</b>: dbforge control-plane runs on a public IP, but creates databases
 * on K8s NodePorts bound to an internal network host. This client automatically
 * rewrites the returned connection host → the control-plane's public host
 * so that external clients (this L3 framework) can reach the DB directly.
 */
public class DbforgeClient {

    private static final Logger log = LoggerFactory.getLogger(DbforgeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Control-plane base URL, e.g. "http://113.98.206.138:30880". */
    private final String baseUrl;

    /** Public IP / hostname extracted from baseUrl — used to rewrite DB connection host. */
    private final String publicHost;

    private final String token;
    private final HttpClient http;

    public DbforgeClient() {
        // Read from config file first, then env vars, then system props
        String configPath = System.getProperty("dbforge.config",
                System.getenv("DBFORGE_CONFIG") != null
                        ? System.getenv("DBFORGE_CONFIG")
                        : System.getProperty("user.home") + "/.dbforge");
        String url = null, token = null;
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(configPath);
            if (java.nio.file.Files.exists(p)) {
                for (String line : java.nio.file.Files.readAllLines(p)) {
                    line = line.trim();
                    if (line.isBlank() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String k = line.substring(0, eq).trim();
                        String v = line.substring(eq + 1).trim();
                        if ("url".equalsIgnoreCase(k)) url = v;
                        if ("token".equalsIgnoreCase(k)) token = v;
                    }
                }
            }
        } catch (IOException ignored) {}

        this.baseUrl = System.getProperty("dbforge.url",
                System.getenv("DBFORGE_URL") != null ? System.getenv("DBFORGE_URL")
                        : url != null ? url : "http://113.98.206.138:30880");
        this.token = System.getProperty("dbforge.token",
                System.getenv("DBFORGE_TOKEN") != null ? System.getenv("DBFORGE_TOKEN")
                        : token != null ? token : "");

        // Extract public host from control-plane URL — NodePorts are reachable on the same host
        this.publicHost = extractHost(baseUrl);
        log.info("[DBForge] Initialized: baseUrl={}, publicHost={}", baseUrl, publicHost);

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Extract host component from a URL, or the URL itself if parsing fails. */
    private static String extractHost(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() != null ? u.getHost() : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Rewrite any internal-host references in a connection string to the public host.
     * dbforge returns K8s cluster-internal IPs (e.g. 192.168.x.x) which are only reachable
     * from inside the cluster; we replace them with the NodePort's public host.
     */
    private String rewriteHost(String value, String internalHost) {
        if (value == null || internalHost == null || internalHost.equals(publicHost)) {
            return value;
        }
        // Replace internal host → public host in the URL/URI
        String rewritten = value.replace(internalHost, publicHost);
        if (!rewritten.equals(value)) {
            log.debug("[DBForge] Rewrote host: {} → {} (internal={}, public={})",
                    value, rewritten, internalHost, publicHost);
        }
        return rewritten;
    }

    // ==================== Public API ====================

    /**
     * Create a new lease.
     *
     * @param dbType    e.g. "mysql", "postgresql", "mongodb"
     * @param mode      "shared" or "dedicated"
     * @param topology  e.g. "single", "primary-replica", "replicaset"
     * @return lease DTO with connection details (host rewritten to public IP)
     */
    public LeaseResult createLease(String dbType, String mode, String topology) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("db_type", dbType);
        if (mode != null) body.put("mode", mode);
        if (topology != null) body.put("topology", topology);

        JsonNode resp = post("/v1/leases", body);
        LeaseResult lease = LeaseResult.fromJson(resp);

        // Always keep the original internal host — TM agents inside K8s need this
        lease.internalHost = lease.host;

        // Rewrite internal host → public host for external connectivity (L3 runner)
        if (lease.host != null && !lease.host.equals(publicHost)) {
            String internalHost = lease.host;
            lease.host = publicHost;
            if (lease.jdbcUrl != null) lease.jdbcUrl = rewriteHost(lease.jdbcUrl, internalHost);
            if (lease.uri != null) lease.uri = rewriteHost(lease.uri, internalHost);
            log.info("[DBForge] Rewrote DB host {} → {} (NodePort on control plane, internal preserved as internalHost)", internalHost, publicHost);
        }

        return lease;
    }

    /** Destroy a lease — releases the database. */
    public void destroyLease(String leaseId) throws IOException, InterruptedException {
        String url = baseUrl + "/v1/leases/" + leaseId;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            log.warn("[DBForge] destroyLease {} returned {}: {}", leaseId, resp.statusCode(), resp.body());
        } else {
            log.info("[DBForge] destroyLease {} OK", leaseId);
        }
    }

    // ==================== Internal ====================

    private JsonNode post(String path, Map<String, Object> body) throws IOException, InterruptedException {
        String url = baseUrl + path;
        String json = MAPPER.writeValueAsString(body);
        log.debug("[DBForge] POST {} → {}", url, json);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IOException("DBForge API " + resp.statusCode() + " on " + url + ": " + resp.body());
        }
        JsonNode node = MAPPER.readTree(resp.body());
        log.debug("[DBForge] Response: {}", resp.body());
        return node;
    }

    // ==================== DTO ====================

    /** lease_id + connection details. */
    public static class LeaseResult {
        public String leaseId;
        public String status;
        public String dbType;
        public String mode;
        public String topology;
        public String host;          // Public host (for external clients like L3 runner)
        public String internalHost;  // Original internal host (for TM agents inside K8s)
        public int port;
        public String database;
        public String username;
        public String password;
        public String uri;       // e.g. "mysql://user:pass@host:port/db"
        public String jdbcUrl;   // e.g. "jdbc:mysql://host:port/db?..."

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("lease_id", leaseId);
            m.put("status", status);
            m.put("db_type", dbType);
            m.put("jdbc_url", jdbcUrl);
            m.put("uri", uri);
            m.put("host", host);
            m.put("internal_host", internalHost);
            m.put("port", port);
            m.put("database", database);
            m.put("username", username);
            m.put("password", password);
            return m;
        }

        static LeaseResult fromJson(JsonNode n) {
            LeaseResult r = new LeaseResult();
            r.leaseId = text(n, "lease_id");
            r.status = text(n, "status");
            r.dbType = text(n, "db_type");
            r.mode = text(n, "mode");
            r.topology = text(n, "topology");
            JsonNode conn = n.get("connection");
            if (conn != null) {
                r.host = text(conn, "host");
                r.port = conn.has("port") ? conn.get("port").asInt(0) : 0;
                r.database = text(conn, "database");
                r.username = text(conn, "username");
                r.password = text(conn, "password");
                r.uri = text(conn, "uri");
                r.jdbcUrl = text(conn, "jdbc_url");
                if (r.jdbcUrl == null) r.jdbcUrl = text(conn, "jdbcUrl");
            }
            return r;
        }

        private static String text(JsonNode n, String key) {
            return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
        }
    }
}
