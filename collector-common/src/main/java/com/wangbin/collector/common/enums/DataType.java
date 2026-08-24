package com.wangbin.collector.common.enums;

import lombok.Getter;

/**
 * 定义当前模块的枚举值。
 */
@Getter
public enum DataType {
    INT(4),
    INT16(2), UINT16(2),
    INT32(4), UINT32(4),
    FLOAT(4), FLOAT32(4), FLOAT32_SWAP(4), FLOAT32_LITTLE(4),
    FLOAT64(8), INT64(8), UINT64(8),
    BOOLEAN(1), STRING(1),
    DOUBLE(8), DOUBLE_SWAP(8), FLOAT64_SWAP(8), FLOAT64_LITTLE(8),
    ;

    /**
     * 数据类型最小字节长度。
     */
    private final int minBytes;

    /**
     * 创建当前组件实例。
     */
    DataType(int minBytes) {
        this.minBytes = minBytes;
    }

    /**
     * 返回数据类型对应的寄存器数量（Modbus寄存器每个2字节）
     */
    public int getRegisterCount() {
        return (int) Math.ceil((double) minBytes / 2);
    }

    /**
     * 根据字符串获取枚举
     */
    public static DataType fromString(String type) {
        if (type == null || type.trim().isEmpty()) {
            return INT16;
        }
        try {
            return DataType.valueOf(type.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return INT16;
        }
    }
}
