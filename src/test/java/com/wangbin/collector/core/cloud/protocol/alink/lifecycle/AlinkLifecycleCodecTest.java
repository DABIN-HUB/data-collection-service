package com.wangbin.collector.core.cloud.protocol.alink.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AlinkLifecycleCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AlinkLifecycleCodec codec = new AlinkLifecycleCodec(objectMapper, new AlinkTopicBuilder());

    @Test
    void shouldEncodeGatewayOnlineStateMessageWithoutReplyAck() throws Exception {
        AlinkLifecycleCodec.LifecycleMessage message =
                codec.encodeGatewayOnline(CloudDeviceIdentity.of("pk-gw", "gw-1"));

        JsonNode body = objectMapper.readTree(message.payload());

        assertEquals("/sys/pk-gw/gw-1/thing/state/update", message.topic());
        assertEquals(MessageConstant.MESSAGE_TYPE_STATE_UPDATE, body.path("method").asText());
        assertEquals(1, body.path("params").path("state").asInt());
        assertFalse(MessageConstant.getAckMethods().contains(MessageConstant.MESSAGE_TYPE_STATE_UPDATE));
    }

    @Test
    void shouldEncodeGatewayOfflineStateMessage() throws Exception {
        AlinkLifecycleCodec.LifecycleMessage message =
                codec.encodeGatewayOffline(CloudDeviceIdentity.of("pk-gw", "gw-1"));

        JsonNode body = objectMapper.readTree(message.payload());

        assertEquals("/sys/pk-gw/gw-1/thing/state/update", message.topic());
        assertEquals(MessageConstant.MESSAGE_TYPE_STATE_UPDATE, body.path("method").asText());
        assertEquals(2, body.path("params").path("state").asInt());
    }
}
