package io.tapdata.it.l3.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.tapdata.it.l3.api.model.TmApiException;
import io.tapdata.it.l3.api.model.TmResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * TM（管理端）API 客户端：L3 用例与已部署产品交互的统一入口。
 * <p>
 * 职责内聚：
 * <ul>
 *   <li><b>认证</b>：支持三种取得 {@code access-token} 的方式 —— 账密登录（{@link #login}）、
 *       accessCode 换 token（{@link #loginByAccessCode}）、预置 token 直连（{@link #withToken}）；</li>
 *   <li><b>传输</b>：持有 {@link TmApiContext}（tm-sdk {@code TmAvailableRestTemplate} + Apache HttpClient）；</li>
 *   <li><b>API 装配</b>：为 TM 每个 Controller 暴露对应 API 访问器（{@link #taskApi()}/{@link #dataSourceApi()}/…），
 *       懒加载、共享同一 {@link TmApiContext}（含 token）。</li>
 * </ul>
 * 用法：{@code tmApi.taskApi().startTask(taskDto)}。
 */
public class TmApiClient {

    /** TM 登录口令 RC4 加密密钥（与 {@code UserController.RC4_KEY} 对齐）。 */
    private static final String RC4_KEY = "Gotapd8";
    private static final String SALTED_MAGIC = "Salted__";

    private static final TypeReference<TmResponse<Map<String, Object>>> RESP_MAP =
            new TypeReference<TmResponse<Map<String, Object>>>() {
            };

    private final TmApiContext ctx;

    private volatile TaskAPI taskApi;
    private volatile DataSourceAPI dataSourceApi;
    private volatile DataSourceDefinitionAPI dataSourceDefinitionApi;
    private volatile MetadataInstancesAPI metadataInstancesApi;
    private volatile PdkAPI pdkApi;
    private volatile InspectAPI inspectApi;
    private volatile MetadataAPI metadataApi;
    private volatile ClusterAPI clusterApi;

    public TmApiClient(String tmBaseUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.ctx = new TmApiContext(Objects.requireNonNull(tmBaseUrl, "tmBaseUrl"), connectTimeoutMs, readTimeoutMs);
    }

    /** 默认超时（连接 10s / 读 60s）。 */
    public static TmApiClient at(String tmBaseUrl) {
        return new TmApiClient(tmBaseUrl, 10_000, 60_000);
    }

    public TmApiContext context() {
        return ctx;
    }

    public String accessToken() {
        return ctx.getAccessToken();
    }

    // ---- 认证 ----

    /**
     * 账密登录（{@code POST /users/login}）。口令需以 RC4（密钥 {@value #RC4_KEY}）加密后提交，
     * 与 TM Web 前端一致；服务端 {@code RC4Util.decrypt} 解密后比对。
     *
     * @return 本客户端（链式）
     */
    public TmApiClient login(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", encryptPassword(password));
        Map<String, Object> data = postForData("/users/login", TmJson.write(body));
        return applyToken(data);
    }

    /** accessCode 换 token（{@code POST /users/generatetoken}，服务端 {@code @IgnoreLogin}）。 */
    public TmApiClient loginByAccessCode(String accessCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accesscode", accessCode);
        Map<String, Object> data = postForData("/users/generatetoken", TmJson.write(body));
        return applyToken(data);
    }

    /** 预置 token 直连（独立进程反复测同一环境时最省事，跳过登录）。 */
    public TmApiClient withToken(String accessToken) {
        ctx.setAccessToken(accessToken);
        return this;
    }

    private Map<String, Object> postForData(String path, String jsonBody) {
        String raw = ctx.exchange(org.springframework.http.HttpMethod.POST, path, jsonBody);
        if (raw == null || raw.trim().isEmpty()) {
            throw new TmApiException("Empty login response from TM @ " + path);
        }
        TmResponse<Map<String, Object>> response = TmJson.read(raw, RESP_MAP);
        if (!response.isSuccess()) {
            throw new TmApiException("TM login failed [" + response.getCode() + "]: " + response.getMessage(),
                    response.getCode());
        }
        return response.getData();
    }

    private TmApiClient applyToken(Map<String, Object> accessTokenDto) {
        String token = accessTokenDto == null ? null
                : (accessTokenDto.get("id") == null ? null : String.valueOf(accessTokenDto.get("id")));
        if (token == null || token.trim().isEmpty()) {
            throw new TmApiException("TM login returned no access token id");
        }
        ctx.setAccessToken(token);
        return this;
    }

    // ---- API 访问器（懒加载，共享 ctx）----

    public TaskAPI taskApi() {
        TaskAPI local = taskApi;
        if (local == null) {
            synchronized (this) {
                local = taskApi;
                if (local == null) {
                    taskApi = local = new TaskAPI(ctx);
                }
            }
        }
        return local;
    }

    public DataSourceAPI dataSourceApi() {
        DataSourceAPI local = dataSourceApi;
        if (local == null) {
            synchronized (this) {
                local = dataSourceApi;
                if (local == null) {
                    dataSourceApi = local = new DataSourceAPI(ctx);
                }
            }
        }
        return local;
    }

    public DataSourceDefinitionAPI dataSourceDefinitionApi() {
        DataSourceDefinitionAPI local = dataSourceDefinitionApi;
        if (local == null) {
            synchronized (this) {
                local = dataSourceDefinitionApi;
                if (local == null) {
                    dataSourceDefinitionApi = local = new DataSourceDefinitionAPI(ctx);
                }
            }
        }
        return local;
    }

    public MetadataInstancesAPI metadataInstancesApi() {
        MetadataInstancesAPI local = metadataInstancesApi;
        if (local == null) {
            synchronized (this) {
                local = metadataInstancesApi;
                if (local == null) {
                    metadataInstancesApi = local = new MetadataInstancesAPI(ctx);
                }
            }
        }
        return local;
    }

    public PdkAPI pdkApi() {
        PdkAPI local = pdkApi;
        if (local == null) {
            synchronized (this) {
                local = pdkApi;
                if (local == null) {
                    pdkApi = local = new PdkAPI(ctx);
                }
            }
        }
        return local;
    }

    public InspectAPI inspectApi() {
        InspectAPI local = inspectApi;
        if (local == null) {
            synchronized (this) {
                local = inspectApi;
                if (local == null) {
                    inspectApi = local = new InspectAPI(ctx);
                }
            }
        }
        return local;
    }

    public MetadataAPI metadataApi() {
        MetadataAPI local = metadataApi;
        if (local == null) {
            synchronized (this) {
                local = metadataApi;
                if (local == null) {
                    metadataApi = local = new MetadataAPI(ctx);
                }
            }
        }
        return local;
    }

    public ClusterAPI clusterApi() {
        ClusterAPI local = clusterApi;
        if (local == null) {
            synchronized (this) {
                local = clusterApi;
                if (local == null) {
                    clusterApi = local = new ClusterAPI(ctx);
                }
            }
        }
        return local;
    }

    // ---- RC4 口令加密（复刻 com.tapdata.tm.utils.RC4Util.encrypt，保证与服务端 decrypt 对齐）----

    private static String encryptPassword(String plaintext) {
        try {
            byte[] pass = RC4_KEY.getBytes(StandardCharsets.US_ASCII);
            byte[] salt = new java.security.SecureRandom().generateSeed(8);
            byte[] inBytes = plaintext.getBytes(StandardCharsets.UTF_8);

            byte[] passAndSalt = concat(pass, salt);
            byte[] hash = new byte[0];
            byte[] keyAndIv = new byte[0];
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (int i = 0; i < 3 && keyAndIv.length < 48; i++) {
                hash = md.digest(concat(hash, passAndSalt));
                keyAndIv = concat(keyAndIv, hash);
            }
            byte[] keyValue = Arrays.copyOfRange(keyAndIv, 0, 32);
            SecretKeySpec key = new SecretKeySpec(keyValue, "RC4");
            Cipher cipher = Cipher.getInstance("RC4");
            cipher.init(Cipher.ENCRYPT_MODE, key, (javax.crypto.spec.IvParameterSpec) null);
            byte[] data = cipher.doFinal(inBytes);
            data = concat(concat(SALTED_MAGIC.getBytes(StandardCharsets.US_ASCII), salt), data);
            return Base64.getEncoder().encodeToString(data);
        } catch (Exception e) {
            throw new TmApiException("Encrypt login password failed: " + e.getMessage(), null, e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
