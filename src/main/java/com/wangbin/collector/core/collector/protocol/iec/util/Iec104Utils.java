package com.wangbin.collector.core.collector.protocol.iec.util;

import com.wangbin.collector.core.collector.protocol.iec.domain.Iec104Address;
import com.wangbin.collector.core.collector.protocol.iec.domain.Iec104Type;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.ie.IeDoubleCommand;
import org.openmuc.j60870.ie.IeRegulatingStepCommand;

import java.util.Locale;

/**
 * IEC104 工具方法。
 */
public class Iec104Utils {

    /**
     * 创建当前组件实例。
     */
    private Iec104Utils() {
    }

    /**
     * 解析 IEC104 地址。
     */
    public static Iec104Address parseAddress(String commonAddress, String addressStr) {
        if (addressStr == null || addressStr.isEmpty()) {
            throw new IllegalArgumentException("IEC 104 address cannot be empty");
        }

        try {
            int ca = Integer.parseInt(commonAddress);
            String raw = addressStr.trim();
            Integer typeId = null;
            int ioa;

            if (raw.contains(":")) {
                String[] parts = raw.split(":");
                if (parts.length != 2) {
                    throw new IllegalArgumentException(
                            "IEC 104 address must be ioa, typeId:ioa or typeName:ioa");
                }
                typeId = resolveRawTypeIdToken(parts[0].trim());
                ioa = Integer.parseInt(parts[1].trim());
            } else {
                ioa = Integer.parseInt(raw);
            }
            return new Iec104Address(ca, ioa, typeId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid IEC 104 address: " + addressStr, e);
        }
    }

    /**
     * 解析 IEC104 地址，并要求显式类型标记。
     */
    public static Iec104Address parseTypedAddress(String commonAddress, String addressStr, String usage) {
        Iec104Address address = parseAddress(commonAddress, addressStr);
        if (address.getTypeId() == null) {
            throw new IllegalArgumentException(
                    "IEC 104 " + usage + " address must include typeId or typeName, address=" + addressStr);
        }
        return address;
    }

    /**
     * 将 IEC104 配置类型标记解析为匹配使用的规范类型 ID。
     */
    public static Integer resolveTypeIdToken(Object raw) {
        Integer rawTypeId = resolveRawTypeIdToken(raw);
        return rawTypeId != null ? Iec104Type.canonicalTypeId(rawTypeId) : null;
    }

    /**
     * Resolve a configured IEC 104 type token into the 协议 raw type id.
     */
    public static Integer resolveRawTypeIdToken(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number) && raw.toString().isBlank()) {
            return null;
        }
        return Iec104Type.requireToken(raw).typeId();
    }

    /**
     * Resolve a configured IEC 104 type token into enum 元数据.
     */
    public static Iec104Type resolveType(Object raw) {
        if (raw == null || (!(raw instanceof Number) && raw.toString().isBlank())) {
            return null;
        }
        return Iec104Type.requireToken(raw);
    }

    /**
     * 将 ASDU 类型解析为匹配逻辑使用的规范类型 ID。
     */
    public static Integer resolveTypeId(ASduType type) {
        if (type == null) {
            return null;
        }
        return Iec104Type.canonicalTypeId(type.getId());
    }

    /**
     * 解析双点命令状态。
     */
    public static IeDoubleCommand.DoubleCommandState parseDoubleCommandState(Object value) {
        if (value instanceof IeDoubleCommand.DoubleCommandState state) {
            return state;
        }

        String strValue = normalizeToken(value);
        return switch (strValue) {
            case "OFF", "FALSE", "OPEN", "TRIP" -> IeDoubleCommand.DoubleCommandState.OFF;
            case "ON", "TRUE", "CLOSE", "START" -> IeDoubleCommand.DoubleCommandState.ON;
            case "NOT_PERMITTED_A" -> IeDoubleCommand.DoubleCommandState.NOT_PERMITTED_A;
            case "NOT_PERMITTED_B" -> IeDoubleCommand.DoubleCommandState.NOT_PERMITTED_B;
            default -> {
                IeDoubleCommand.DoubleCommandState state =
                        IeDoubleCommand.DoubleCommandState.getInstance(parseIntegerValue(value));
                if (state == null) {
                    throw new IllegalArgumentException("Invalid IEC 104 double command state: " + value);
                }
                yield state;
            }
        };
    }

    /**
     * 解析调节步命令状态。
     */
    public static IeRegulatingStepCommand.StepCommandState parseStepCommandState(Object value) {
        if (value instanceof IeRegulatingStepCommand.StepCommandState state) {
            return state;
        }

        String token = normalizeToken(value);
        return switch (token) {
            case "NOT_PERMITTED_A" -> IeRegulatingStepCommand.StepCommandState.NOT_PERMITTED_A;
            case "NEXT_STEP_LOWER", "LOWER", "DOWN", "DECREASE" ->
                    IeRegulatingStepCommand.StepCommandState.NEXT_STEP_LOWER;
            case "NEXT_STEP_HIGHER", "HIGHER", "UP", "INCREASE", "RAISE" ->
                    IeRegulatingStepCommand.StepCommandState.NEXT_STEP_HIGHER;
            case "NOT_PERMITTED_B" -> IeRegulatingStepCommand.StepCommandState.NOT_PERMITTED_B;
            default -> {
                IeRegulatingStepCommand.StepCommandState state =
                        IeRegulatingStepCommand.StepCommandState.getInstance(parseIntegerValue(value));
                if (state == null) {
                    throw new IllegalArgumentException("Invalid IEC 104 step command state: " + value);
                }
                yield state;
            }
        };
    }

    /**
     * Parse boolean-style command 值.
     */
    public static boolean parseBooleanValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("IEC 104 boolean command value cannot be null");
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0d;
        }

        String token = normalizeToken(value);
        return switch (token) {
            case "TRUE", "1", "ON", "YES", "Y", "ENABLE", "ENABLED" -> true;
            case "FALSE", "0", "OFF", "NO", "N", "DISABLE", "DISABLED" -> false;
            default -> {
                try {
                    yield Double.parseDouble(value.toString().trim()) != 0d;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid IEC 104 boolean command value: " + value, e);
                }
            }
        };
    }

    /**
     * Parse integer-style command 值, supporting decimal, hex and binary literals.
     */
    public static int parseIntegerValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("IEC 104 integer command value cannot be null");
        }
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException("Invalid IEC 104 integer command value: " + value);
            }
            int intValue = number.intValue();
            if (doubleValue != intValue) {
                throw new IllegalArgumentException("IEC 104 integer command value must be integral: " + value);
            }
            return intValue;
        }

        String trimmed = value.toString().trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("IEC 104 integer command value cannot be empty");
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        try {
            if (normalized.startsWith("0x")) {
                return Integer.parseUnsignedInt(normalized.substring(2), 16);
            }
            if (normalized.startsWith("0b")) {
                return Integer.parseUnsignedInt(normalized.substring(2), 2);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignore) {
            try {
                double doubleValue = Double.parseDouble(trimmed);
                int intValue = (int) doubleValue;
                if (doubleValue != intValue) {
                    throw new IllegalArgumentException("IEC 104 integer command value must be integral: " + value);
                }
                return intValue;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid IEC 104 integer command value: " + value, e);
            }
        }
    }

    /**
     * Parse float-style command 值.
     */
    public static float parseFloatValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("IEC 104 float command value cannot be null");
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }

        String trimmed = value.toString().trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("IEC 104 float command value cannot be empty");
        }
        return Float.parseFloat(trimmed);
    }

    /**
     * Whether the type id is a readable 遥测 type.
     */
    public static boolean isReadType(int typeId) {
        return Iec104Type.fromTypeId(typeId)
                .map(Iec104Type::readSupported)
                .orElse(false);
    }

    /**
     * 判断类型 ID 是否为可写命令类型。
     */
    public static boolean isWriteType(int typeId) {
        return Iec104Type.fromTypeId(typeId)
                .map(Iec104Type::writeSupported)
                .orElse(false);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalizeTypeToken(String raw) {
        return raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalizeToken(Object raw) {
        if (raw == null) {
            return "";
        }
        return normalizeTypeToken(raw.toString());
    }

}
