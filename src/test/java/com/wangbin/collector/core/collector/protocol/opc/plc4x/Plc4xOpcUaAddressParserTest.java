package com.wangbin.collector.core.collector.protocol.opc.plc4x;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.domain.Plc4xOpcUaAddress;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.util.Plc4xOpcUaAddressParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Plc4xOpcUaAddressParserTest {

    @Test
    void shouldAppendDriverTypeToExplicitNodeId() {
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setAddress("ns=2;s=Channel1.Device1.Tag1");
        point.setDataType("FLOAT");
        point.setCollectionMode("SUBSCRIPTION");

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("samplingInterval", 250);
        point.setAdditionalConfig(config);

        Plc4xOpcUaAddress address = Plc4xOpcUaAddressParser.parse(point);

        assertEquals("ns=2;s=Channel1.Device1.Tag1;REAL", address.getPlc4xAddress());
        assertEquals("REAL", address.getDataType());
        assertTrue(address.needSubscribe());
        assertEquals(250d, address.getSamplingInterval());
    }

    @Test
    void shouldBuildNodeIdFromNamespaceFields() {
        DataPoint point = new DataPoint();
        point.setPointId("p2");
        point.setDataType("BOOLEAN");

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("namespace", 3);
        config.put("identifierType", "i");
        config.put("identifier", 1001);
        point.setAdditionalConfig(config);

        Plc4xOpcUaAddress address = Plc4xOpcUaAddressParser.parse(point);

        assertEquals("ns=3;i=1001", address.getRawAddress());
        assertEquals("ns=3;i=1001;BOOL", address.getPlc4xAddress());
        assertEquals("BOOL", address.getDataType());
    }

    @Test
    void shouldMapBinaryAliasesToByteString() {
        DataPoint point = new DataPoint();
        point.setPointId("p3");
        point.setAddress("ns=2;s=Channel1.Device1.Payload");
        point.setDataType("STRING");
        point.setAdditionalConfig(new LinkedHashMap<>(Map.of("driverDataType", "BYTE_ARRAY")));

        Plc4xOpcUaAddress address = Plc4xOpcUaAddressParser.parse(point);

        assertEquals("ns=2;s=Channel1.Device1.Payload;BYTESTRING", address.getPlc4xAddress());
        assertEquals("BYTESTRING", address.getDataType());
    }
}