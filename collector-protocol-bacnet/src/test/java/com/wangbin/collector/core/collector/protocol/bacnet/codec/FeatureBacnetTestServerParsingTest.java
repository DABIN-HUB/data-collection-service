package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.FeatureBacnetTestServer.SubscribeRequest;

class FeatureBacnetTestServerParsingTest {

    @Test
    void shouldParseObjectSubscriptionAndCancellationEndPositions() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            BacnetSubscribeCovRequest subscription = objectRequest(true, 60);
            SubscribeRequest parsedSubscription = server.parseSubscribeFrame(BacnetSubscribeCovCodec.encode(subscription));
            assertEquals(1001, parsedSubscription.processIdentifier());
            assertTrue(parsedSubscription.issueConfirmedNotifications());
            assertEquals(60, parsedSubscription.lifetime());

            BacnetSubscribeCovRequest unconfirmedSubscription = objectRequest(false, 60);
            SubscribeRequest parsedUnconfirmed = server.parseSubscribeFrame(
                    BacnetSubscribeCovCodec.encode(unconfirmedSubscription));
            assertFalse(parsedUnconfirmed.issueConfirmedNotifications());
            assertEquals(60, parsedUnconfirmed.lifetime());

            BacnetSubscribeCovRequest cancellation = objectRequest(false, null);
            SubscribeRequest parsedCancellation = server.parseSubscribeFrame(BacnetSubscribeCovCodec.encode(cancellation));
            assertFalse(parsedCancellation.issueConfirmedNotifications());
            assertNull(parsedCancellation.lifetime());
        }
    }

    @Test
    void shouldParsePropertySubscriptionAndCancellationEndPositions() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            BacnetSubscribeCovPropertyRequest subscription = propertyRequest(true, 0, 1.5d);
            SubscribeRequest parsedSubscription = server.parseSubscribeFrame(
                    BacnetSubscribeCovPropertyCodec.encode(subscription));
            assertTrue(parsedSubscription.propertyLevel());
            assertTrue(parsedSubscription.issueConfirmedNotifications());
            assertEquals(0, parsedSubscription.lifetime());
            assertEquals(1.5d, parsedSubscription.covIncrement(), 1.0E-6);

            BacnetSubscribeCovPropertyRequest cancellation = propertyRequest(false, null, null);
            SubscribeRequest parsedCancellation = server.parseSubscribeFrame(
                    BacnetSubscribeCovPropertyCodec.encode(cancellation));
            assertTrue(parsedCancellation.propertyLevel());
            assertFalse(parsedCancellation.issueConfirmedNotifications());
            assertNull(parsedCancellation.lifetime());
            assertNull(parsedCancellation.covIncrement());
        }
    }

    @Test
    void shouldRejectTruncatedRequiredSubscriptionField() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            byte[] validFrame = BacnetSubscribeCovCodec.encode(objectRequest(false, 60));
            byte[] truncatedFrame = Arrays.copyOf(validFrame, validFrame.length - 1);

            assertThrows(IllegalArgumentException.class,
                    () -> server.parseSubscribeFrame(truncatedFrame));
        }
    }

    private BacnetSubscribeCovRequest objectRequest(boolean confirmed, Integer lifetime) {
        return BacnetSubscribeCovRequest.builder()
                .subscriberProcessIdentifier(1001)
                .objectType(BacnetObjectType.ANALOG_INPUT)
                .objectInstance(1)
                .issueConfirmedNotifications(confirmed)
                .lifetimeSeconds(lifetime)
                .invokeId(7)
                .remoteDeviceInstance(1001)
                .build();
    }

    private BacnetSubscribeCovPropertyRequest propertyRequest(boolean confirmed,
                                                              Integer lifetime,
                                                              Double increment) {
        return BacnetSubscribeCovPropertyRequest.builder()
                .subscriberProcessIdentifier(1002)
                .objectType(BacnetObjectType.ANALOG_INPUT)
                .objectInstance(1)
                .propertyIdentifier(BacnetPropertyIdentifier.PRESENT_VALUE)
                .issueConfirmedNotifications(confirmed)
                .lifetimeSeconds(lifetime)
                .covIncrement(increment)
                .invokeId(8)
                .remoteDeviceInstance(1001)
                .build();
    }
}
