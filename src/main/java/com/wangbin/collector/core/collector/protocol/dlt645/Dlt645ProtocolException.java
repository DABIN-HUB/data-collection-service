package com.wangbin.collector.core.collector.protocol.dlt645;

/**
 * DL/T 645 协议处理异常。
 */
public class Dlt645ProtocolException extends Exception {

    public Dlt645ProtocolException(String message) {
        super(message);
    }

    public Dlt645ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
