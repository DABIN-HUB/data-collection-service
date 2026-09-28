package com.wangbin.collector.core.collector.protocol.mc.codec;

/** A successful MC read whose data section cannot cover the requested units. */
public class McPayloadLengthException extends IllegalArgumentException {

    public McPayloadLengthException(String context, int expected, int actual) {
        super("MC " + context + " payload is shorter than expected: expected="
                + expected + ", actual=" + actual);
    }
}
