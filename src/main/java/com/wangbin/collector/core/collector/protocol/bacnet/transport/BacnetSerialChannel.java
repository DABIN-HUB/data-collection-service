package com.wangbin.collector.core.collector.protocol.bacnet.transport;

public interface BacnetSerialChannel extends AutoCloseable {

    void open() throws Exception;

    boolean isOpen();

    void write(byte[] data) throws Exception;

    int read(byte[] buffer, int offset, int length, long timeoutMs) throws Exception;

    @Override
    void close() throws Exception;
}