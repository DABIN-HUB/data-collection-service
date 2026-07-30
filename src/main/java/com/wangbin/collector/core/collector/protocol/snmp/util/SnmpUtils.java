package com.wangbin.collector.core.collector.protocol.snmp.util;

import com.wangbin.collector.core.collector.protocol.snmp.domain.SnmpAddress;
import com.wangbin.collector.core.collector.protocol.snmp.domain.SnmpDataType;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * SNMP 工具方法。
 */
public final class SnmpUtils {

    private SnmpUtils() {
    }

    public static List<List<SnmpAddress>> partition(List<SnmpAddress> source, int size) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<SnmpAddress>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            result.add(source.subList(i, Math.min(source.size(), i + size)));
        }
        return result;
    }

    public static List<List<VariableBinding>> partitionBindings(List<VariableBinding> source, int size) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<VariableBinding>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            result.add(source.subList(i, Math.min(source.size(), i + size)));
        }
        return result;
    }

    public static Object variableToJava(Variable variable, SnmpDataType dataType) {
        if (variable == null || variable instanceof Null) {
            return null;
        }
        SnmpDataType type = dataType != null ? dataType : SnmpDataType.AUTO;
        if (type == SnmpDataType.AUTO) {
            return autoConvert(variable);
        }
        return switch (type) {
            case INTEGER -> ((Integer32) variable).getValue();
            case COUNTER32 -> ((Counter32) variable).getValue();
            case COUNTER64 -> ((Counter64) variable).getValue();
            case GAUGE32 -> ((Gauge32) variable).getValue();
            case TIMETICKS -> ((TimeTicks) variable).getValue();
            case OCTET_STRING, STRING -> variable.toString();
            case IP_ADDRESS -> variable.toString();
            case OID -> variable.toString();
            case NULL -> null;
            case AUTO -> autoConvert(variable);
        };
    }

    private static Object autoConvert(Variable variable) {
        if (variable instanceof Integer32 int32) {
            return int32.getValue();
        }
        if (variable instanceof Counter32 counter32) {
            return counter32.getValue();
        }
        if (variable instanceof Counter64 counter64) {
            return counter64.getValue();
        }
        if (variable instanceof Gauge32 gauge32) {
            return gauge32.getValue();
        }
        if (variable instanceof TimeTicks timeTicks) {
            return timeTicks.getValue();
        }
        if (variable instanceof OctetString octetString) {
            return octetString.toString();
        }
        if (variable instanceof IpAddress ipAddress) {
            return ipAddress.toString();
        }
        if (variable instanceof OID oid) {
            return oid.toDottedString();
        }
        return variable.toString();
    }

    public static Variable toVariable(Object value, SnmpDataType dataType) {
        SnmpDataType type = dataType != null ? dataType : SnmpDataType.AUTO;
        if (value == null) {
            return new Null();
        }
        return switch (type) {
            case INTEGER -> new Integer32(((Number) value).intValue());
            case COUNTER32 -> new Counter32(((Number) value).intValue());
            case COUNTER64 -> new Counter64(((Number) value).longValue());
            case GAUGE32 -> new Gauge32(((Number) value).intValue());
            case TIMETICKS -> new TimeTicks(((Number) value).longValue());
            case OCTET_STRING, STRING -> new OctetString(value.toString());
            case IP_ADDRESS -> new IpAddress(value.toString());
            case OID -> new OID(value.toString());
            case NULL -> new Null();
            case AUTO -> inferVariable(value);
        };
    }

    private static Variable inferVariable(Object value) {
        if (value instanceof Number number) {
            long longValue = number.longValue();
            if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                return new Integer32((int) longValue);
            }
            return new Counter64(longValue);
        }
        return new OctetString(value.toString());
    }

    public static int parseSecurityLevel(String text) {
        if (text == null) {
            return SecurityLevel.AUTH_PRIV;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "noauthnopriv", "noauth" -> SecurityLevel.NOAUTH_NOPRIV;
            case "authnopriv" -> SecurityLevel.AUTH_NOPRIV;
            case "authpriv" -> SecurityLevel.AUTH_PRIV;
            default -> SecurityLevel.AUTH_PRIV;
        };
    }

    public static OID resolveAuthProtocol(String text) {
        if (text == null || text.isBlank()) {
            return AuthSHA.ID;
        }
        String normalized = text.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MD5" -> AuthMD5.ID;
            case "SHA", "SHA1", "SHA256", "SHA512", "SHA224", "SHA384" -> AuthSHA.ID;
            case "NONE" -> null;
            default -> AuthSHA.ID;
        };
    }

    public static OID resolvePrivProtocol(String text) {
        if (text == null || text.isBlank()) {
            return PrivAES128.ID;
        }
        String normalized = text.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DES" -> PrivDES.ID;
            case "3DES", "TRIPLEDES" -> Priv3DES.ID;
            case "AES", "AES128" -> PrivAES128.ID;
            case "AES192" -> PrivAES192.ID;
            case "AES256" -> PrivAES256.ID;
            case "NONE" -> null;
            default -> PrivAES128.ID;
        };
    }

    public static OctetString parseContextEngineId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        String normalized = trimmed.replace(":", "")
                .replace("-", "")
                .replace(" ", "");
        if (!normalized.isEmpty()
                && normalized.length() % 2 == 0
                && normalized.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
            byte[] bytes = new byte[normalized.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                int hi = Character.digit(normalized.charAt(i * 2), 16);
                int lo = Character.digit(normalized.charAt(i * 2 + 1), 16);
                bytes[i] = (byte) ((hi << 4) + lo);
            }
            return new OctetString(bytes);
        }
        return new OctetString(trimmed);
    }
}
