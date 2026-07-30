package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import org.apache.plc4x.java.api.value.PlcValue;

public interface CodecBackedPlcType<C extends CollectorValueCodec> extends PlcTypeDescriptor {

    C getCodec();

    @Override
    default Object read(PlcValue plcValue) {
        return getCodec().read(plcValue);
    }

    @Override
    default Object write(Object value) {
        return getCodec().write(value);
    }
}