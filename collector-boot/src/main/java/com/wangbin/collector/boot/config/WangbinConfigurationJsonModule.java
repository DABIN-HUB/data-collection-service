package com.wangbin.collector.boot.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅在 Boot JSON 边界保留原始告警和扩展精度，不改变运行期告警解释规则。 */
public final class WangbinConfigurationJsonModule extends SimpleModule {
    private static final String INVALID_JSON = "配置 JSON 格式不正确";
    private static final ObjectMapper PRECISE = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() { };

    public WangbinConfigurationJsonModule() {
        super("wangbin-configuration-json");
        setMixInAnnotation(DataPoint.class, PointMixin.class);
        setMixInAnnotation(DeviceConnection.class, ConnectionMixin.class);
        setMixInAnnotation(DeviceInfo.class, DeviceMixin.class);
        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription bean,
                                                             List<BeanPropertyWriter> properties) {
                if (DataPoint.class.isAssignableFrom(bean.getBeanClass())) {
                    for (int i = 0; i < properties.size(); i++) {
                        BeanPropertyWriter writer = properties.get(i);
                        if ("alarmRule".equals(writer.getName())) properties.set(i, new RawAlarmRuleWriter(writer));
                    }
                }
                return properties;
            }
        });
    }

    /** 字段注解覆盖实体内部兼容反序列化器，其他默认属性保持不变。 */
    private abstract static class PointMixin {
        @JsonDeserialize(using = ExactMapDeserializer.class)
        private Map<String, Object> additionalConfig;
        @JsonDeserialize(using = RawAlarmRuleDeserializer.class)
        private String alarmRule;
    }

    private abstract static class DeviceMixin {
        @JsonDeserialize(using = ExactMapDeserializer.class)
        private Map<String, Object> authConfig;
    }

    private abstract static class ConnectionMixin {
        @JsonDeserialize(using = ExactMapDeserializer.class)
        private Map<String, Object> extJson;
        @JsonDeserialize(using = ExactMapDeserializer.class)
        private Map<String, Object> stats;
        @JsonDeserialize(using = StringMapDeserializer.class)
        private Map<String, String> authParams;
    }

    /** 直接读取原字段，不能先调用 typed getter 再覆盖已经损失的 JSON。 */
    private static final class RawAlarmRuleWriter extends BeanPropertyWriter {
        private final Field rawField;

        private RawAlarmRuleWriter(BeanPropertyWriter original) {
            super(original);
            try {
                rawField = DataPoint.class.getDeclaredField("alarmRule");
                if (!rawField.trySetAccessible()) throw new IllegalStateException("无法访问告警配置字段");
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("无法访问告警配置字段");
            }
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator generator, SerializerProvider provider) throws Exception {
            JsonNode node;
            try {
                String raw = (String) rawField.get(bean);
                node = raw == null || raw.isBlank() ? null : PRECISE.readTree(raw);
            } catch (IOException | IllegalAccessException | RuntimeException exception) {
                throw JsonMappingException.from(generator, INVALID_JSON);
            }
            if ((node == null || node.isNull()) && willSuppressNulls()) return;
            generator.writeFieldName(getSerializedName());
            if (node == null) generator.writeNull();
            else generator.writeTree(node);
        }
    }

    /** 由精确 mapper 直接读取原 token，避免先经过默认 readTree 丢失小数精度。 */
    private static JsonNode readConfigurationValue(JsonParser parser) throws IOException {
        try {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String text = parser.getText();
                return text == null || text.isBlank() ? null : PRECISE.readTree(text);
            }
            // 当前属性之后还有其他属性，尾随内容检查仅用于上面的独立 JSON 字符串。
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return PRECISE.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(parser);
        } catch (IOException | RuntimeException exception) {
            throw JsonMappingException.from(parser, INVALID_JSON);
        }
    }

    public static final class ExactMapDeserializer extends JsonDeserializer<Map<String, Object>> {
        @Override
        public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = readConfigurationValue(parser);
            if (node == null || node.isNull()) return new LinkedHashMap<>();
            if (!node.isObject()) throw JsonMappingException.from(parser, INVALID_JSON);
            try {
                return PRECISE.convertValue(node, MAP_TYPE);
            } catch (RuntimeException exception) {
                throw JsonMappingException.from(parser, INVALID_JSON);
            }
        }
    }

    public static final class StringMapDeserializer extends JsonDeserializer<Map<String, String>> {
        @Override
        public Map<String, String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = readConfigurationValue(parser);
            Map<String, String> values = new LinkedHashMap<>();
            if (node == null || node.isNull()) return values;
            if (!node.isObject()) throw JsonMappingException.from(parser, INVALID_JSON);
            var fields = node.properties().iterator();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode value = entry.getValue();
                if (!value.isNull() && !value.isTextual()) throw JsonMappingException.from(parser, INVALID_JSON);
                values.put(entry.getKey(), value.isNull() ? null : value.textValue());
            }
            return values;
        }
    }

    public static final class RawAlarmRuleDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = readConfigurationValue(parser);
            if (node != null && !node.isNull() && !node.isObject() && !node.isArray()) {
                throw JsonMappingException.from(parser, "告警规则必须为对象或数组");
            }
            return node == null || node.isNull() ? null : PRECISE.writeValueAsString(node);
        }
    }
}
