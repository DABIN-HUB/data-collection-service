package com.wangbin.collector.core.collector.protocol.ads.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmsNetIdParserTest {

    @Test
    void shouldNormalizeValidAmsNetId() {
        assertEquals("1.2.3.4.5.6", AmsNetIdParser.parse("1.2.3.4.5.6"));
        assertTrue(AmsNetIdParser.isValid("1.2.3.4.5.6"));
    }

    @Test
    void shouldRejectInvalidAmsNetId() {
        assertFalse(AmsNetIdParser.isValid("1.2.3.4.5"));
        assertThrows(IllegalArgumentException.class, () -> AmsNetIdParser.parse("1.2.3.4.5.999"));
    }
}
