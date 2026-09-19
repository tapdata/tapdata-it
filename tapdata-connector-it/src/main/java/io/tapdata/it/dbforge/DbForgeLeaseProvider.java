package io.tapdata.it.dbforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DbForgeLeaseProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicBoolean SOURCE_LOGGED = new AtomicBoolean();
    private static final String SOURCE_PROPERTY = "connector.it.config.source";
    private static final String SOURCE_ENVIRONMENT = "CONNECTOR_IT_CONFIG_SOURCE";
    private static final String URL_PROPERTY = "connector.it.dbforge.url";
    private static final String URL_ENVIRONMENT = "CONNECTOR_IT_DBFORGE_URL";
    private static final String TOKEN_PROPERTY = "connector.it.dbforge.token";
    private static final String TOKEN_ENVIRONMENT = "CONNECTOR_IT_DBFORGE_TOKEN";
    private static final String TTL_PROPERTY = "connector.it.dbforge.ttl.minutes";
    private static final String TTL_ENVIRONMENT = "CONNECTOR_IT_DBFORGE_TTL_MINUTES";
    private static final String OWNER_PROPERTY = "connector.it.dbforge.owner";
    private static final String OWNER_ENVIRONMENT = "CONNECTOR_IT_DBFORGE_OWNER";
    private static final String ENDPOINT_SCOPE_PROPERTY = "connector.it.dbforge.endpoint.scope";
    private static final String ENDPOINT_SCOPE_ENVIRONMENT = "CONNECTOR_IT_DBFORGE_ENDPOINT_SCOPE";
    private static final String PROVISION_TIMEOUT_PROPERTY = "connector.it.dbforge.provision.timeout.seconds";
    private static final String PROVISION_TIMEOUT_ENVIRONMENT = "CONNECTOR_IT_DBFORGE_PROVISION_TIMEOUT_SECONDS";
    private static final int REQUEST_TIMEOUT_MILLIS = 30_000;

    private final String baseUrl;
    private final String token;
    private final int ttlMinutes;
    private final String owner;
    private final String endpointScope;
    private final int provisionTimeoutMillis;
    private String leaseId;

    private DbForgeLeaseProvider(String baseUrl, String token, int ttlMinutes, String owner, String endpointScope,
                                 int provisionTimeoutMillis) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.token = token;
        this.ttlMinutes = ttlMinutes;
        this.owner = owner;
        this.endpointScope = endpointScope;
        this.provisionTimeoutMillis = provisionTimeoutMillis;
    }

    public static boolean isDbForgeSelected() {
        String source = resolve(SOURCE_PROPERTY, SOURCE_ENVIRONMENT, "json");
        if (!"json".equalsIgnoreCase(source) && !"dbforge".equalsIgnoreCase(source)) {
            throw new IllegalArgumentException("Invalid " + SOURCE_PROPERTY + ": " + source
                    + "; expected json or dbforge");
        }
        if (SOURCE_LOGGED.compareAndSet(false, true)) {
            System.out.printf("[IT] connection source=%s%n", source.toLowerCase());
        }
        return "dbforge".equalsIgnoreCase(source);
    }

    public static DbForgeLeaseProvider fromEnvironment(String defaultOwner) {
        String url = required(URL_PROPERTY, URL_ENVIRONMENT);
        String token = required(TOKEN_PROPERTY, TOKEN_ENVIRONMENT);
        int ttl = parsePositiveInt(resolve(TTL_PROPERTY, TTL_ENVIRONMENT, "60"), TTL_PROPERTY);
        String owner = resolve(OWNER_PROPERTY, OWNER_ENVIRONMENT, defaultOwner);
        String endpointScope = resolve(ENDPOINT_SCOPE_PROPERTY, ENDPOINT_SCOPE_ENVIRONMENT, null);
        int provisionTimeoutSeconds = parsePositiveInt(resolve(
                PROVISION_TIMEOUT_PROPERTY, PROVISION_TIMEOUT_ENVIRONMENT, "900"), PROVISION_TIMEOUT_PROPERTY);
        if (endpointScope != null && !"internal".equalsIgnoreCase(endpointScope) && !"external".equalsIgnoreCase(endpointScope)) {
            throw new IllegalArgumentException("Invalid " + ENDPOINT_SCOPE_PROPERTY + ": " + endpointScope
                    + "; expected internal or external");
        }
        return new DbForgeLeaseProvider(url, token, ttl, owner, endpointScope, provisionTimeoutSeconds * 1_000);
    }

    public Connection acquire(String dbType, String mode, String topology) throws IOException {
        if (leaseId != null) {
            throw new IllegalStateException("DBForge lease has already been acquired: " + leaseId);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("db_type", dbType);
        request.put("mode", mode);
        request.put("topology", topology);
        request.put("ttl_minutes", ttlMinutes);
        request.put("owner", owner);
        if (endpointScope != null) {
            request.put("endpoint_scope", endpointScope);
        }
        JsonNode root = MAPPER.readTree(request("POST", "/v1/leases", MAPPER.writeValueAsString(request),
                UUID.randomUUID().toString(), false, provisionTimeoutMillis));
        JsonNode connection = root.path("connection");
        if (!connection.isObject()) {
            throw new IOException("DBForge response does not contain connection");
        }
        String returnedLeaseId = text(root, "lease_id", "leaseId");
        if (returnedLeaseId == null) {
            throw new IOException("DBForge response does not contain lease_id");
        }
        leaseId = returnedLeaseId;
        try {
            return Connection.from(connection);
        } catch (RuntimeException error) {
            try {
                release();
            } catch (IOException ignored) {
            }
            throw error;
        }
    }

    public void release() throws IOException {
        if (leaseId == null) {
            return;
        }
        String currentLeaseId = leaseId;
        try {
            request("DELETE", "/v1/leases/" + currentLeaseId, null, null, true, REQUEST_TIMEOUT_MILLIS);
        } finally {
            leaseId = null;
        }
    }

    public String getLeaseId() {
        return leaseId;
    }

    private String request(String method, String path, String body, String idempotencyKey, boolean notFoundIsSuccess,
                           int readTimeoutMillis) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        if (idempotencyKey != null) {
            connection.setRequestProperty("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        if ((status < 200 || status >= 300) && !(notFoundIsSuccess && status == 404)) {
            String errorBody = readBody(connection.getErrorStream());
            throw new IOException("DBForge " + method + " " + path + " failed with HTTP " + status
                    + (errorBody.isEmpty() ? "" : ": " + errorBody));
        }
        return notFoundIsSuccess && status == 404 ? "" : readBody(connection.getInputStream());
    }

    private static String readBody(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String required(String property, String environment) {
        String value = resolve(property, environment, null);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing DBForge setting: -D" + property + " or " + environment);
        }
        return value;
    }

    private static int parsePositiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value, error);
        }
    }

    private static String resolve(String property, String environment, String defaultValue) {
        String value = System.getProperty(property);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        value = System.getenv(environment);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().trim().isEmpty()) {
                return value.asText();
            }
        }
        return null;
    }

    public static final class Connection {
        private final Map<String, String> values;

        private Connection(Map<String, String> values) {
            this.values = Collections.unmodifiableMap(values);
        }

        public String required(String key) throws IOException {
            String value = values.get(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IOException("DBForge connection is missing " + key);
            }
            return value;
        }

        public String firstRequired(String... keys) throws IOException {
            for (String key : keys) {
                String value = values.get(key);
                if (value != null && !value.trim().isEmpty()) {
                    return value;
                }
            }
            throw new IOException("DBForge connection is missing one of " + String.join(", ", keys));
        }

        public int requiredPort() throws IOException {
            try {
                return Integer.parseInt(required("port"));
            } catch (NumberFormatException error) {
                throw new IOException("DBForge connection has invalid port", error);
            }
        }

        public String optional(String key) {
            return values.get(key);
        }

        public Map<String, String> values() {
            return values;
        }

        private static Connection from(JsonNode node) {
            Map<String, String> values = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isValueNode() && !value.isNull()) {
                    values.put(field.getKey(), value.asText());
                }
            }
            JsonNode extra = node.path("extra");
            if (extra.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> extraFields = extra.fields();
                while (extraFields.hasNext()) {
                    Map.Entry<String, JsonNode> field = extraFields.next();
                    JsonNode value = field.getValue();
                    if (value.isValueNode() && !value.isNull()) {
                        values.put(field.getKey(), value.asText());
                    }
                }
            }
            return new Connection(values);
        }
    }
}
