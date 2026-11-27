package com.wangbin.collector.core.report.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import lombok.extern.slf4j.Slf4j;

/**
 * MQTT 平台业务 ACK 处理器，只负责解析 ACK 并驱动 ACK 状态机。
 */
@Slf4j
public class MqttAckReplyHandler {

    private final ObjectMapper objectMapper;
    private final MqttAckReplySink ackReplySink;

    /**
     * 创建当前组件实例。
     */
    public MqttAckReplyHandler(ObjectMapper objectMapper, MqttAckReplySink ackReplySink) {
        this.objectMapper = objectMapper;
        this.ackReplySink = ackReplySink;
    }

    /**
     * 处理当前业务流程。
     */
    public void handle(MqttInboundMessage message) {
        if (message == null || message.payload().length == 0) {
            log.trace("忽略空 MQTT ACK payload：主题={}", message != null ? message.topic() : null);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(message.payload());
            String messageId = text(
                    root, MessageConstant.FIELD_REQUEST_ID, MessageConstant.FIELD_MESSAGE_ID, "id");
            if (messageId == null || messageId.isBlank()) {
                log.trace("MQTT ACK 缺少 id：主题={}", message.topic());
                return;
            }
            Integer code = parseAckCode(root);
            if (code == null) {
                log.warn("忽略缺少有效业务状态码的 MQTT ACK：主题={}", message.topic());
                return;
            }
            String msgText = text(root, "msg");
            ackReplySink.complete(new MqttAckReply(messageId, code, msgText == null ? "" : msgText));
            if (code != 0) {
                log.warn("MQTT ACK 返回业务错误：状态码={} 主题={}", code, message.topic());
            }
        } catch (Exception e) {
            log.warn("解析 MQTT ACK 失败：主题={} 异常类型={}", message.topic(), e.getClass().getSimpleName());
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer parseAckCode(JsonNode root) {
        JsonNode codeNode = root != null ? root.get("code") : null;
        if (codeNode == null || codeNode.isNull()) {
            return null;
        }
        if (codeNode.isInt()) {
            return codeNode.asInt();
        }
        if (!codeNode.isTextual()) {
            return null;
        }
        try {
            return Integer.parseInt(codeNode.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private String text(JsonNode root, String... fields) {
        if (root == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode node = field != null ? root.get(field) : null;
            if (node != null && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }
}
