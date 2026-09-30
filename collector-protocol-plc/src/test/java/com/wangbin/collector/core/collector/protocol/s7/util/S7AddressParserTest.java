package com.wangbin.collector.core.collector.protocol.s7.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import org.apache.plc4x.java.s7.readwrite.tag.S7Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S7AddressParserTest {

    @Test
    void shouldExpandDbBitAddressToPlc4xSyntax() {
        DataPoint point = point("DB1.DBX0.0", "BOOLEAN", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB1:0.0:BOOL", address.getPlc4xAddress());
        assertEquals("DB", address.getArea());
        assertEquals("BOOL", address.getPlcType());
    }

    @Test
    void shouldInferRealTypeForDbdAddress() {
        DataPoint point = point("DB1.DBD4", "FLOAT32", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB1:4:REAL", address.getPlc4xAddress());
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

        assertEquals("%DB20:2:STRING(32)", address.getPlc4xAddress());
        assertEquals("STRING(32)", address.getPlcType());
    }

    @Test
    void shouldNormalizeDriverTypeAliasWhenInferringAddressType() {
        DataPoint point = point("DB1.DBB0", "INT16", Map.of("driverDataType", "BYTE"));

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB1:0:USINT", address.getPlc4xAddress());
        assertEquals("USINT", address.getPlcType());
    }


    @Test
    void shouldApplyConfiguredArraySizeToUntypedAddress() {
        DataPoint point = point("DB1.DBW0", "INT16", Map.of("arraySize", 4));

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB1:0:INT[4]", address.getPlc4xAddress());
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
    void shouldNormalizeExplicitDbPlc4xAddress() {
        DataPoint point = point("%DB56.DBW20:INT", "INT16", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB56:20:INT", address.getPlc4xAddress());
        assertEquals("INT", address.getPlcType());
    }

    @Test
    void shouldNormalizeExplicitDbBitPlc4xAddress() {
        DataPoint point = point("%DB1.DBX0.0:BOOL", "BOOLEAN", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB1:0.0:BOOL", address.getPlc4xAddress());
        assertEquals("BOOL", address.getPlcType());
    }

    @Test
    void shouldPreserveShortPlc4xDbAddress() {
        DataPoint point = point("DB1:4:REAL", "FLOAT32", Map.of());

        S7Address address = S7AddressParser.parse(point);

        assertEquals("%DB1:4:REAL", address.getPlc4xAddress());
        assertEquals("REAL", address.getPlcType());
    }


    @Test
    void shouldEmitCanonicalDbAddressesAcceptedByPlc4xS7Driver() {
        S7Address boolAddress = S7AddressParser.parse(point("DB1.DBX0.0", "BOOLEAN", Map.of()));
        S7Address realAddress = S7AddressParser.parse(point("DB1.DBD4", "FLOAT32", Map.of()));

        assertTrue(S7Tag.matches(boolAddress.getPlc4xAddress()));
        assertTrue(S7Tag.matches(realAddress.getPlc4xAddress()));
        assertFalse(S7Tag.matches("DB1:0.0:BOOL"));
        assertFalse(S7Tag.matches("DB1:4:REAL"));
    }

    @Test
    void shouldCanonicalizeTypedAndTiaAddressesToSamePoint() {
        S7Address tia = S7AddressParser.parse(point("DB12.DBD24", "FLOAT32", Map.of()));
        S7Address typed = S7AddressParser.parse(point("%DB12.DBD24:REAL", "FLOAT32", Map.of()));

        assertEquals(tia.getPlc4xAddress(), typed.getPlc4xAddress());
        assertEquals(tia.getPlcType(), typed.getPlcType());
        assertEquals(tia.getArraySize(), typed.getArraySize());
        assertTrue(S7Tag.matches(typed.getPlc4xAddress()));
        assertEquals("%M12:USINT", S7AddressParser.parse("MB12:BYTE").getPlc4xAddress());
        assertEquals("%DB12:24:REAL", S7AddressParser.parse("DB012:00024:REAL").getPlc4xAddress());
    }

    @Test
    void shouldKeepEncodedStringLengthAndRejectConflictingConfiguration() {
        S7Address address = S7AddressParser.parse(point("DB1.DBB2:STRING(12)", "STRING", Map.of("stringLength", 12)));
        assertEquals("%DB1:2:STRING(12)", address.getPlc4xAddress());
        assertEquals("STRING(12)", address.getPlcType());

        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse(
                point("DB1.DBB2:STRING(12)", "STRING", Map.of("stringLength", 8))));
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse(
                point("DB1.DBB2:STRING(0)", "STRING", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse(
                point("DB1.DBB2", "STRING", Map.of("driverDataType", "WSTRING(0)"))));
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse(
                point("DB1.DBB2", "STRING", Map.of("stringLength", "bogus"))));
    }

    @Test
    void shouldRejectConflictingAndInvalidArraySize() {
        assertEquals("%DB1:0:INT[3]", S7AddressParser.parse(point("DB1.DBW0:INT[3]", "INT16",
                Map.of("arraySize", 3))).getPlc4xAddress());
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse(
                point("DB1.DBW0:INT[3]", "INT16", Map.of("arraySize", 2))));
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse(
                point("DB1.DBW0", "INT16", Map.of("arraySize", 2, "s7ArraySize", 3))));
    }

    @Test
    void shouldRejectBitTypeAndWidthCollisions() {
        for (String address : new String[]{"DB1.DBX0.2", "DB1:0.2", "M1.2", "DB1.DBX0.2:REAL", "DB1:0.2:REAL"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> S7AddressParser.parse(point(address, "REAL", Map.of())), address);
        }
        for (String address : new String[]{"DB1.DBW4:REAL", "DB1.DBB4:INT", "DB1.DBD4:INT", "MW4:REAL",
                "DB1.DBW4", "DB1.DBB4"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> S7AddressParser.parse(point(address, "REAL", Map.of())), address);
        }
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse("DB1.DBX0.8:BOOL"));
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse("DB1:0.8:BOOL"));
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse("M1.8"));
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse("DB1.DBX0:BOOL"));
        assertThrows(IllegalArgumentException.class, () -> S7AddressParser.parse(
                point("DB1.DBD4:REAL", "FLOAT32", Map.of("driverDataType", "INT"))));
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
