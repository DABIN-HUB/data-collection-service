package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;

import java.util.Objects;

/**
 * PLC4X Modbus 标签构造器。
 *
 * <p>寄存器统一使用无符号 16 位类型承载原始数据，点位业务类型由上层解析。</p>
 */
final class Plc4xModbusTagBuilder {

    private static final String BIT_DATA_TYPE = "BOOL";
    private static final String REGISTER_DATA_TYPE = "UINT";

    private Plc4xModbusTagBuilder() {
    }

    static String build(RegisterType registerType, int zeroBasedAddress, int quantity, int unitId) {
        Objects.requireNonNull(registerType, "registerType 不能为空");
        if (zeroBasedAddress < 0) {
            throw new IllegalArgumentException("zeroBasedAddress 不能小于 0");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }

        int logicalAddress = zeroBasedAddress + 1;
        String area = resolveArea(registerType);
        String dataType = resolveDataType(registerType);
        String quantityPart = quantity > 1 ? "[" + quantity + "]" : "";
        return area + ":" + logicalAddress + ":" + dataType + quantityPart + "{unit-id: " + unitId + "}";
    }

    private static String resolveArea(RegisterType registerType) {
        return switch (registerType) {
            case COIL -> "coil";
            case DISCRETE_INPUT -> "discrete-input";
            case HOLDING_REGISTER -> "holding-register";
            case INPUT_REGISTER -> "input-register";
        };
    }

    private static String resolveDataType(RegisterType registerType) {
        return switch (registerType) {
            case COIL, DISCRETE_INPUT -> BIT_DATA_TYPE;
            case HOLDING_REGISTER, INPUT_REGISTER -> REGISTER_DATA_TYPE;
        };
    }
}
