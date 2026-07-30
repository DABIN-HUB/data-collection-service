package com.wangbin.collector.core.collector.protocol.s7.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S7PlcTypeTest {

    @Test
    void shouldNormalizeAliasesToCanonicalS7Types() {
        assertEquals(S7PlcType.USINT, S7PlcType.fromText("BYTE"));
        assertEquals(S7PlcType.REAL, S7PlcType.fromText("FLOAT32_SWAP"));
        assertEquals(S7PlcType.LREAL, S7PlcType.fromText("DOUBLE"));
        assertEquals(S7PlcType.STRING, S7PlcType.fromText("STRING(32)"));
        assertEquals(S7PlcType.WSTRING, S7PlcType.fromText("WSTRING(64)"));
    }
}
