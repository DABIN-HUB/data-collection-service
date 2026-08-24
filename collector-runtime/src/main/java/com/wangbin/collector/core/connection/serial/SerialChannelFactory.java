package com.wangbin.collector.core.connection.serial;

/**
 * 串口通道创建工厂，便于生产通道和内存测试通道替换。
 */
@FunctionalInterface
public interface SerialChannelFactory {

    /**
     * 创建并返回业务对象。
     */
    SerialChannel create(SerialEndpoint endpoint);
}
