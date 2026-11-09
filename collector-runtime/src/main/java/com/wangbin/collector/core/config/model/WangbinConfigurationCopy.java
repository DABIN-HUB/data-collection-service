package com.wangbin.collector.core.config.model;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置值的内存防御复制，不经 JSON 中转，保留精确数字、空值和数组。 */
final class WangbinConfigurationCopy {
    private WangbinConfigurationCopy() { }

    @SuppressWarnings("unchecked")
    static <K, V> Map<K, V> map(Map<K, V> source) {
        // 递归复制保持原键值类型，泛型转换不执行数值或字符串强制转换。
        return (Map<K, V>) value(source, new IdentityHashMap<>());
    }

    private static Object value(Object source, IdentityHashMap<Object, Object> copies) {
        if (source == null || source instanceof String || source instanceof Boolean || source instanceof Character
                || source instanceof Byte || source instanceof Short || source instanceof Integer || source instanceof Long
                || source instanceof Float || source instanceof Double || source instanceof BigDecimal
                || source instanceof BigInteger || source instanceof Enum<?> || source instanceof Charset) return source;
        if (copies.containsKey(source)) return copies.get(source);
        if (source instanceof Date date) return new Date(date.getTime());
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            copies.put(source, result);
            map.forEach((key, item) -> result.put(value(key, copies), value(item, copies)));
            return result;
        }
        if (source instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            copies.put(source, result);
            for (Object item : list) result.add(value(item, copies));
            return result;
        }
        if (source instanceof Set<?> set) {
            Set<Object> result = new LinkedHashSet<>();
            copies.put(source, result);
            for (Object item : set) result.add(value(item, copies));
            return result;
        }
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            Object result = Array.newInstance(source.getClass().getComponentType(), length);
            copies.put(source, result);
            for (int index = 0; index < length; index++) Array.set(result, index, value(Array.get(source, index), copies));
            return result;
        }
        throw new IllegalArgumentException("配置扩展包含不支持的可变值类型");
    }
}
