package com.wangbin.collector.core.collector.protocol.ethernetip.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtherNetIpAddressParserTest {

    @Test
    void shouldInferTypeForSimpleTag() {
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("Tag1", "INT", Map.of()));

        assertEquals("Tag1:INT", address.getPlc4xAddress());
        assertEquals("INT", address.getBasePlcType());
        assertTrue(address.isScalar());
    }

    @Test
    void shouldMapPlatformWordTypeToUintWhenInferring() {
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("Tag1", "WORD", Map.of()));

        assertEquals("Tag1:UINT", address.getPlc4xAddress());
        assertEquals("UINT", address.getBasePlcType());
    }

    @Test
    void shouldKeepExplicitLogixTypeAndArraySize() {
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("Program:Main.TagA:REAL[4]", "FLOAT", Map.of()));

        assertEquals("Program:Main.TagA:REAL[4]", address.getPlc4xAddress());
        assertEquals("REAL", address.getBasePlcType());
        assertFalse(address.isScalar());
    }

    @Test
    void shouldKeepEipSymbolicAddress() {
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("%TagArray[2]:3:DINT", "LONG", Map.of()));

        assertEquals("%TagArray[2]:3:DINT", address.getPlc4xAddress());
        assertEquals("DINT", address.getBasePlcType());
        assertEquals(3, address.getArraySize());
    }

    @Test
    void shouldInferTypeForIndexedTagWhenNeeded() {
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("TagArray[1]", "FLOAT", Map.of()));

        assertEquals("TagArray[1]:REAL", address.getPlc4xAddress());
        assertEquals("REAL", address.getBasePlcType());
        assertTrue(address.isScalar());
    }

    @Test
    void shouldPreferExplicitOverrideType() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("logixType", "DINT");

        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("MainProgram.Tag1", "FLOAT", config));

        assertEquals("MainProgram.Tag1:DINT", address.getPlc4xAddress());
        assertEquals("DINT", address.getBasePlcType());
    }

    @Test
    void shouldPreferDriverDataTypeOverPlatformType() {
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("MainProgram.Tag1", "FLOAT", Map.of("driverDataType", "WORD")));

        assertEquals("MainProgram.Tag1:WORD", address.getPlc4xAddress());
        assertEquals("WORD", address.getBasePlcType());
    }

    private DataPoint point(String address, String dataType, Map<String, Object> additionalConfig) {
        DataPoint point = new DataPoint();
        point.setPointId(address);
        point.setAddress(address);
        point.setDataType(dataType);
        point.setAdditionalConfig(new LinkedHashMap<>(additionalConfig));
        return point;
    }
}