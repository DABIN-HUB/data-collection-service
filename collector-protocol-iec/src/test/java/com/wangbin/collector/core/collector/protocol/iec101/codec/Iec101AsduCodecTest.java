package com.wangbin.collector.core.collector.protocol.iec101.codec;

import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101Asdu;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101LinkConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Iec101AsduCodecTest {

    private static final Iec101LinkConfig CONFIG = new Iec101LinkConfig(1, 2, 2, 3);

    @Test
    void shouldDecodeSequenceMeasuredValues() {
        byte[] asdu = new byte[]{
                11, (byte) 0x82, 3, 0, 1, 0,
                100, 0, 0,
                10, 0, 0,
                20, 0, (byte) 0x80
        };

        Iec101Asdu decoded = Iec101AsduCodec.decode(asdu, CONFIG);

        assertTrue(decoded.sequence());
        assertEquals(2, decoded.informationObjects().size());
        assertEquals(100, decoded.informationObjects().get(0).address());
        assertEquals(101, decoded.informationObjects().get(1).address());
        assertEquals(10L, decoded.informationObjects().get(0).value());
        assertEquals(0, decoded.informationObjects().get(1).quality());
    }

    @Test
    void shouldEncodeSelectAndExecuteSingleCommand() {
        byte[] select = Iec101AsduCodec.encodeCommand(45, 1, 7, true, true, 3, CONFIG);
        byte[] execute = Iec101AsduCodec.encodeCommand(45, 1, 7, true, false, 3, CONFIG);

        Iec101Asdu selected = Iec101AsduCodec.decode(select, CONFIG);
        Iec101Asdu executed = Iec101AsduCodec.decode(execute, CONFIG);

        int selectedElement = (Integer) selected.informationObjects().get(0).value();
        int executedElement = (Integer) executed.informationObjects().get(0).value();
        assertTrue((selectedElement & 0x80) != 0);
        assertFalse((executedElement & 0x80) != 0);
        assertEquals(3, (selectedElement >>> 2) & 0x1F);
    }
}
