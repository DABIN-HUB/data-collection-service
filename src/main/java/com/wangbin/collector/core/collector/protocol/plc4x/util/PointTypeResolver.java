package com.wangbin.collector.core.collector.protocol.plc4x.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.PlcTypeDescriptor;

public interface PointTypeResolver<A, T extends PlcTypeDescriptor> {

    T resolveOrNull(DataPoint point, A address);

    default T resolveRequired(DataPoint point, A address, String message) {
        T resolved = resolveOrNull(point, address);
        if (resolved == null) {
            throw new IllegalArgumentException(message);
        }
        return resolved;
    }
}