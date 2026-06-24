package com.wangbin.collector.core.collector.protocol.s7.domain;

import com.wangbin.collector.core.collector.protocol.plc4x.domain.CollectorValueCodec;
import org.apache.plc4x.java.api.value.PlcValue;

import java.math.BigInteger;
import java.util.Objects;

public enum S7ValueCodec implements CollectorValueCodec {
    BOOL {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isBoolean() ? plcValue.getBoolean() : toBoolean(plcValue.getObject());
        }

        @Override
        public Object write(Object value) {
            return toBoolean(value);
        }
    },
    INT8_SIGNED {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isByte() ? plcValue.getByte() : coerceNumber(plcValue.getObject()).byteValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).byteValue();
        }
    },
    INT8_UNSIGNED {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isInteger() ? plcValue.getInteger() : coerceNumber(plcValue.getObject()).intValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).intValue();
        }
    },
    INT16_SIGNED {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isInteger() ? plcValue.getInteger() : coerceNumber(plcValue.getObject()).intValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).intValue();
        }
    },
    INT16_UNSIGNED {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isInteger() ? plcValue.getInteger() : coerceNumber(plcValue.getObject()).intValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).intValue();
        }
    },
    INT32_SIGNED {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isInteger() ? plcValue.getInteger() : coerceNumber(plcValue.getObject()).intValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).intValue();
        }
    },
    INT32_UNSIGNED {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isLong() ? plcValue.getLong() : coerceNumber(plcValue.getObject()).longValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).longValue();
        }
    },
    INT64_SIGNED {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isLong() ? plcValue.getLong() : coerceNumber(plcValue.getObject()).longValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).longValue();
        }
    },
    INT64_UNSIGNED {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isBigInteger()
                    ? plcValue.getBigInteger()
                    : BigInteger.valueOf(coerceNumber(plcValue.getObject()).longValue());
        }

        @Override
        public Object write(Object value) {
            return value instanceof BigInteger bigInteger
                    ? bigInteger
                    : BigInteger.valueOf(coerceNumber(value).longValue());
        }
    },
    FLOAT32 {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isFloat() ? plcValue.getFloat() : coerceNumber(plcValue.getObject()).floatValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).floatValue();
        }
    },
    FLOAT64 {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isDouble() ? plcValue.getDouble() : coerceNumber(plcValue.getObject()).doubleValue();
        }

        @Override
        public Object write(Object value) {
            return coerceNumber(value).doubleValue();
        }
    },
    TEXT {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.isString() ? plcValue.getString() : Objects.toString(plcValue.getObject(), null);
        }

        @Override
        public Object write(Object value) {
            return value.toString();
        }
    },
    PASSTHROUGH {
        @Override
        public Object read(PlcValue plcValue) {
            return plcValue.getObject();
        }

        @Override
        public Object write(Object value) {
            return value;
        }
    };

    public abstract Object read(PlcValue plcValue);

    public abstract Object write(Object value);

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
