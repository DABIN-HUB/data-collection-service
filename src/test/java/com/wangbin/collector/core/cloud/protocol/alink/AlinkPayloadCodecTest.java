package com.wangbin.collector.core.cloud.protocol.alink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkMessageEnvelope;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkPayloadDecoder;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkPayloadEncoder;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicBuilder;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicParser;
import com.wangbin.collector.core.report.model.ReportData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AlinkPayloadCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldEncodeAndDecodeAlinkPropertyPost() throws Exception {
        AlinkPayloadEncoder encoder = new AlinkPayloadEncoder(objectMapper);
        AlinkTopicBuilder topicBuilder = new AlinkTopicBuilder();
        AlinkPayloadDecoder decoder = new AlinkPayloadDecoder(objectMapper, new AlinkTopicParser());

        ReportData data = new ReportData();
        data.setDeviceId("device-a");
        data.setMethod(MessageConstant.MESSAGE_TYPE_PROPERTY_POST);
        data.setTimestamp(1000L);
        data.addMetadata("productKey", "pk-a");
        data.addProperty("temperature", 26.5, 1000L, "GOOD");

        byte[] payload = encoder.encodeReportData(data);
        JsonNode body = objectMapper.readTree(payload);
        String topic = topicBuilder.build(CloudDeviceIdentity.of("pk-a", "device-a"), data.getMethod());

        assertEquals("/sys/pk-a/device-a/thing/property/post", topic);
        assertEquals(MessageConstant.MESSAGE_TYPE_PROPERTY_POST, body.get("method").asText());
        assertEquals(26.5, body.path("params").path("temperature").asDouble());
        assertNotNull(body.get("id"));
        String correlationId = body.get("id").asText();
        assertEquals(correlationId, body.get(MessageConstant.FIELD_REQUEST_ID).asText());
        assertEquals(correlationId, body.get(MessageConstant.FIELD_MESSAGE_ID).asText());
        assertEquals(correlationId, data.getMetadata().get(MessageConstant.FIELD_MESSAGE_ID));

        AlinkMessageEnvelope envelope = decoder.decode(topic, payload);
        assertEquals(AlinkMethod.PROPERTY_POST, envelope.method());
        assertEquals("pk-a", envelope.identity().productKey());
        assertEquals("device-a", envelope.identity().deviceName());
    }

    @Test
    void shouldEncodeGatewayPropertyPackWithDocumentShape() throws Exception {
        AlinkPayloadEncoder encoder = new AlinkPayloadEncoder(objectMapper);

        ReportData data = new ReportData();
        data.setDeviceId("gateway-1");
        data.setMethod(MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST);
        data.addMetadata("productKey", "pk-gw");
        data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, "pack-request-1");
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("properties", Map.of("cpuUsage", 45.2));
        pack.put("events", Map.of());
        pack.put("subDevices", List.of(Map.of(
                "identity", Map.of("productKey", "pk-sub", "deviceName", "sub-1"),
                "properties", Map.of("temperature", 36.5),
                "events", Map.of())));
        data.addMetadata("propertyPack", pack);

        JsonNode body = objectMapper.readTree(encoder.encodeReportData(data));

        assertEquals(MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST, body.get("method").asText());
        assertEquals("pack-request-1", body.get("id").asText());
        assertEquals("pack-request-1", body.get(MessageConstant.FIELD_REQUEST_ID).asText());
        assertEquals("pack-request-1", body.get(MessageConstant.FIELD_MESSAGE_ID).asText());
        assertEquals(45.2, body.path("params").path("properties").path("cpuUsage").asDouble());
        assertEquals("pk-sub", body.path("params").path("subDevices").get(0)
                .path("identity").path("productKey").asText());
        assertEquals(36.5, body.path("params").path("subDevices").get(0)
                .path("properties").path("temperature").asDouble());
    }

    @Test
    void shouldEncodeGatewayPropertyPackWithSubDeviceEvents() throws Exception {
        AlinkPayloadEncoder encoder = new AlinkPayloadEncoder(objectMapper);

        ReportData data = new ReportData();
        data.setDeviceId("gateway-1");
        data.setMethod(MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST);
        data.addMetadata("productKey", "pk-gw");
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("properties", Map.of());
        pack.put("events", Map.of());
        pack.put("subDevices", List.of(Map.of(
                "identity", Map.of("productKey", "pk-sub", "deviceName", "sub-1"),
                "properties", Map.of(),
                "events", Map.of("ALARM", Map.of(
                        "value", Map.of("level", 1),
                        "time", 2000L)))));
        data.addMetadata("propertyPack", pack);

        JsonNode body = objectMapper.readTree(encoder.encodeReportData(data));

        assertEquals(MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST, body.get("method").asText());
        assertEquals(1, body.path("params").path("subDevices").get(0)
                .path("events").path("ALARM").path("value").path("level").asInt());
        assertEquals(2000L, body.path("params").path("subDevices").get(0)
                .path("events").path("ALARM").path("time").asLong());
    }

    @Test
    void shouldNotFallbackPointCodeAsCloudProperty() throws Exception {
        AlinkPayloadEncoder encoder = new AlinkPayloadEncoder(objectMapper);
        ReportData data = new ReportData();
        data.setDeviceId("device-a");
        data.setMethod(MessageConstant.MESSAGE_TYPE_PROPERTY_POST);
        data.setPointCode("localPointCode");
        data.setValue(12.3);

        JsonNode body = objectMapper.readTree(encoder.encodeReportData(data));

        assertEquals(0, body.path("params").size());
    }

    @Test
    void shouldEncodeEventPostWithIdentifierValueAndTime() throws Exception {
        AlinkPayloadEncoder encoder = new AlinkPayloadEncoder(objectMapper);
        ReportData data = new ReportData();
        data.setDeviceId("device-a");
        data.setMethod(MessageConstant.MESSAGE_TYPE_EVENT_POST);
        data.setPointCode("alarm");
        data.setValue(true);
        data.setTimestamp(2000L);
        data.addMetadata("eventType", "ALARM");
        data.addMetadata("eventLevel", "WARNING");

        JsonNode body = objectMapper.readTree(encoder.encodeReportData(data));

        assertEquals(MessageConstant.MESSAGE_TYPE_EVENT_POST, body.get("method").asText());
        assertEquals("ALARM", body.path("params").path("identifier").asText());
        assertEquals(true, body.path("params").path("value").path("value").asBoolean());
        assertEquals("WARNING", body.path("params").path("value").path("eventLevel").asText());
        assertEquals(2000L, body.path("params").path("time").asLong());
    }
}
