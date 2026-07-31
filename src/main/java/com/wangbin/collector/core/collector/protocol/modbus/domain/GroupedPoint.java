package com.wangbin.collector.core.collector.protocol.modbus.domain;

import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.Data;

/**
 * 定义当前模块的业务组件。
 */
@Data
public class GroupedPoint {
    final ModbusAddress address;
    final DataPoint point;

    /**
     * 创建当前组件实例。
     */
    public GroupedPoint(ModbusAddress address, DataPoint point) {
        this.address = address;
        this.point = point;
    }
}
