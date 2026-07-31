package com.wangbin.collector.core.connection.adapter;

/**
 * 自定义请求响应协议的统一传输接口。
 */
public interface CustomExchangeAdapter {

    /**
     * 执行当前业务逻辑。
     */
    byte[] exchange(byte[] request, long timeoutMs) throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    void sendOnly(byte[] request) throws Exception;
}
