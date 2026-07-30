package com.wangbin.collector.core.collector.protocol.mc.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDriverType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McAddressParserTest {

    @Test
    void shouldParseDecimalWordAddress() {
        McAddress address = McAddressParser.parse(point("D100", "INT16", Map.of()));

        assertEquals("D100", address.getCanonicalAddress());
        assertEquals(McDeviceCode.D, address.getDeviceCode());
        assertEquals(100, address.getDeviceNumber());
        assertEquals(McDriverType.INT16, address.getDriverType());
        assertEquals(1, address.getReadUnitCount());
    }

    @Test
    void shouldParseHexBitArrayAddress() {
        McAddress address = McAddressParser.parse(point("X1A[3]", "BOOLEAN", Map.of()));

        assertEquals("X1A[3]", address.getCanonicalAddress());
        assertEquals(McDeviceCode.X, address.getDeviceCode());
        assertEquals(0x1A, address.getDeviceNumber());
        assertEquals(McDriverType.BOOL, address.getDriverType());
        assertEquals(3, address.getArraySize());
        assertEquals(2, address.getExpectedPayloadLength());
    }

    @Test
    void shouldParseWordBitOffsetAddress() {
        McAddress address = McAddressParser.parse(point("D100.3", "BOOLEAN", Map.of()));

        assertEquals("D100.3", address.getCanonicalAddress());
        assertEquals(McDeviceCode.D, address.getDeviceCode());
        assertEquals(100, address.getDeviceNumber());
        assertEquals(McDriverType.BOOL, address.getDriverType());
        assertEquals(3, address.getBitIndex());
    }

    @Test
    void shouldParseBitOffsetFromAdditionalConfig() {
        McAddress address = McAddressParser.parse(point("D100", "BOOLEAN", Map.of("bitIndex", 7)));

        assertEquals("D100.7", address.getCanonicalAddress());
        assertEquals(7, address.getBitIndex());
    }

    @Test
    void shouldRequireStringLengthForStringPoints() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> McAddressParser.parse(point("D200", "STRING", Map.of())));

        assertEquals("MC STRING requires additionalConfig.stringLength", exception.getMessage());
    }

    @Test
    void shouldRejectNumericTypeOnBitDevice() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> McAddressParser.parse(point("M0", "INT16", Map.of())));

        assertEquals("MC bit devices only support BOOL in P0", exception.getMessage());
    }

    @Test
    void shouldRejectNonBooleanBitOffsetAddress() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> McAddressParser.parse(point("D100.3", "INT16", Map.of())));

        assertEquals("MC bit offset address only supports BOOL", exception.getMessage());
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
