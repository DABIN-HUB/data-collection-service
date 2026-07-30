package com.wangbin.collector.core.collector.protocol.iec.util;

import com.wangbin.collector.core.collector.protocol.iec.domain.Iec104Address;
import com.wangbin.collector.core.collector.protocol.iec.domain.Iec104Type;
import org.junit.jupiter.api.Test;
import org.openmuc.j60870.ASduType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Iec104UtilsTest {

    @Test
    void shouldParseNamedTypeAddress() {
        Iec104Address address = Iec104Utils.parseAddress("2", "M_ME_NC_1:15");

        assertEquals(2, address.getCommonAddress());
        assertEquals(15, address.getIoAddress());
        assertEquals(Integer.valueOf(13), address.getTypeId());
    }

    @Test
    void shouldCanonicalizeTimedVariantTypeIds() {
        Iec104Address address = Iec104Utils.parseAddress("1", "36:8");

        assertEquals(Integer.valueOf(36), address.getTypeId());
        assertEquals(Integer.valueOf(13), Iec104Utils.resolveTypeId(ASduType.M_ME_TC_1));
        assertEquals(Integer.valueOf(13), Iec104Utils.resolveTypeId(ASduType.M_ME_TF_1));
        assertEquals(Integer.valueOf(3), Iec104Utils.resolveTypeIdToken("DOUBLE_POINT"));
        assertEquals(Integer.valueOf(9), Iec104Utils.resolveTypeIdToken("M_ME_TD_1"));
        assertEquals(Integer.valueOf(34), Iec104Utils.resolveRawTypeIdToken("M_ME_TD_1"));
        assertEquals(Integer.valueOf(5), Iec104Utils.resolveTypeIdToken("STEP_POSITION"));
        assertEquals(Integer.valueOf(15), Iec104Utils.resolveTypeIdToken("M_IT_TB_1"));
        assertEquals(Integer.valueOf(51), Iec104Utils.resolveTypeIdToken("BIT_STRING_COMMAND"));
        assertEquals(Integer.valueOf(64), Iec104Utils.resolveRawTypeIdToken("C_BO_TA_1"));
        assertEquals(Iec104Type.C_SE_TC_1, Iec104Utils.resolveType("C_SE_TC_1"));
        assertEquals(Iec104Type.TimestampKind.CP56, Iec104Utils.resolveType("63").timestampKind());
    }

    @Test
    void shouldRejectUnknownTypeToken() {
        assertThrows(IllegalArgumentException.class, () -> Iec104Utils.parseAddress("1", "UNKNOWN_TYPE:9"));
    }

    @Test
    void shouldRequireTypedAddressWhenRequested() {
        assertThrows(IllegalArgumentException.class,
                () -> Iec104Utils.parseTypedAddress("1", "11", "write"));
    }

    @Test
    void shouldParseFlexibleWriteValues() {
        assertEquals(true, Iec104Utils.parseBooleanValue("1.0"));
        assertEquals(15, Iec104Utils.parseIntegerValue("0x0f"));
        assertEquals(2.5f, Iec104Utils.parseFloatValue("2.5"));
        assertEquals(2, Iec104Utils.parseStepCommandState("RAISE").getId());
        assertEquals(2, Iec104Utils.parseDoubleCommandState("close").getId());
    }
}
