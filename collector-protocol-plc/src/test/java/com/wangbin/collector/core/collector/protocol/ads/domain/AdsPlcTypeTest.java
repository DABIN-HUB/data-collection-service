package com.wangbin.collector.core.collector.protocol.ads.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void shouldRejectMalformedStringTypeExpressions() {
        for (String type : new String[]{"STRING(", "STRING(abc)", "STRING(0)", "STRING(1000)", "WSTRING(1)garbage"}) {
            assertThrows(IllegalArgumentException.class, () -> AdsPlcType.fromDriverText(type));
        }
    }
}