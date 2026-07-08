package com.wangbin.collector.core.cloud.protocol.alink.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.config.CloudPayloadOptions;
import com.wangbin.collector.core.cloud.config.CloudPayloadProfile;
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

    private static final String METADATA_PROPERTY_PACK = "propertyPack";

    private final ObjectMapper objectMapper;

    public AlinkPayloadEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] encodeReportData(ReportData data) {
        return encodeReportData(data, CloudPayloadOptions.defaults());
    }

    public byte[] encodeReportData(ReportData data, CloudPayloadOptions options) {
        try {
            return objectMapper.writeValueAsBytes(toReportBody(data, options));
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
        return toReportBody(data, CloudPayloadOptions.defaults());
    }

    public Map<String, Object> toReportBody(ReportData data, CloudPayloadOptions options) {
        CloudPayloadOptions payloadOptions = options == null ? CloudPayloadOptions.defaults() : options;
        Map<String, Object> body = new LinkedHashMap<>();
        String messageId = resolveMessageId(data);
        body.put("id", messageId);
        if (payloadOptions.includeMessageId()) {
            body.put(MessageConstant.FIELD_MESSAGE_ID, messageId);
        }
        body.put("version", MessageConstant.MESSAGE_VERSION_1_0);
        body.put("method", data.getMethod());
        if (payloadOptions.includeTimestamp()) {
            body.put("timestamp", data.getTimestamp());
        }
        body.put("params", buildParams(data));

        if (payloadOptions.includeQuality(data.getPropertyQuality())) {
            body.put("quality", data.getPropertyQuality());
        }
        if ((payloadOptions.includePropertyTs() || payloadOptions.profile() == CloudPayloadProfile.DIAGNOSTIC)
                && !data.getPropertyTs().isEmpty()) {
            body.put("propertyTs", data.getPropertyTs());
        }
        Map<String, Object> metadata = filterMetadata(data, payloadOptions);
        if (!metadata.isEmpty()) {
            body.put("metadata", metadata);
        }
        return body;
    }

    public byte[] encodeUtf8(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> buildParams(ReportData data) {
        Map<String, Object> params = new LinkedHashMap<>();
        Object propertyPack = data.getMetadata() != null ? data.getMetadata().get(METADATA_PROPERTY_PACK) : null;
        if (MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST.equals(data.getMethod()) && propertyPack != null) {
            // 批量属性包按云平台 Alink 规范放入 params.properties。
            params.put("properties", propertyPack);
        } else if (data.hasProperties()) {
            params.putAll(data.getProperties());
        } else if (data.getPointCode() != null) {
            params.put(data.getPointCode(), data.getValue());
        }
        return params;
    }

    private Map<String, Object> filterMetadata(ReportData data, CloudPayloadOptions options) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (data.getMetadata() == null || data.getMetadata().isEmpty()) {
            return metadata;
        }
        if (!options.includeMetadata() && options.profile() != CloudPayloadProfile.DIAGNOSTIC) {
            return metadata;
        }
        data.getMetadata().forEach((key, value) -> {
            if (key == null || METADATA_PROPERTY_PACK.equals(key)) {
                return;
            }
            metadata.put(key, value);
        });
        return metadata;
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