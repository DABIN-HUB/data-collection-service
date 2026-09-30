package com.wangbin.collector.core.collector.protocol.opc;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.processor.ProcessResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpcUaCollectorReadStatusTest {
    private static final StatusCode BAD_NODE_ID_UNKNOWN = new StatusCode(0x80340000L);

    @Test
    void singleReadRejectsBadStatusEvenWhenPayloadExists() {
        TestCollector collector = new TestCollector(List.of(new DataValue(new Variant(17), BAD_NODE_ID_UNKNOWN)));
        assertThrows(IllegalStateException.class, () -> collector.readRaw(point("p1", "ns=2;s=Joint1")));
    }

    @Test
    void batchKeepsRequestOrderAndOmitsBadNodeWithoutLosingGoodNodes() throws Exception {
        TestCollector collector = new TestCollector(List.of(
                new DataValue(new Variant(11)),
                new DataValue(new Variant(99), BAD_NODE_ID_UNKNOWN),
                new DataValue(new Variant(33))));
        Map<String, Object> result = collector.readRawBatch(List.of(
                point("p1", "ns=2;s=Joint1"),
                point("p2", "ns=2;s=Joint2"),
                point("p3", "ns=2;s=Joint3")));
        assertEquals(List.of(NodeId.parse("ns=2;s=Joint1"), NodeId.parse("ns=2;s=Joint2"),
                NodeId.parse("ns=2;s=Joint3")), collector.requestedNodeIds);
        assertEquals(11, result.get("p1"));
        assertFalse(result.containsKey("p2"));
        assertEquals(33, result.get("p3"));
    }

    @Test
    void batchRejectsResponseWithDifferentLengthInsteadOfAssociatingWrongPoint() {
        TestCollector collector = new TestCollector(List.of(new DataValue(new Variant(11))));
        assertThrows(IllegalStateException.class, () -> collector.readRawBatch(List.of(
                point("p1", "ns=2;s=Joint1"), point("p2", "ns=2;s=Joint2"))));
    }

    @Test
    void batchAllBadDoesNotPretendToBeEmptySuccess() {
        TestCollector collector = new TestCollector(List.of(new DataValue(new Variant(null), BAD_NODE_ID_UNKNOWN)));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> collector.readRawBatch(List.of(point("p1", "ns=2;s=Joint1"))));
        assertTrue(failure.getMessage().contains("p1"));
    }

    @Test
    void commandReadReturnsBadStatusWithoutLeakingInvalidPayload() throws Exception {
        TestCollector collector = new TestCollector(List.of(new DataValue(new Variant(99), BAD_NODE_ID_UNKNOWN)));
        List<?> result = (List<?>) collector.commandRead("ns=2;s=Joint1");
        Map<?, ?> item = (Map<?, ?>) result.get(0);
        assertEquals(null, item.get("value"));
        assertTrue(item.get("status").toString().contains("Bad_NodeIdUnknown"));
    }

    @Test
    void badSubscriptionNotificationIsNotPublished() {
        TestCollector collector = new TestCollector(List.of(new DataValue(new Variant(99), BAD_NODE_ID_UNKNOWN)));
        DataPoint point = point("p1", "ns=2;s=Joint1");
        ReflectionTestUtils.invokeMethod(collector, "handleNotification", point, null,
                new DataValue(new Variant(99), BAD_NODE_ID_UNKNOWN));
        assertFalse(collector.notificationPublished);
    }

    private static DataPoint point(String id, String address) {
        DataPoint point = new DataPoint();
        point.setPointId(id);
        point.setAddress(address);
        point.setDataType("INT32");
        return point;
    }

    private static class TestCollector extends OpcUaCollector {
        private final List<DataValue> response;
        private List<NodeId> requestedNodeIds;
        private boolean notificationPublished;

        @Override
        protected ProcessResult ingestPushedValue(DataPoint point, Object rawValue) {
            notificationPublished = true;
            return null;
        }

        TestCollector(List<DataValue> response) {
            this.response = response;
            DeviceInfo device = new DeviceInfo();
            device.setDeviceId("test-opc-device");
            deviceInfo = device;
            client = mock(OpcUaClient.class);
            try {
                when(client.readValue(eq(0.0), eq(TimestampsToReturn.Both), any(NodeId.class)))
                        .thenReturn(response.get(0));
                when(client.readValues(eq(0.0), eq(TimestampsToReturn.Both), any()))
                        .thenReturn(response);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected NodeId resolveNodeId(DataPoint point) {
            return NodeId.parse(point.getAddress());
        }

        @Override
        protected List<DataValue> readValues(List<NodeId> nodeIds) {
            requestedNodeIds = List.copyOf(nodeIds);
            return response;
        }

        @Override
        protected NodeId resolveNodeIdForCommand(NodeId nodeId) {
            return nodeId;
        }

        Object commandRead(String nodeId) throws Exception {
            return doExecuteCommand(0, "read", Map.of("nodeId", nodeId));
        }

        Object readRaw(DataPoint point) throws Exception {
            return doReadPoint(point);
        }

        Map<String, Object> readRawBatch(List<DataPoint> points) throws Exception {
            return doReadPoints(points);
        }
    }
}
