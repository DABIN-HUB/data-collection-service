package com.wangbin.collector.core.collector.protocol.ads.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsAddress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdsAddressParserTest {

    @Test
    void shouldInferTypeForDirectAddress() {
        AdsAddress address = AdsAddressParser.parse(point("0x4020/0x0", "FLOAT", Map.of()));

        assertEquals("0x4020/0x0:REAL", address.getPlc4xAddress());
        assertEquals("REAL", address.getBasePlcType());
        assertTrue(address.isScalar());
        assertFalse(address.isSymbolic());
    }

    @Test
    void shouldMapPlatformByteTypeToSintForDirectAddress() {
        AdsAddress address = AdsAddressParser.parse(point("0x4020/0x0", "BYTE", Map.of()));

        assertEquals("0x4020/0x0:SINT", address.getPlc4xAddress());
        assertEquals("SINT", address.getBasePlcType());
    }

    @Test
    void shouldPreserveExplicitDriverByteTypeForDirectAddress() {
        AdsAddress address = AdsAddressParser.parse(point("0x4020/0x0", "INT", Map.of("driverDataType", "BYTE")));

        assertEquals("0x4020/0x0:BYTE", address.getPlc4xAddress());
        assertEquals("BYTE", address.getBasePlcType());
    }

    @Test
    void shouldPreserveExplicitDirectArrayAddress() {
        AdsAddress address = AdsAddressParser.parse(point("0x4020/0x0:DINT[4]", "LONG", Map.of()));

        assertEquals("0x4020/0x0:DINT[4]", address.getPlc4xAddress());
        assertEquals("DINT", address.getBasePlcType());
        assertEquals(4, address.getArraySize());
    }

    @Test
    void shouldInferStringLengthForDirectAddress() {
        AdsAddress address = AdsAddressParser.parse(point("16416/32", "STRING", Map.of("stringLength", 80)));

        assertEquals("16416/32:STRING(80)", address.getPlc4xAddress());
        assertEquals("STRING", address.getBasePlcType());
        assertEquals(80, address.getStringLength());
    }

    @Test
    void shouldPreserveSymbolicAddressAndKeepResolvedType() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("adsType", "LREAL");

        AdsAddress address = AdsAddressParser.parse(point("MAIN.temperature", "FLOAT", config));

        assertEquals("MAIN.temperature", address.getPlc4xAddress());
        assertTrue(address.isSymbolic());
        assertEquals("LREAL", address.getBasePlcType());
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