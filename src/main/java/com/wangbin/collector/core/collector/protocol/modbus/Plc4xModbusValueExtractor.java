package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.value.PlcValue;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * PLC4X Modbus 响应值提取器。
 */
final class Plc4xModbusValueExtractor {

    private Plc4xModbusValueExtractor() {
    }

    static byte[] registerBytes(PlcReadResponse response, String fieldName, int quantity) {
        List<Object> values = extractObjects(response, fieldName);
        if (values.size() == 1 && values.get(0) instanceof byte[] rawBytes) {
            return copyRegisterBytes(rawBytes, quantity, fieldName);
        }
        if (values.size() < quantity) {
            throw new IllegalStateException("PLC4X register response size mismatch, field="
                    + fieldName + ", expected=" + quantity + ", actual=" + values.size());
        }

        ByteBuffer buffer = ByteBuffer.allocate(quantity * 2).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < quantity; i++) {
            Number number = toNumber(values.get(i), fieldName, i);
            buffer.putShort((short) (number.intValue() & 0xFFFF));
        }
        return buffer.array();
    }

    static byte[] coilBytes(PlcReadResponse response, String fieldName, int quantity, Parity parity) {
        List<Object> rawValues = extractObjects(response, fieldName);
        if (rawValues.size() < quantity) {
            throw new IllegalStateException("PLC4X coil response size mismatch, field="
                    + fieldName + ", expected=" + quantity + ", actual=" + rawValues.size());
        }

        List<Boolean> values = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            values.add(toBoolean(rawValues.get(i)));
        }
        return ModbusUtils.buildCoilBytes(values, parity);
    }

    private static List<Object> extractObjects(PlcReadResponse response, String fieldName) {
        if (response == null) {
            return List.of();
        }
        List<Object> values = new ArrayList<>();
        collectValue(response.getPlcValue(fieldName), values);
        if (values.isEmpty()) {
            collectValue(response.getAllObjects(fieldName), values);
        }
        return values;
    }

    private static void collectValue(Object value, List<Object> values) {
        if (value == null) {
            return;
        }
        if (value instanceof PlcValue plcValue) {
            collectPlcValue(plcValue, values);
            return;
        }
        if (value instanceof byte[] rawBytes) {
            values.add(rawBytes);
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectValue(item, values);
            }
            return;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectValue(Array.get(value, i), values);
            }
            return;
        }
        values.add(value);
    }

    private static void collectPlcValue(PlcValue plcValue, List<Object> values) {
        if (plcValue == null || plcValue.isNull()) {
            return;
        }
        if (plcValue.isList()) {
            for (PlcValue item : plcValue.getList()) {
                collectPlcValue(item, values);
            }
            return;
        }
        Object object = plcValue.getObject();
        if (object == plcValue) {
            values.add(readScalarValue(plcValue));
        } else {
            collectValue(object, values);
        }
    }

    private static Object readScalarValue(PlcValue plcValue) {
        if (plcValue.isBoolean()) {
            return plcValue.getBoolean();
        }
        if (plcValue.isByte()) {
            return plcValue.getByte();
        }
        if (plcValue.isShort()) {
            return plcValue.getShort();
        }
        if (plcValue.isInteger()) {
            return plcValue.getInteger();
        }
        if (plcValue.isLong()) {
            return plcValue.getLong();
        }
        if (plcValue.isBigInteger()) {
            return plcValue.getBigInteger();
        }
        if (plcValue.isFloat()) {
            return plcValue.getFloat();
        }
        if (plcValue.isDouble()) {
            return plcValue.getDouble();
        }
        if (plcValue.isBigDecimal()) {
            return plcValue.getBigDecimal();
        }
        if (plcValue.isString()) {
            return plcValue.getString();
        }
        return plcValue.getRaw();
    }

    private static byte[] copyRegisterBytes(byte[] rawBytes, int quantity, String fieldName) {
        int expectedBytes = quantity * 2;
        if (rawBytes.length < expectedBytes) {
            throw new IllegalStateException("PLC4X raw register response size mismatch, field="
                    + fieldName + ", expectedBytes=" + expectedBytes + ", actualBytes=" + rawBytes.length);
        }
        return Arrays.copyOf(rawBytes, expectedBytes);
    }

    private static Number toNumber(Object value, String fieldName, int index) {
        if (value instanceof PlcValue plcValue) {
            return toNumber(plcValue.getObject(), fieldName, index);
        }
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String text) {
            return Integer.parseInt(text.trim());
        }
        throw new IllegalStateException("PLC4X register response contains non-numeric value, field="
                + fieldName + ", index=" + index + ", value=" + value);
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof PlcValue plcValue) {
            return toBoolean(plcValue.getObject());
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
