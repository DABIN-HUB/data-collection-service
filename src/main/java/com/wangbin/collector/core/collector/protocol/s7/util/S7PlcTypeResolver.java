package com.wangbin.collector.core.collector.protocol.s7.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.plc4x.util.PointTypeResolver;
import com.wangbin.collector.core.collector.protocol.plc4x.util.PointTypeResolverSupport;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7PlcType;

public enum S7PlcTypeResolver implements PointTypeResolver<S7Address, S7PlcType> {
    INSTANCE;

    private static final String[] DRIVER_TYPE_FIELDS = {"driverDataType", "s7Type", "plc4xType", "plcType"};

    @Override
    public S7PlcType resolveOrNull(DataPoint point, S7Address address) {
        return PointTypeResolverSupport.resolveOrNull(
                point,
                address,
                S7Address::getPlcType,
                S7PlcType::fromText,
                S7PlcType::fromText,
                null,
                true,
                DRIVER_TYPE_FIELDS
        );
    }
}