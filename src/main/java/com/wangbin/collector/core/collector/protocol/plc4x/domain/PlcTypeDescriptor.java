package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import org.apache.plc4x.java.api.value.PlcValue;

public interface PlcTypeDescriptor {

    Object read(PlcValue plcValue);

    Object write(Object value);

    default String toTypeExpression() {
        if (this instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return toString();
    }
}