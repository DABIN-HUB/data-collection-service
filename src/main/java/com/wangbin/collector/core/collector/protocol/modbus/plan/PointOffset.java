package com.wangbin.collector.core.collector.protocol.modbus.plan;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 定义当前模块的业务组件。
 */
@Getter
@AllArgsConstructor
public class PointOffset {

    private final String pointId;
    private final int offset;       // 相对起始寄存器偏移（单位：寄存器）
    private final String dataType;

}
