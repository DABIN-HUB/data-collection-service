package com.wangbin.collector.core.connection.adapter;

/**
 * 自定义请求响应协议的统一传输接口。
 */
public interface CustomExchangeAdapter {

    byte[] exchange(byte[] request, long timeoutMs) throws Exception;

    void sendOnly(byte[] request) throws Exception;
}
