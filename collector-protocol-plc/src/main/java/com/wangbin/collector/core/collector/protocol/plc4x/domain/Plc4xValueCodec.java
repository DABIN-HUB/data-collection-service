package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import org.apache.plc4x.java.api.value.PlcValue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 定义当前模块的枚举值。
 */
public enum Plc4xValueCodec implements CollectorValueCodec {
    BOOL {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isBoolean() ? plcValue.getBoolean() : toBoolean(plcValue.getObject());
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return toBoolean(value);
        }
    },
    BYTE_SIGNED {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isByte() ? plcValue.getByte() : coerceNumber(plcValue.getObject()).byteValue();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return coerceNumber(value).byteValue();
        }
    },
    INT32 {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isInteger() ? plcValue.getInteger() : coerceNumber(plcValue.getObject()).intValue();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return coerceNumber(value).intValue();
        }
    },
    INT64 {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isLong() ? plcValue.getLong() : coerceNumber(plcValue.getObject()).longValue();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return coerceNumber(value).longValue();
        }
    },
    UINT64_BIGINT {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isBigInteger()
                    ? plcValue.getBigInteger()
                    : BigInteger.valueOf(coerceNumber(plcValue.getObject()).longValue());
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return value instanceof BigInteger bigInteger
                    ? bigInteger
                    : BigInteger.valueOf(coerceNumber(value).longValue());
        }
    },
    FLOAT32 {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isFloat() ? plcValue.getFloat() : coerceNumber(plcValue.getObject()).floatValue();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return coerceNumber(value).floatValue();
        }
    },
    FLOAT64 {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isDouble() ? plcValue.getDouble() : coerceNumber(plcValue.getObject()).doubleValue();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return coerceNumber(value).doubleValue();
        }
    },
    STRING {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isString() ? plcValue.getString() : Objects.toString(plcValue.getObject(), null);
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return value.toString();
        }
    },
    DURATION {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isDuration() ? plcValue.getDuration() : plcValue.getObject();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return value;
        }
    },
    DATE {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isDate() ? plcValue.getDate() : plcValue.getObject();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return value;
        }
    },
    DATETIME {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isDateTime() ? plcValue.getDateTime() : plcValue.getObject();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return value;
        }
    },
    RAW_BYTES {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.getRaw();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            if (value instanceof byte[] bytes) {
                return bytes;
            }
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }
    },
    PASSTHROUGH {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.getObject();
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public Object write(Object value) {
            return value;
        }
    };

    /**
     * 查询并返回业务数据。
     */
    public abstract Object read(PlcValue plcValue);

    /**
     * 写入或持久化业务数据。
     */
    public abstract Object write(Object value);

    /**
     * 执行当前业务逻辑。
     */
    private static Number coerceNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof PlcValue plcValue) {
            return coerceNumber(plcValue.getObject());
        }
        if (value instanceof String text) {
            return text.contains(".") ? Double.parseDouble(text) : Long.parseLong(text);
        }
        throw new IllegalArgumentException("Cannot convert PLC4X value to number: " + value);
    }

    /**
     * 解析或转换业务数据。
     */
    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
