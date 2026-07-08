package com.wangbin.collector.core.cloud.protocol.alink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkMessageEnvelope;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkPayloadDecoder;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkPayloadEncoder;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicBuilder;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicParser;
import com.wangbin.collector.core.report.model.ReportData;
import org.junit.jupiter.api.Test;

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
        assertEquals(body.get("id").asText(), data.getMetadata().get(MessageConstant.FIELD_MESSAGE_ID));

        AlinkMessageEnvelope envelope = decoder.decode(topic, payload);
        assertEquals(AlinkMethod.PROPERTY_POST, envelope.method());
        assertEquals("pk-a", envelope.identity().productKey());
        assertEquals("device-a", envelope.identity().deviceName());
    }
}
