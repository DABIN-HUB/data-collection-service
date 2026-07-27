package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import org.apache.plc4x.java.api.value.PlcValue;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * PLC4X 一维数组值转换工具，统一处理数组长度校验和元素类型转换。
 */
public final class Plc4xArrayValueSupport {

    private Plc4xArrayValueSupport() {
    }

    public static Object decode(PlcValue plcValue,
                                int expectedSize,
                                Function<PlcValue, Object> elementDecoder,
                                String protocolName,
                                String address) {
        if (plcValue == null || plcValue.isNull()) {
            return null;
        }
        if (expectedSize <= 1) {
            if (!plcValue.isList()) {
                return elementDecoder.apply(plcValue);
            }
            if (plcValue.getLength() != 1) {
                throw new IllegalStateException(protocolName + " 标量点位返回了数组数据，地址=" + address
                        + "，实际长度=" + plcValue.getLength());
            }
            return elementDecoder.apply(plcValue.getIndex(0));
        }
        if (!plcValue.isList()) {
            throw new IllegalStateException(protocolName + " 数组点位未返回数组数据，地址=" + address);
        }
        if (plcValue.getLength() != expectedSize) {
            throw new IllegalStateException(protocolName + " 数组读取长度不匹配，地址=" + address
                    + "，期望长度=" + expectedSize + "，实际长度=" + plcValue.getLength());
        }

        List<Object> values = new ArrayList<>(expectedSize);
        for (int index = 0; index < expectedSize; index++) {
            values.add(elementDecoder.apply(plcValue.getIndex(index)));
        }
        return values;
    }

    public static Object encode(Object value,
                                int expectedSize,
                                Function<Object, Object> elementEncoder,
                                String protocolName) {
        if (expectedSize <= 1) {
            return value == null ? null : elementEncoder.apply(value);
        }
        List<Object> sourceValues = toObjectList(value, protocolName);
        if (sourceValues.size() != expectedSize) {
            throw new IllegalArgumentException(protocolName + " 数组写入长度不匹配，期望长度="
                    + expectedSize + "，实际长度=" + sourceValues.size());
        }

        List<Object> encodedValues = new ArrayList<>(expectedSize);
        for (Object sourceValue : sourceValues) {
            encodedValues.add(sourceValue == null ? null : elementEncoder.apply(sourceValue));
        }
        return encodedValues;
    }

    private static List<Object> toObjectList(Object value, String protocolName) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(Array.get(value, index));
            }
            return values;
        }
        throw new IllegalArgumentException(protocolName + " 数组写入值必须是集合或数组");
    }
}
