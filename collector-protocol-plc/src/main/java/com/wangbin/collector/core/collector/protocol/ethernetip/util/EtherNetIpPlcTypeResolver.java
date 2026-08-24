package com.wangbin.collector.core.collector.protocol.ethernetip.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpPlcType;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;
import com.wangbin.collector.core.collector.protocol.plc4x.util.PointTypeResolver;
import com.wangbin.collector.core.collector.protocol.plc4x.util.PointTypeResolverSupport;

/**
 * 定义当前模块的枚举值。
 */
public enum EtherNetIpPlcTypeResolver implements PointTypeResolver<EtherNetIpTagAddress, EtherNetIpPlcType> {
    INSTANCE;

    private static final String[] DRIVER_TYPE_FIELDS = {"driverDataType", "eipType", "logixType", "plc4xType", "plcType"};

    /**
     * 解析或转换业务数据。
     */
    @Override
    public EtherNetIpPlcType resolveOrNull(DataPoint point, EtherNetIpTagAddress address) {
        return PointTypeResolverSupport.resolveOrNull(
                point,
                address,
                EtherNetIpTagAddress::getPlcType,
                EtherNetIpPlcType::fromDriverText,
                EtherNetIpPlcType::fromDriverText,
                EtherNetIpPlcType::fromPlatformDataType,
                false,
                DRIVER_TYPE_FIELDS
        );
    }
}