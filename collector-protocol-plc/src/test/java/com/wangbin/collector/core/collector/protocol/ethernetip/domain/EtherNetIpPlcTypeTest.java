package com.wangbin.collector.core.collector.protocol.ethernetip.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EtherNetIpPlcTypeTest {

    @Test
    void shouldNormalizeDriverAliases() {
        assertEquals(EtherNetIpPlcType.STRING, EtherNetIpPlcType.fromDriverText("WCHAR"));
        assertEquals(EtherNetIpPlcType.DWORD, EtherNetIpPlcType.fromDriverText("DWORD"));
        assertEquals(EtherNetIpPlcType.LWORD, EtherNetIpPlcType.fromDriverText("LWORD"));
        assertEquals(EtherNetIpPlcType.REAL, EtherNetIpPlcType.fromDriverText("FLOAT32_LITTLE"));
    }

    @Test
    void shouldNormalizePlatformAliases() {
        assertEquals(EtherNetIpPlcType.UINT, EtherNetIpPlcType.fromPlatformDataType("WORD"));
        assertEquals(EtherNetIpPlcType.UDINT, EtherNetIpPlcType.fromPlatformDataType("DWORD"));
        assertEquals(EtherNetIpPlcType.STRING, EtherNetIpPlcType.fromPlatformDataType("CHAR"));
    }
}