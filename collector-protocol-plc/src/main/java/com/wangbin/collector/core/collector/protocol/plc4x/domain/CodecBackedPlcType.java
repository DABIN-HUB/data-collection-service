package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import org.apache.plc4x.java.api.value.PlcValue;

/**
 * 定义当前模块的业务契约。
 */
public interface CodecBackedPlcType<C extends CollectorValueCodec> extends PlcTypeDescriptor {

    C getCodec();

    /**
     * 查询并返回业务数据。
     */
    @Override
    default Object read(PlcValue plcValue) {
        return getCodec().read(plcValue);
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    default Object write(Object value) {
        return getCodec().write(value);
    }
}