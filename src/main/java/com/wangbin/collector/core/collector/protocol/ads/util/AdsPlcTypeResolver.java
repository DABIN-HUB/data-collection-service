package com.wangbin.collector.core.collector.protocol.ads.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsAddress;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsPlcType;
import com.wangbin.collector.core.collector.protocol.plc4x.util.PointTypeResolver;
import com.wangbin.collector.core.collector.protocol.plc4x.util.PointTypeResolverSupport;

/**
 * 定义当前模块的枚举值。
 */
public enum AdsPlcTypeResolver implements PointTypeResolver<AdsAddress, AdsPlcType> {
    INSTANCE;

    private static final String[] DRIVER_TYPE_FIELDS = {"driverDataType", "adsType", "plc4xType", "plcType"};

    /**
     * 解析或转换业务数据。
     */
    @Override
    public AdsPlcType resolveOrNull(DataPoint point, AdsAddress address) {
        return PointTypeResolverSupport.resolveOrNull(
                point,
                address,
                AdsAddress::getPlcType,
                AdsPlcType::fromDriverText,
                AdsPlcType::fromDriverText,
                AdsPlcType::fromPlatformDataType,
                false,
                DRIVER_TYPE_FIELDS
        );
    }
}