package com.wangbin.collector.core.connection.serial;

/**
 * 串口通道创建工厂，便于生产通道和内存测试通道替换。
 */
@FunctionalInterface
public interface SerialChannelFactory {

    SerialChannel create(SerialEndpoint endpoint);
}
