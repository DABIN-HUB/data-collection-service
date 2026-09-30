package com.wangbin.collector.core.collector.protocol.ethernetip.domain;

import org.apache.plc4x.java.api.value.PlcValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtherNetIpPlcTypeTest {
    @Test
    void mapsPlatformAliasesWithoutChangingExplicitDriverIdentity() {
        assertEquals(EtherNetIpPlcType.UINT, EtherNetIpPlcType.fromPlatformDataType("WORD"));
        assertEquals(EtherNetIpPlcType.UDINT, EtherNetIpPlcType.fromPlatformDataType("DWORD"));
        assertEquals(EtherNetIpPlcType.WORD, EtherNetIpPlcType.fromDriverText("WORD"));
        assertEquals(EtherNetIpPlcType.STRING, EtherNetIpPlcType.fromDriverText("WCHAR"));
    }

    @Test
    void unsignedWidthsDoNotWrapWhenWriting() {
        assertEquals((short) 255, EtherNetIpPlcType.USINT.write("255"));
        assertEquals(65535, EtherNetIpPlcType.UINT.write("65535"));
        assertEquals(4294967295L, EtherNetIpPlcType.UDINT.write("4294967295"));
        assertEquals(new BigInteger("18446744073709551615"), EtherNetIpPlcType.ULINT.write("18446744073709551615"));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpPlcType.USINT.write(256));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpPlcType.UINT.write(-1));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpPlcType.UDINT.write(4294967296L));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpPlcType.ULINT.write(new BigInteger("18446744073709551616")));
    }

    @Test
    void signedWidthsRejectOverflowAndFractions() {
        assertEquals((byte) -128, EtherNetIpPlcType.SINT.write("-128"));
        assertEquals((short) -32768, EtherNetIpPlcType.INT.write("-32768"));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpPlcType.INT.write(32768));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpPlcType.DINT.write(1.5));
    }

    @Test
    void readsUnsignedPlcValuesWithoutTruncation() {
        PlcValue value = mock(PlcValue.class);
        when(value.getObject()).thenReturn(new BigInteger("18446744073709551615"));
        assertEquals(new BigInteger("18446744073709551615"), EtherNetIpPlcType.ULINT.read(value));
        when(value.getObject()).thenReturn(4294967295L);
        assertEquals(4294967295L, EtherNetIpPlcType.UDINT.read(value));
    }
}
