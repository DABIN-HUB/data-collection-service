package com.wangbin.collector.core.collector.protocol.opc.ua.address;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpcUaNodeIdResolverTest {

    private final OpcUaNodeIdResolver resolver = new OpcUaNodeIdResolver();

    @Test
    void noneAndDefaultModeKeepNodeIdUnchanged() {
        NodeId original = NodeId.parse("ns=2;s=Joint1");
        assertEquals(original, resolver.resolve(original, connection("NONE", "Robot")));
        assertEquals(original, resolver.resolve(original, new DeviceConnection()));
    }

    @Test
    void prefixOnlySimpleStringIdentifier() {
        DeviceConnection connection = connection("PREFIX", "Robot");
        assertEquals(NodeId.parse("ns=2;s=Robot.Joint1"),
                resolver.resolve(NodeId.parse("ns=2;s=Joint1"), connection));
        assertEquals(NodeId.parse("ns=2;i=1001"),
                resolver.resolve(NodeId.parse("ns=2;i=1001"), connection));
        assertEquals(NodeId.parse("ns=2;s=Robot.Joint1"),
                resolver.resolve(NodeId.parse("ns=2;s=Robot.Joint1"), connection));
    }

    @Test
    void blankPrefixKeepsNodeIdUnchanged() {
        NodeId original = NodeId.parse("ns=2;s=Joint1");
        assertEquals(original, resolver.resolve(original, connection("PREFIX", "")));
    }

    @Test
    void protoForgeTemplateAddressRequiresExplicitAliasConfiguration() {
        DataPoint point = new DataPoint();
        point.setPointId("joint1_angle");
        point.setAddress("ns=2;s=Joint1");
        assertEquals(NodeId.parse("ns=2;s=Joint1"), resolver.resolve(point, new DeviceConnection()));
        DeviceConnection alias = connection("PREFIX", "pf_proto_opcua");
        assertEquals(NodeId.parse("ns=2;s=pf_proto_opcua.Joint1"), resolver.resolve(point, alias));
        point.setAddress("ns=2;s=pf_proto_opcua.Joint1");
        assertEquals(NodeId.parse("ns=2;s=pf_proto_opcua.Joint1"), resolver.resolve(point, alias));
    }

    @Test
    void conflictingNodeIdSourcesAreRejected() {
        DataPoint point = new DataPoint();
        point.setPointId("joint1_angle");
        point.setAddress("ns=2;s=Joint1");
        point.setAdditionalConfig(Map.of("nodeId", "ns=2;s=OldJoint1"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(point, new DeviceConnection()));
    }

    private DeviceConnection connection(String mode, String prefix) {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(Map.of("nodeIdAliasMode", mode, "nodeIdPrefix", prefix));
        return connection;
    }
}
