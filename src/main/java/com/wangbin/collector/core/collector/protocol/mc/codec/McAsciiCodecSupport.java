package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 定义当前模块的业务组件。
 */
public final class McAsciiCodecSupport {

    /**
     * 创建当前组件实例。
     */
    private McAsciiCodecSupport() {
    }

    /**
     * 执行当前业务逻辑。
     */
    public static String formatHex(int value, int width) {
        return String.format(Locale.ROOT, "%0" + width + "X", value & mask(width));
    }

    /**
     * 执行当前业务逻辑。
     */
    public static String formatDeviceNumber(McAddress address) {
        if (address == null) {
            return "000000";
        }
        String raw = Integer.toString(address.getDeviceNumber(), address.getDeviceCode().getRadix()).toUpperCase(Locale.ROOT);
        return "0".repeat(Math.max(0, 6 - raw.length())) + raw;
    }

    /**
     * 执行当前业务逻辑。
     */
    public static String deviceCodeText(McDeviceCode deviceCode) {
        return switch (deviceCode) {
            case ZR -> "ZR";
            case M -> "M*";
            case X -> "X*";
            case Y -> "Y*";
            case B -> "B*";
            case D -> "D*";
            case R -> "R*";
            case W -> "W*";
        };
    }

    /**
     * 解析或转换业务数据。
     */
    public static McDeviceCode parseDeviceCodeText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("MC ASCII device code cannot be empty");
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ZR" -> McDeviceCode.ZR;
            case "M*" -> McDeviceCode.M;
            case "X*" -> McDeviceCode.X;
            case "Y*" -> McDeviceCode.Y;
            case "B*" -> McDeviceCode.B;
            case "D*" -> McDeviceCode.D;
            case "R*" -> McDeviceCode.R;
            case "W*" -> McDeviceCode.W;
            default -> throw new IllegalArgumentException("Unsupported MC ASCII device code: " + text);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    public static int parseHex(byte[] payload, int offset, int width) {
        return Integer.parseInt(new String(payload, offset, width, StandardCharsets.US_ASCII), 16);
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeWritePayload(McAddress address, byte[] normalizedPayload) {
        byte[] safePayload = normalizedPayload != null ? normalizedPayload : new byte[0];
        if (address == null) {
            return safePayload.clone();
        }
        if (address.isBitDevice()) {
            return encodeBitPayload(address, safePayload);
        }
        return encodeWordPayload(address, safePayload);
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] decodeReadPayload(McAddress address, byte[] rawPayload) {
        byte[] safePayload = rawPayload != null ? rawPayload : new byte[0];
        if (address == null) {
            return safePayload.clone();
        }
        if (address.isBitDevice()) {
            return decodeBitPayload(address, safePayload);
        }
        return decodeWordPayload(address, safePayload);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static int rawReadPayloadLength(McAddress address) {
        if (address == null) {
            return 0;
        }
        if (address.isBitDevice()) {
            return address.getReadUnitCount();
        }
        return address.getWordCount() * 4;
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeWordPayload(McAddress address, byte[] payload) {
        int expected = address.getWordCount() * 2;
        if (payload.length < expected) {
            throw new IllegalArgumentException("MC ASCII write payload is shorter than expected: expected="
                    + expected + ", actual=" + payload.length);
        }
        byte[] encoded = new byte[address.getWordCount() * 4];
        int targetOffset = 0;
        for (int i = 0; i < address.getWordCount(); i++) {
            int sourceOffset = i * 2;
            int word = ((payload[sourceOffset + 1] & 0xFF) << 8) | (payload[sourceOffset] & 0xFF);
            byte[] wordAscii = formatHex(word, 4).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(wordAscii, 0, encoded, targetOffset, wordAscii.length);
            targetOffset += 4;
        }
        return encoded;
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] decodeWordPayload(McAddress address, byte[] payload) {
        int expected = address.getWordCount() * 4;
        if (payload.length < expected) {
            throw new IllegalArgumentException("MC ASCII read payload is shorter than expected: expected="
                    + expected + ", actual=" + payload.length);
        }
        byte[] decoded = new byte[address.getWordCount() * 2];
        int targetOffset = 0;
        for (int i = 0; i < address.getWordCount(); i++) {
            int word = parseHex(payload, i * 4, 4);
            decoded[targetOffset] = (byte) (word & 0xFF);
            decoded[targetOffset + 1] = (byte) ((word >> 8) & 0xFF);
            targetOffset += 2;
        }
        return decoded;
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeBitPayload(McAddress address, byte[] payload) {
        int expectedBits = address.getReadUnitCount();
        byte[] encoded = new byte[expectedBits];
        for (int i = 0; i < expectedBits; i++) {
            int packed = payload[i / 2] & 0xFF;
            int bit = (i & 1) == 0 ? (packed & 0x0F) : ((packed >> 4) & 0x0F);
            encoded[i] = (byte) (bit != 0 ? '1' : '0');
        }
        return encoded;
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] decodeBitPayload(McAddress address, byte[] payload) {
        int expectedBits = address.getReadUnitCount();
        if (payload.length < expectedBits) {
            throw new IllegalArgumentException("MC ASCII bit payload is shorter than expected: expected="
                    + expectedBits + ", actual=" + payload.length);
        }
        byte[] decoded = new byte[(expectedBits + 1) / 2];
        for (int i = 0; i < expectedBits; i++) {
            int bit = payload[i] == '1' ? 0x01 : 0x00;
            int targetIndex = i / 2;
            if ((i & 1) == 0) {
                decoded[targetIndex] = (byte) ((decoded[targetIndex] & 0xF0) | bit);
            } else {
                decoded[targetIndex] = (byte) ((decoded[targetIndex] & 0x0F) | (bit << 4));
            }
        }
        return decoded;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int mask(int width) {
        return switch (width) {
            case 2 -> 0xFF;
            case 4 -> 0xFFFF;
            case 6 -> 0xFFFFFF;
            default -> -1;
        };
    }
}
