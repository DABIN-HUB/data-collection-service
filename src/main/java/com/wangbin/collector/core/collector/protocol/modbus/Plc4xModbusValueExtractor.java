package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.value.PlcValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * PLC4X Modbus 响应值提取器。
 */
final class Plc4xModbusValueExtractor {

    private Plc4xModbusValueExtractor() {
    }

    static byte[] registerBytes(PlcReadResponse response, String fieldName, int quantity) {
        List<? extends PlcValue> values = extractValues(response, fieldName, quantity, "register");

        ByteBuffer buffer = ByteBuffer.allocate(quantity * 2).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < quantity; i++) {
            Number number = toNumber(values.get(i), fieldName, i);
            buffer.putShort((short) (number.intValue() & 0xFFFF));
        }
        return buffer.array();
    }

    static byte[] coilBytes(PlcReadResponse response, String fieldName, int quantity, Parity parity) {
        List<? extends PlcValue> rawValues = extractValues(response, fieldName, quantity, "coil");

        List<Boolean> values = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            values.add(toBoolean(rawValues.get(i)));
        }
        return ModbusUtils.buildCoilBytes(values, parity);
    }

    private static List<? extends PlcValue> extractValues(
            PlcReadResponse response, String fieldName, int expectedQuantity, String valueType) {
        if (response == null) {
            throw new IllegalStateException("PLC4X " + valueType + " response cannot be null");
        }
        PlcValue root = response.getPlcValue(fieldName);
        if (root == null || root.isNull()) {
            throw new IllegalStateException("PLC4X " + valueType + " response value cannot be null, field="
                    + fieldName);
        }
        List<? extends PlcValue> values = root.isList() ? root.getList() : List.of(root);
        if (values.size() != expectedQuantity) {
            throw new IllegalStateException("PLC4X " + valueType + " response size mismatch, field="
                    + fieldName + ", expected=" + expectedQuantity + ", actual=" + values.size());
        }
        return values;
    }

    private static Number toNumber(PlcValue plcValue, String fieldName, int index) {
        Object value = plcValue.getObject();
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String text) {
            return Integer.parseInt(text.trim());
        }
        throw new IllegalStateException("PLC4X register response contains non-numeric value, field="
                + fieldName + ", index=" + index + ", value=" + value);
    }

    private static Boolean toBoolean(PlcValue plcValue) {
        Object value = plcValue.getObject();
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
