package io.tapdata.it.l3.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.tapdata.it.l3.api.model.TmApiException;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.Map;

/**
 * TM 线上传输 JSON 编解码：提供与 Web 层解耦、但与 TM 序列化口径对齐的共享 {@link ObjectMapper}。
 * <p>
 * 关键点：
 * <ul>
 *   <li>{@link ObjectId} 与十六进制字符串互反（兼容 {@code "id":"65.."} 与 {@code {"$oid":"65.."}}），
 *       保证与 TM 的 Mongo ObjectId 序列化对齐；</li>
 *   <li>注册 {@link JavaTimeModule} 处理 LocalDateTime；</li>
 *   <li>反序列化忽略未知字段（{@code FAIL_ON_UNKNOWN_PROPERTIES=false}），对版本差异更鲁棒。</li>
 * </ul>
 * 该 Mapper 仅负责"传输层拆包/装包"，复杂领域对象（如带多态 DAG 的 TaskDto）的构造由用例侧以 JSON
 * 模板或 DTO 提供，框架不臆测服务端类型判定细节。
 */
public final class TmJson {

    private static final ObjectMapper MAPPER = build();

    private TmJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper build() {
        SimpleModule module = new SimpleModule("tapdata-it-l3");
        module.addSerializer(ObjectId.class, new ObjectIdSerializer());
        module.addDeserializer(ObjectId.class, new ObjectIdDeserializer());

        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(module)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
    }

    /** 序列化对象为 JSON 字符串；失败包装为 {@link TmApiException}。 */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new TmApiException("Serialize request body failed: " + e.getMessage(), null, e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为目标类型；失败包装为 {@link TmApiException}。
     *
     * @param json 原始 JSON
     * @param type 目标类型（用 {@link com.fasterxml.jackson.core.type.TypeReference} 承载泛型）
     */
    public static <T> T read(String json, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new TmApiException("Deserialize response failed: " + e.getMessage(), null, e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new TmApiException("Deserialize response failed: " + e.getMessage(), null, e);
        }
    }

    /** ObjectId → 十六进制字符串。 */
    static final class ObjectIdSerializer extends JsonSerializer<ObjectId> {
        @Override
        public void serialize(ObjectId value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.toHexString());
        }
    }

    /** 兼容 {@code "65.."} 与 {@code {"$oid":"65.."}} 两种形态 → ObjectId。 */
    static final class ObjectIdDeserializer extends JsonDeserializer<ObjectId> {
        @Override
        @SuppressWarnings("unchecked")
        public ObjectId deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_STRING) {
                String text = p.getText();
                return (text == null || text.isEmpty()) ? null : new ObjectId(text);
            }
            if (token == JsonToken.START_OBJECT) {
                Map<String, Object> map = p.readValueAs(Map.class);
                Object oid = map == null ? null : map.get("$oid");
                return oid == null ? null : new ObjectId(oid.toString());
            }
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            throw new IOException("Unsupported ObjectId token: " + token);
        }
    }
}
