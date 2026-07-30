package com.wangbin.collector.core.collector.protocol.opc.plc4x.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.domain.Plc4xOpcUaAddress;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.domain.Plc4xOpcUaType;
import com.wangbin.collector.core.collector.protocol.plc4x.util.PointTypeResolver;
import com.wangbin.collector.core.collector.protocol.plc4x.util.PointTypeResolverSupport;

public enum Plc4xOpcUaTypeResolver implements PointTypeResolver<Plc4xOpcUaAddress, Plc4xOpcUaType> {
    INSTANCE;

    private static final String[] DRIVER_TYPE_FIELDS = {"driverDataType", "opcUaType", "opcType", "nodeType", "dataType"};

    @Override
    public Plc4xOpcUaType resolveOrNull(DataPoint point, Plc4xOpcUaAddress address) {
        return PointTypeResolverSupport.resolveOrNull(
                point,
                address,
                Plc4xOpcUaAddress::getDataType,
                Plc4xOpcUaType::fromDriverTextOrNull,
                Plc4xOpcUaType::fromDriverTextOrNull,
                null,
                true,
                DRIVER_TYPE_FIELDS
        );
    }
}