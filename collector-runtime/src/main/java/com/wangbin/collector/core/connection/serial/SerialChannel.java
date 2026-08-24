package com.wangbin.collector.core.connection.serial;

/**
 * 协议无关的串口字节通道。
 */
public interface SerialChannel extends AutoCloseable {

    /**
     * 执行当前业务逻辑。
     */
    void open() throws Exception;

    boolean isOpen();

    /**
     * 写入或持久化业务数据。
     */
    void write(byte[] data) throws Exception;

    /**
     * 查询并返回业务数据。
     */
    int read(byte[] buffer, int offset, int length, long timeoutMs) throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    @Override
    void close() throws Exception;
}
