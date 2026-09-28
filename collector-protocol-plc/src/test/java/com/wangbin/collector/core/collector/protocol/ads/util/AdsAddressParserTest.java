package com.wangbin.collector.core.collector.protocol.ads.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsAddress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void shouldPreserveValidSymbolicAndMixedNumericAddress() {
        assertEquals("MAIN.motor[2].speed", AdsAddressParser.parse("MAIN.motor[2].speed").getPlc4xAddress());
        assertEquals("0x4020/32:DINT[2]", AdsAddressParser.parse("0x4020/32:DINT[2]").getPlc4xAddress());
        assertEquals("16416/0x20:WSTRING(16)[2]", AdsAddressParser.parse("16416/0x20:WSTRING(16)[2]").getPlc4xAddress());
    }

    @Test
    void shouldRequireAndValidateStringLength() {
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32", "STRING", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32:WSTRING", "STRING", Map.of())));
        for (Object length : new Object[]{0, -1, "abc", "1000", 1.5}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AdsAddressParser.parse(point("16416/32", "STRING", Map.of("stringLength", length))));
        }
        assertEquals("16416/32:WSTRING(999)", AdsAddressParser.parse(point("16416/32", "STRING",
                Map.of("driverDataType", "WSTRING", "adsStringLength", "999"))).getPlc4xAddress());
    }

    @Test
    void shouldRejectConflictingExplicitLengthsAndTypes() {
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32:STRING(16)", "STRING",
                Map.of("stringLength", 32))));
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32:STRING(16)", "STRING",
                Map.of("driverDataType", "WSTRING"))));
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32:DINT", "LONG",
                Map.of("driverDataType", "REAL"))));
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32", "STRING",
                Map.of("driverDataType", "STRING(16)", "stringLength", 32))));
        assertEquals("16416/32:STRING(16)", AdsAddressParser.parse(point("16416/32", "STRING",
                Map.of("driverDataType", "STRING(16)"))).getPlc4xAddress());
    }

    @Test
    void shouldRejectBadCountsAndConflictingCounts() {
        for (Object count : new Object[]{0, -1, "abc", "2147483648", 1.5}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AdsAddressParser.parse(point("16416/32:DINT", "LONG", Map.of("arraySize", count))));
        }
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse("16416/32:DINT[0]"));
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32:DINT[4]", "LONG",
                Map.of("numberOfElements", 2))));
        assertEquals(4, AdsAddressParser.parse(point("16416/32:DINT[4]", "LONG",
                Map.of("numberOfElements", 4))).getArraySize());
        assertEquals("16416/32:DINT[1]", AdsAddressParser.parse("16416/32:DINT[1]").getPlc4xAddress());
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("MAIN.speed", "LONG",
                Map.of("arraySize", 4))));
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32:DINT", "LONG",
                Map.of("arraySize", 4, "numberOfElements", 2))));
    }

    @Test
    void shouldRejectMalformedDirectAddressInsteadOfTreatingItAsSymbol() {
        assertEquals("0xffffffff/4294967295:DINT", AdsAddressParser.parse("0xffffffff/4294967295:DINT").getPlc4xAddress());
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse("0x100000000/1:DINT"));
        assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(point("16416/32:STRING(16)", "STRING",
                Map.of("stringLength", 16, "adsStringLength", 17))));
        for (String raw : new String[]{"16416/32:STRING(1000)", "16416/32:DINT[-2]",
                "0x10000000000000000/1:DINT", "16416/32:STRING(0)", "MAIN..speed", "16416/32:UNKNOWN"}) {
            assertThrows(IllegalArgumentException.class, () -> AdsAddressParser.parse(raw));
        }
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