package com.wangbin.collector.core.collector.protocol.modbus.domain;

import java.util.Objects;

/**
 * 定义当前模块的业务组件。
 */
public class ModbusAddress {
    private final RegisterType registerType;
    private final int address;

    /**
     * 创建当前组件实例。
     */
    public ModbusAddress(RegisterType registerType, int address) {
        this.registerType = registerType;
        this.address = address;
    }

    public RegisterType getRegisterType() {
        return registerType;
    }

    public int getAddress() {
        return address;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModbusAddress that = (ModbusAddress) o;
        return address == that.address && registerType == that.registerType;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public int hashCode() {
        return Objects.hash(registerType, address);
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public String toString() {
        return registerType + ":" + address;
    }
}
