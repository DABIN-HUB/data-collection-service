package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.value.PlcValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * PLC4X Modbus 响应值提取器。
 */
final class Plc4xModbusValueExtractor {

    /**
     * 创建当前组件实例。
     */
    private Plc4xModbusValueExtractor() {
    }

    /**
     * 维护注册或订阅关系。
     */
    static byte[] registerBytes(PlcReadResponse response, String fieldName, int quantity) {
        List<? extends PlcValue> values = extractValues(response, fieldName, quantity, "register");

        ByteBuffer buffer = ByteBuffer.allocate(quantity * 2).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < quantity; i++) {
            Number number = toNumber(values.get(i), fieldName, i);
            buffer.putShort((short) (number.intValue() & 0xFFFF));
        }
        return buffer.array();
    }

    /**
     * 执行当前业务逻辑。
     */
    static byte[] coilBytes(PlcReadResponse response, String fieldName, int quantity, Parity parity) {
        PlcValue root = extractRootValue(response, fieldName, "coil");
        List<PlcValue> rawValues = new ArrayList<>(quantity);
        flattenValues(root, rawValues);

        if (rawValues.size() == 1 && quantity > 1) {
            byte[] packedBytes = toPackedCoilBytes(rawValues.get(0).getObject(), quantity, parity);
            if (packedBytes != null) {
                return packedBytes;
            }
        }

        validateQuantity(rawValues, fieldName, quantity, "coil");

        List<Boolean> values = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            values.add(toBoolean(rawValues.get(i)));
        }
        return ModbusUtils.buildCoilBytes(values, parity);
    }

    /**
     * 解析或转换业务数据。
     */
    private static List<? extends PlcValue> extractValues(
            PlcReadResponse response, String fieldName, int expectedQuantity, String valueType) {
        PlcValue root = extractRootValue(response, fieldName, valueType);
        List<PlcValue> values = new ArrayList<>(expectedQuantity);
        flattenValues(root, values);
        validateQuantity(values, fieldName, expectedQuantity, valueType);
        return values;
    }

    /**
     * 提取 PLC4X 字段根值。
     */
    private static PlcValue extractRootValue(PlcReadResponse response, String fieldName, String valueType) {
        if (response == null) {
            throw new IllegalStateException("PLC4X " + valueType + " response cannot be null");
        }
        PlcValue root = response.getPlcValue(fieldName);
        if (root == null || root.isNull()) {
            throw new IllegalStateException("PLC4X " + valueType + " response value cannot be null, field="
                    + fieldName);
        }
        return root;
    }

    /**
     * 校验 PLC4X 返回值数量。
     */
    private static void validateQuantity(List<? extends PlcValue> values,
                                         String fieldName,
                                         int expectedQuantity,
                                         String valueType) {
        if (values.size() != expectedQuantity) {
            throw new IllegalStateException("PLC4X " + valueType + " response size mismatch, field="
                    + fieldName + ", expected=" + expectedQuantity + ", actual=" + values.size());
        }
    }

    /**
     * 递归展开 PLC4X 返回的嵌套列表。
     */
    private static void flattenValues(PlcValue value, List<PlcValue> target) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isList()) {
            target.add(value);
            return;
        }
        for (PlcValue nestedValue : value.getList()) {
            flattenValues(nestedValue, target);
        }
    }

    /**
     * 将 PLC4X 单值 packed 位域转换为 Modbus 线圈字节。
     */
    private static byte[] toPackedCoilBytes(Object value, int quantity, Parity parity) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return ensureByteCapacity(bytes, quantity);
        }
        if (value instanceof Byte[] bytes) {
            byte[] raw = new byte[bytes.length];
            for (int index = 0; index < bytes.length; index++) {
                raw[index] = bytes[index] == null ? 0 : bytes[index];
            }
            return ensureByteCapacity(raw, quantity);
        }
        if (value instanceof Number number) {
            return numberToPackedBytes(number, quantity);
        }
        if (value instanceof List<?> list) {
            return listToPackedBytes(list, quantity, parity);
        }
        if (value.getClass().isArray()) {
            return arrayToPackedBytes(value, quantity, parity);
        }
        return null;
    }

    /**
     * 按线圈数量补齐 packed 字节长度。
     */
    private static byte[] ensureByteCapacity(byte[] bytes, int quantity) {
        int byteCount = Math.max(1, (quantity + 7) / 8);
        byte[] raw = new byte[byteCount];
        System.arraycopy(bytes, 0, raw, 0, Math.min(bytes.length, raw.length));
        return raw;
    }

    /**
     * 将数字按 Modbus 位序拆成 packed 字节。
     */
    private static byte[] numberToPackedBytes(Number number, int quantity) {
        byte[] raw = new byte[Math.max(1, (quantity + 7) / 8)];
        long packedValue = number.longValue();
        for (int index = 0; index < raw.length; index++) {
            raw[index] = (byte) ((packedValue >> (index * 8)) & 0xFF);
        }
        return raw;
    }

    /**
     * 将列表按布尔列表或字节列表转换为 packed 字节。
     */
    private static byte[] listToPackedBytes(List<?> list, int quantity, Parity parity) {
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() == quantity && list.stream().allMatch(Boolean.class::isInstance)) {
            List<Boolean> booleans = new ArrayList<>(quantity);
            for (Object item : list) {
                booleans.add((Boolean) item);
            }
            return ModbusUtils.buildCoilBytes(booleans, parity);
        }
        if (list.stream().allMatch(Number.class::isInstance)) {
            byte[] raw = new byte[list.size()];
            for (int index = 0; index < list.size(); index++) {
                raw[index] = (byte) (((Number) list.get(index)).intValue() & 0xFF);
            }
            return ensureByteCapacity(raw, quantity);
        }
        return null;
    }

    /**
     * 将数组按布尔数组或字节数组转换为 packed 字节。
     */
    private static byte[] arrayToPackedBytes(Object array, int quantity, Parity parity) {
        int length = Array.getLength(array);
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(Array.get(array, index));
        }
        return listToPackedBytes(values, quantity, parity);
    }

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
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
