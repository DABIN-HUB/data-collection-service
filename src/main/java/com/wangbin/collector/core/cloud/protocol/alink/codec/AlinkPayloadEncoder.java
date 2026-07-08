package com.wangbin.collector.core.cloud.protocol.alink.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.report.model.ReportData;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Alink payload 编码器。
 */
@Component
public class AlinkPayloadEncoder {

    private final ObjectMapper objectMapper;

    public AlinkPayloadEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] encodeReportData(ReportData data) {
        try {
            return objectMapper.writeValueAsBytes(toReportBody(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("编码 Alink 上报消息失败", e);
        }
    }

    public byte[] encodeBody(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("编码 Alink 消息失败", e);
        }
    }

    public Map<String, Object> toReportBody(ReportData data) {
        Map<String, Object> body = new LinkedHashMap<>();
        String messageId = resolveMessageId(data);
        body.put("id", messageId);
        body.put(MessageConstant.FIELD_MESSAGE_ID, messageId);
        body.put("version", MessageConstant.MESSAGE_VERSION_1_0);
        body.put("method", data.getMethod());
        body.put("timestamp", data.getTimestamp());

        Map<String, Object> params = new LinkedHashMap<>();
        Object propertyPack = data.getMetadata() != null ? data.getMetadata().get("propertyPack") : null;
        if (MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST.equals(data.getMethod()) && propertyPack != null) {
            // 批量属性包按云平台 Alink 规范放入 params.properties。
            params.put("properties", propertyPack);
        } else if (data.hasProperties()) {
            params.putAll(data.getProperties());
        } else if (data.getPointCode() != null) {
            params.put(data.getPointCode(), data.getValue());
        }
        body.put("params", params);

        if (!data.getPropertyQuality().isEmpty()) {
            body.put("quality", data.getPropertyQuality());
        }
        if (!data.getPropertyTs().isEmpty()) {
            body.put("propertyTs", data.getPropertyTs());
        }
        if (data.getMetadata() != null && !data.getMetadata().isEmpty()) {
            body.put("metadata", data.getMetadata());
        }
        return body;
    }

    public byte[] encodeUtf8(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    private String resolveMessageId(ReportData data) {
        if (data == null) {
            return UUID.randomUUID().toString();
        }
        Object existing = data.getMetadata().get(MessageConstant.FIELD_MESSAGE_ID);
        if (existing != null && !existing.toString().isBlank()) {
            return existing.toString();
        }
        Object id = data.getMetadata().get("id");
        if (id != null && !id.toString().isBlank()) {
            data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, id.toString());
            return id.toString();
        }
        String generated = UUID.randomUUID().toString();
        data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, generated);
        return generated;
    }
}
