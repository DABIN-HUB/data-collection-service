package com.wangbin.collector.core.collector.protocol.ads.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdsPlcTypeTest {

    @Test
    void shouldNormalizeDriverAliases() {
        assertEquals(AdsPlcType.BYTE, AdsPlcType.fromDriverText("CHAR"));
        assertEquals(AdsPlcType.UDINT, AdsPlcType.fromDriverText("DWORD"));
        assertEquals(AdsPlcType.LREAL, AdsPlcType.fromDriverText("DOUBLE_SWAP"));
        assertEquals(AdsPlcType.STRING, AdsPlcType.fromDriverText("STRING(32)"));
        assertEquals(AdsPlcType.WSTRING, AdsPlcType.fromDriverText("WSTRING(64)"));
    }

    @Test
    void shouldNormalizePlatformAliases() {
        assertEquals(AdsPlcType.SINT, AdsPlcType.fromPlatformDataType("BYTE"));
        assertEquals(AdsPlcType.BYTE, AdsPlcType.fromPlatformDataType("CHAR"));
        assertEquals(AdsPlcType.UINT, AdsPlcType.fromPlatformDataType("WORD"));
    }
}