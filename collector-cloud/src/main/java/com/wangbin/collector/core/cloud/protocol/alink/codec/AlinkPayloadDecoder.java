package com.wangbin.collector.core.cloud.protocol.alink.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkMethod;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopic;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Alink payload 解码器。
 */
@Component
public class AlinkPayloadDecoder {

    private final ObjectMapper objectMapper;
    private final AlinkTopicParser topicParser;

    /**
     * 创建当前组件实例。
     */
    public AlinkPayloadDecoder(ObjectMapper objectMapper, AlinkTopicParser topicParser) {
        this.objectMapper = objectMapper;
        this.topicParser = topicParser;
    }

    /**
     * 解析或转换业务数据。
     */
    public AlinkMessageEnvelope decode(String topic, byte[] payload) throws IOException {
        JsonNode root = objectMapper.readTree(payload);
        Optional<AlinkTopic> parsedTopic = topicParser.parse(topic);
        AlinkMethod method = resolveMethod(root, parsedTopic);
        CloudDeviceIdentity identity = parsedTopic
                .map(AlinkTopic::identity)
                .orElseGet(() -> CloudDeviceIdentity.of(text(root, "productKey"), text(root, "deviceName")));
        return new AlinkMessageEnvelope(
                firstText(root, "id", MessageConstant.FIELD_REQUEST_ID, MessageConstant.FIELD_MESSAGE_ID),
                firstText(root, "version"),
                method,
                identity,
                root,
                root.get(MessageConstant.FIELD_PARAMS));
    }

    /**
     * 解析或转换业务数据。
     */
    private AlinkMethod resolveMethod(JsonNode root, Optional<AlinkTopic> parsedTopic) {
        String method = firstText(root, MessageConstant.FIELD_METHOD);
        if (method != null) {
            return AlinkMethod.fromMethod(method).orElse(null);
        }
        return parsedTopic.map(AlinkTopic::method).orElse(null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value == null || value.isNull() ? "" : value.asText("");
    }

    /**
     * 执行当前业务逻辑。
     */
    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
