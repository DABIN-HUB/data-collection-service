package com.wangbin.collector.core.collector.protocol.s7.domain;

import com.wangbin.collector.core.collector.protocol.plc4x.domain.CollectorValueCodec;
import org.apache.plc4x.java.api.value.PlcValue;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 定义当前模块的枚举值。
 */
public enum S7ValueCodec implements CollectorValueCodec {
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
    INT8_SIGNED {
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
    INT8_UNSIGNED {
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
    INT16_SIGNED {
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
    INT16_UNSIGNED {
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
    INT32_SIGNED {
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
    INT32_UNSIGNED {
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
    INT64_SIGNED {
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
    INT64_UNSIGNED {
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
    TEXT {
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
        throw new IllegalArgumentException("Cannot convert S7 value to number: " + value);
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
