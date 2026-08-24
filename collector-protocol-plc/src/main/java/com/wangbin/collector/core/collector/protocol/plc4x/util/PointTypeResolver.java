package com.wangbin.collector.core.collector.protocol.plc4x.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.PlcTypeDescriptor;

/**
 * 定义当前模块的业务契约。
 */
public interface PointTypeResolver<A, T extends PlcTypeDescriptor> {

    /**
     * 解析或转换业务数据。
     */
    T resolveOrNull(DataPoint point, A address);

    /**
     * 解析或转换业务数据。
     */
    default T resolveRequired(DataPoint point, A address, String message) {
        T resolved = resolveOrNull(point, address);
        if (resolved == null) {
            throw new IllegalArgumentException(message);
        }
        return resolved;
    }
}