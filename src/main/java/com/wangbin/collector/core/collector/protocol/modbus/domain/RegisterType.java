package com.wangbin.collector.core.collector.protocol.modbus.domain;

/**
 * 定义当前模块的枚举值。
 */
public enum RegisterType {
    COIL(0, "线圈"),
    DISCRETE_INPUT(1, "离散输入"),
    INPUT_REGISTER(3, "输入寄存器"),
    HOLDING_REGISTER(4, "保持寄存器"),
    ;

    private final int code;
    private final String description;

    /**
     * 创建当前组件实例。
     */
    RegisterType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 创建并返回业务对象。
     */
    public static RegisterType fromCode(int code) {
        for (RegisterType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
