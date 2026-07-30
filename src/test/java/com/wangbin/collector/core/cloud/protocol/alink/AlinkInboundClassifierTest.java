package com.wangbin.collector.core.cloud.protocol.alink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.protocol.CloudInboundMessageType;
import com.wangbin.collector.core.cloud.protocol.CloudInboundRoute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlinkInboundClassifierTest {

    private final AlinkCloudProtocolAdapter adapter = AlinkCloudProtocolAdapter.standalone(new ObjectMapper());

    @Test
    void shouldClassifyPropertyPackReplyAsAckOnly() {
        CloudInboundRoute route = adapter.classifyInbound(
                "/sys/pk-gw/gateway-1/thing/event/property/pack/post_reply",
                "_reply");

        assertEquals(CloudInboundMessageType.ACK_REPLY, route.type());
        assertEquals(MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST, route.method());
        assertTrue(route.ackReply());
    }

    @Test
    void shouldClassifySubDeviceRegisterReplyAsBusinessReply() {
        CloudInboundRoute route = adapter.classifyInbound(
                "/sys/pk-gw/gateway-1/thing/auth/register/sub_reply",
                "_reply");

        assertEquals(CloudInboundMessageType.BUSINESS_REPLY, route.type());
        assertEquals(MessageConstant.MESSAGE_TYPE_AUTH_REGISTER_SUB, route.method());
        assertTrue(route.ackReply());
    }

    @Test
    void shouldClassifyPropertySetAsDownlinkCommand() {
        CloudInboundRoute route = adapter.classifyInbound(
                "/sys/pk-sub/sub-1/thing/property/set",
                "_reply");

        assertEquals(CloudInboundMessageType.DOWNLINK_COMMAND, route.type());
        assertEquals(MessageConstant.MESSAGE_TYPE_PROPERTY_SET, route.method());
        assertFalse(route.ackReply());
    }

    @Test
    void shouldSeparateDownlinkCommandAndBusinessReplyTopics() {
        assertFalse(adapter.downlinkTopicPaths().contains(AlinkCloudProtocolAdapter.AUTH_REGISTER_SUB_REPLY_PATH));
        assertTrue(adapter.businessReplyTopicPaths().contains(AlinkCloudProtocolAdapter.AUTH_REGISTER_SUB_REPLY_PATH));
    }
}
