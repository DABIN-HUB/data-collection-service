package com.wangbin.collector.core.collector.protocol.s7.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S7AddressParserTest {

    @Test
    void shouldExpandDbBitAddressToPlc4xSyntax() {
        DataPoint point = point("DB1.DBX0.0", "BOOLEAN", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("DB1:0.0:BOOL", address.getPlc4xAddress());
        assertEquals("DB", address.getArea());
        assertEquals("BOOL", address.getPlcType());
    }

    @Test
    void shouldInferRealTypeForDbdAddress() {
        DataPoint point = point("DB1.DBD4", "FLOAT32", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("DB1:4:REAL", address.getPlc4xAddress());
        assertEquals("REAL", address.getPlcType());
    }

    @Test
    void shouldExpandInputBitAddress() {
        DataPoint point = point("I0.0", "BOOLEAN", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%I0.0:BOOL", address.getPlc4xAddress());
        assertEquals("INPUT", address.getArea());
    }

    @Test
    void shouldUseConfiguredStringLengthForStringAddresses() {
        DataPoint point = point("DB20.DBB2", "STRING", Map.of("stringLength", 32));

        S7Address address = S7AddressParser.parse(point);

        assertEquals("DB20:2:STRING(32)", address.getPlc4xAddress());
        assertEquals("STRING(32)", address.getPlcType());
    }

    @Test
    void shouldNormalizeDriverTypeAliasWhenInferringAddressType() {
        DataPoint point = point("DB1.DBB0", "INT16", Map.of("driverDataType", "BYTE"));

        S7Address address = S7AddressParser.parse(point);

        assertEquals("DB1:0:USINT", address.getPlc4xAddress());
        assertEquals("USINT", address.getPlcType());
    }


    @Test
    void shouldApplyConfiguredArraySizeToUntypedAddress() {
        DataPoint point = point("DB1.DBW0", "INT16", Map.of("arraySize", 4));

        S7Address address = S7AddressParser.parse(point);

        assertEquals("DB1:0:INT[4]", address.getPlc4xAddress());
        assertEquals(4, address.getArraySize());
    }

    @Test
    void shouldRejectNonPositiveConfiguredArraySize() {
        DataPoint point = point("DB1.DBW0", "INT16", Map.of("arraySize", 0));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> S7AddressParser.parse(point));

        assertEquals("S7 array size must be greater than 0", exception.getMessage());
    }

    @Test
    void shouldPreserveExplicitDbPlc4xAddress() {
        DataPoint point = point("%DB56.DBW20:INT", "INT16", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB56.DBW20:INT", address.getPlc4xAddress());
        assertEquals("INT", address.getPlcType());
    }

    @Test
    void shouldPreserveExplicitDbBitPlc4xAddress() {
        DataPoint point = point("%DB1.DBX0.0:BOOL", "BOOLEAN", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB1.DBX0.0:BOOL", address.getPlc4xAddress());
        assertEquals("BOOL", address.getPlcType());
    }

    @Test
    void shouldPreserveShortPlc4xDbAddress() {
        DataPoint point = point("DB1:4:REAL", "FLOAT32", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("DB1:4:REAL", address.getPlc4xAddress());
        assertEquals("REAL", address.getPlcType());
    }

    private DataPoint point(String address, String dataType, Map<String, Object> additionalConfig) {
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setAdditionalConfig(additionalConfig);
        return point;
    }
}
