package com.wangbin.collector.core.collector.converter;

import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 采集器通用值转换器。
 *
 * <p>仅处理无状态的读写值转换、缩放反向转换和通用范围校验，不持有设备生命周期或运行统计状态。</p>
 */
@Slf4j
public final class CollectorValueConverter {

    private final Map<String, DataConverter> dataConverters;

    public CollectorValueConverter() {
        this.dataConverters = initDataConverters();
    }

    public Object convertData(DataPoint point, Object rawValue) {
        if (rawValue == null) {
            return null;
        }

        Double scaledValue = point.getActualValue(convertToDouble(rawValue));

        DataConverter converter = dataConverters.get(point.getDataType());
        if (converter != null) {
            return converter.convert(scaledValue, point);
        }

        return scaledValue;
    }

    public Object convertDataForWrite(DataPoint point, Object value) {
        if (value == null) {
            return null;
        }

        Double rawValue = convertToDouble(value);
        if (point.getScalingFactor() != null && point.getScalingFactor() != 0) {
            rawValue = rawValue / point.getScalingFactor();
        }
        if (point.getOffset() != null) {
            rawValue = rawValue - point.getOffset();
        }

        return rawValue;
    }

    public void validateData(DataPoint point, Object value) {
        if (value == null) {
            return;
        }

        if (value instanceof Number) {
            double doubleValue = ((Number) value).doubleValue();

            if (!point.isValueValid(doubleValue)) {
                log.warn(String.format("点位值超出范围: %s, 值: %f, 范围: [%f, %f]", point.getPointName(),
                        doubleValue, point.getMinValue(), point.getMaxValue()));
            }
        }
    }

    Double convertToDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无法转换为数字: " + value);
            }
        } else if (value instanceof Boolean) {
            return ((Boolean) value) ? 1.0 : 0.0;
        } else {
            throw new IllegalArgumentException("不支持的数据类型: " + value.getClass().getName());
        }
    }

    private Map<String, DataConverter> initDataConverters() {
        Map<String, DataConverter> converters = new HashMap<>();
        converters.put("default", new DefaultDataConverter());
        converters.put("scale", new ScaleDataConverter());
        converters.put("boolean", new BooleanDataConverter());
        return Collections.unmodifiableMap(converters);
    }

    private interface DataConverter {
        Object convert(Object value, DataPoint point);
    }

    private static class DefaultDataConverter implements DataConverter {

        @Override
        public Object convert(Object value, DataPoint point) {
            return value;
        }
    }

    private static class ScaleDataConverter implements DataConverter {

        @Override
        public Object convert(Object value, DataPoint point) {
            if (value instanceof Number) {
                double scaledValue = ((Number) value).doubleValue();

                if (point.getPrecision() != null) {
                    double factor = Math.pow(10, point.getPrecision());
                    scaledValue = Math.round(scaledValue * factor) / factor;
                }

                return scaledValue;
            }
            return value;
        }
    }

    private static class BooleanDataConverter implements DataConverter {

        @Override
        public Object convert(Object value, DataPoint point) {
            if (value instanceof Number) {
                double numValue = ((Number) value).doubleValue();
                return numValue != 0;
            } else if (value instanceof String) {
                String strValue = ((String) value).toLowerCase();
                return "true".equals(strValue) || "1".equals(strValue) || "on".equals(strValue);
            }
            return value;
        }
    }
}
