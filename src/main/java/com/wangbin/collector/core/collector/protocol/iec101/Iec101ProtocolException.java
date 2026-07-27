package com.wangbin.collector.core.collector.protocol.iec101;

/**
 * IEC101 协议处理异常。
 */
public class Iec101ProtocolException extends Exception {

    public Iec101ProtocolException(String message) {
        super(message);
    }

    public Iec101ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
