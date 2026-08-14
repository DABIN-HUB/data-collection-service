package com.wangbin.collector.core.report.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.cloud.protocol.CloudInboundRoute;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkCloudProtocolAdapter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MqttInboundMessageDispatcherTest {

    @Test
    void shouldRouteRequestIdAckReplyToAckHandlerOnly() {
        AtomicReference<MqttAckReply> ackReply = new AtomicReference<>();
        RecordingDownlinkCommandHandler downlinkCommandHandler = new RecordingDownlinkCommandHandler();
        MqttInboundMessageDispatcher dispatcher = new MqttInboundMessageDispatcher(
                AlinkCloudProtocolAdapter.standalone(new ObjectMapper()),
                "_reply",
                new MqttAckReplyHandler(new ObjectMapper(), ackReply::set),
                null,
                downlinkCommandHandler);

        dispatcher.dispatch(new MqttInboundMessage(
                "/sys/pk-gw/gateway-1/thing/event/property/pack/post_reply",
                ("{\"id\":\"id-value\",\"messageId\":\"message-value\","
                        + "\"requestId\":\"request-value\",\"code\":0}")
                        .getBytes(StandardCharsets.UTF_8),
                1,
                "alink"));

        assertNotNull(ackReply.get());
        assertEquals("request-value", ackReply.get().messageId());
        assertEquals(0, downlinkCommandHandler.count.get());
    }

    @Test
    void shouldRouteBusinessReplyToAckAndBusinessHandlers() {
        AtomicInteger ackCount = new AtomicInteger();
        RecordingBusinessReplyService businessReplyService = new RecordingBusinessReplyService();
        RecordingDownlinkCommandHandler downlinkCommandHandler = new RecordingDownlinkCommandHandler();
        MqttInboundMessageDispatcher dispatcher = new MqttInboundMessageDispatcher(
                AlinkCloudProtocolAdapter.standalone(new ObjectMapper()),
                "_reply",
                new MqttAckReplyHandler(new ObjectMapper(), ack -> ackCount.incrementAndGet()),
                businessReplyService,
                downlinkCommandHandler);

        dispatcher.dispatch(new MqttInboundMessage(
                "/sys/pk-gw/gateway-1/thing/auth/register/sub_reply",
                "{\"id\":\"reg-1\",\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8),
                1,
                "alink"));

        assertEquals(1, ackCount.get());
        assertEquals(1, businessReplyService.count.get());
        assertEquals(0, downlinkCommandHandler.count.get());
    }

    private static class RecordingDownlinkCommandHandler extends MqttDownlinkCommandHandler {

        private final AtomicInteger count = new AtomicInteger();

        private RecordingDownlinkCommandHandler() {
            super(null, null, "_reply");
        }

        @Override
        public void handle(MqttInboundMessage message, CloudInboundRoute route) {
            count.incrementAndGet();
        }
    }

    private static class RecordingBusinessReplyService extends MqttBusinessReplyService {

        private final AtomicInteger count = new AtomicInteger();

        private RecordingBusinessReplyService() {
            super(new ObjectMapper(), null);
        }

        @Override
        public MqttBusinessReplyResult handle(MqttInboundMessage message, CloudInboundRoute route) {
            count.incrementAndGet();
            return MqttBusinessReplyResult.success(route.method(), null);
        }
    }
}
