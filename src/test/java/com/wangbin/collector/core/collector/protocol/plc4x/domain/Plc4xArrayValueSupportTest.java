package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import org.apache.plc4x.java.api.value.PlcValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Plc4xArrayValueSupportTest {

    @Test
    void shouldDecodeArrayAndCheckLength() {
        PlcValue arrayValue = mock(PlcValue.class);
        PlcValue first = mock(PlcValue.class);
        PlcValue second = mock(PlcValue.class);
        when(arrayValue.isNull()).thenReturn(false);
        when(arrayValue.isList()).thenReturn(true);
        when(arrayValue.getLength()).thenReturn(2);
        when(arrayValue.getIndex(0)).thenReturn(first);
        when(arrayValue.getIndex(1)).thenReturn(second);
        when(first.getInteger()).thenReturn(10);
        when(second.getInteger()).thenReturn(20);

        Object result = Plc4xArrayValueSupport.decode(
                arrayValue, 2, PlcValue::getInteger, "测试协议", "A1");

        assertEquals(List.of(10, 20), result);
        assertThrows(IllegalStateException.class, () -> Plc4xArrayValueSupport.decode(
                arrayValue, 3, PlcValue::getInteger, "测试协议", "A1"));
    }

    @Test
    void shouldEncodeCollectionAndPrimitiveArray() {
        Object listResult = Plc4xArrayValueSupport.encode(
                List.of("1", "2"), 2, value -> Integer.parseInt(value.toString()), "测试协议");
        Object arrayResult = Plc4xArrayValueSupport.encode(
                new int[]{3, 4}, 2, value -> Integer.parseInt(value.toString()), "测试协议");

        assertEquals(List.of(1, 2), listResult);
        assertEquals(List.of(3, 4), arrayResult);
        assertThrows(IllegalArgumentException.class, () -> Plc4xArrayValueSupport.encode(
                List.of(1), 2, value -> value, "测试协议"));
    }
}
