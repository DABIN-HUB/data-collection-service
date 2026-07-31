package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import org.apache.plc4x.java.api.value.PlcValue;

/**
 * 定义当前模块的业务契约。
 */
public interface CollectorValueCodec {

    /**
     * 查询并返回业务数据。
     */
    Object read(PlcValue plcValue);

    /**
     * 写入或持久化业务数据。
     */
    Object write(Object value);
}