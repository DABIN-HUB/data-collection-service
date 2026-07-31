package com.wangbin.collector.core.collector.protocol.iec101.codec;

import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101Asdu;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101InformationObject;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101LinkConfig;

import java.io.ByteArrayOutputStream;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * IEC101 ASDU 编解码器。
 */
public final class Iec101AsduCodec {

    public static final int COT_ACTIVATION = 6;
    public static final int COT_ACTIVATION_CONFIRMATION = 7;
    public static final int COT_ACTIVATION_TERMINATION = 10;

    /**
     * 创建当前组件实例。
     */
    private Iec101AsduCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static Iec101Asdu decode(byte[] bytes, Iec101LinkConfig config) {
        if (bytes == null || bytes.length < 2 + config.causeOfTransmissionSize()
                + config.commonAddressSize()) {
            throw new IllegalArgumentException("IEC101 ASDU 长度不足");
        }
        Cursor cursor = new Cursor(bytes);
        int typeId = cursor.readUnsignedByte();
        int variableStructureQualifier = cursor.readUnsignedByte();
        int objectCount = variableStructureQualifier & 0x7F;
        boolean sequence = (variableStructureQualifier & 0x80) != 0;
        int rawCause = cursor.readUnsignedByte();
        boolean negative = (rawCause & 0x40) != 0;
        boolean test = (rawCause & 0x80) != 0;
        int cause = rawCause & 0x3F;
        int originator = config.causeOfTransmissionSize() == 2 ? cursor.readUnsignedByte() : 0;
        int commonAddress = cursor.readLittleEndian(config.commonAddressSize());
        List<Iec101InformationObject> objects = new ArrayList<>(objectCount);
        int sequenceAddress = 0;
        for (int index = 0; index < objectCount; index++) {
            int ioa;
            if (!sequence || index == 0) {
                ioa = cursor.readLittleEndian(config.informationObjectAddressSize());
                sequenceAddress = ioa;
            } else {
                ioa = ++sequenceAddress;
            }
            objects.add(decodeInformationObject(typeId, ioa, cursor));
        }
        if (cursor.remaining() != 0) {
            throw new IllegalArgumentException("IEC101 ASDU 存在未解析尾部数据");
        }
        return new Iec101Asdu(typeId, cause, negative, test, originator,
                commonAddress, sequence, objects);
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeInterrogation(int commonAddress,
                                             int qualifier,
                                             Iec101LinkConfig config) {
        return encodeSingleObject(100, COT_ACTIVATION, commonAddress, 0,
                new byte[]{(byte) qualifier}, config);
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeCounterInterrogation(int commonAddress,
                                                    int qualifier,
                                                    Iec101LinkConfig config) {
        return encodeSingleObject(101, COT_ACTIVATION, commonAddress, 0,
                new byte[]{(byte) qualifier}, config);
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeRead(int commonAddress,
                                    int informationObjectAddress,
                                    Iec101LinkConfig config) {
        return encodeSingleObject(102, 5, commonAddress, informationObjectAddress,
                new byte[0], config);
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeClockSynchronization(int commonAddress,
                                                    long timestamp,
                                                    Iec101LinkConfig config) {
        return encodeSingleObject(103, COT_ACTIVATION, commonAddress, 0,
                encodeCp56Time(timestamp), config);
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeCommand(int typeId,
                                       int commonAddress,
                                       int informationObjectAddress,
                                       Object value,
                                       boolean select,
                                       int qualifier,
                                       Iec101LinkConfig config) {
        byte[] element = encodeCommandElement(typeId, value, select, qualifier);
        return encodeSingleObject(typeId, COT_ACTIVATION, commonAddress,
                informationObjectAddress, element, config);
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeSingleObject(int typeId,
                                             int cause,
                                             int commonAddress,
                                             int informationObjectAddress,
                                             byte[] element,
                                             Iec101LinkConfig config) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(typeId);
        output.write(1);
        output.write(cause & 0x3F);
        if (config.causeOfTransmissionSize() == 2) {
            output.write(0);
        }
        writeLittleEndian(output, commonAddress, config.commonAddressSize());
        writeLittleEndian(output, informationObjectAddress, config.informationObjectAddressSize());
        output.writeBytes(element);
        return output.toByteArray();
    }

    /**
     * 解析或转换业务数据。
     */
    private static Iec101InformationObject decodeInformationObject(int typeId,
                                                                   int address,
                                                                   Cursor cursor) {
        Object value;
        int rawQuality = 0;
        Long timestamp = null;
        switch (typeId) {
            case 1 -> {
                int siq = cursor.readUnsignedByte();
                value = (siq & 0x01) != 0;
                rawQuality = siq & 0xF0;
            }
            case 2 -> {
                int siq = cursor.readUnsignedByte();
                value = (siq & 0x01) != 0;
                rawQuality = siq & 0xF0;
                timestamp = decodeCp24Time(cursor.readBytes(3));
            }
            case 3 -> {
                int diq = cursor.readUnsignedByte();
                value = diq & 0x03;
                rawQuality = diq & 0xF0;
            }
            case 4 -> {
                int diq = cursor.readUnsignedByte();
                value = diq & 0x03;
                rawQuality = diq & 0xF0;
                timestamp = decodeCp24Time(cursor.readBytes(3));
            }
            case 5 -> {
                int vti = cursor.readUnsignedByte();
                value = decodeSignedSevenBit(vti);
                rawQuality = cursor.readUnsignedByte();
            }
            case 6 -> {
                int vti = cursor.readUnsignedByte();
                value = decodeSignedSevenBit(vti);
                rawQuality = cursor.readUnsignedByte();
                timestamp = decodeCp24Time(cursor.readBytes(3));
            }
            case 7, 20 -> {
                value = cursor.readLittleEndianLong(4);
                rawQuality = cursor.readUnsignedByte();
            }
            case 8 -> {
                value = cursor.readLittleEndianLong(4);
                rawQuality = cursor.readUnsignedByte();
                timestamp = decodeCp24Time(cursor.readBytes(3));
            }
            case 9, 10, 34 -> {
                value = cursor.readSignedLittleEndian(2) / 32768.0d;
                rawQuality = cursor.readUnsignedByte();
                timestamp = typeId == 10 ? decodeCp24Time(cursor.readBytes(3))
                        : typeId == 34 ? decodeCp56Time(cursor.readBytes(7)) : null;
            }
            case 11, 12, 35 -> {
                value = cursor.readSignedLittleEndian(2);
                rawQuality = cursor.readUnsignedByte();
                timestamp = typeId == 12 ? decodeCp24Time(cursor.readBytes(3))
                        : typeId == 35 ? decodeCp56Time(cursor.readBytes(7)) : null;
            }
            case 13, 14, 36 -> {
                value = Float.intBitsToFloat((int) cursor.readLittleEndianLong(4));
                rawQuality = cursor.readUnsignedByte();
                timestamp = typeId == 14 ? decodeCp24Time(cursor.readBytes(3))
                        : typeId == 36 ? decodeCp56Time(cursor.readBytes(7)) : null;
            }
            case 15, 16, 37 -> {
                value = cursor.readSignedLittleEndian(4);
                rawQuality = cursor.readUnsignedByte();
                timestamp = typeId == 16 ? decodeCp24Time(cursor.readBytes(3))
                        : typeId == 37 ? decodeCp56Time(cursor.readBytes(7)) : null;
            }
            case 21 -> value = cursor.readSignedLittleEndian(2) / 32768.0d;
            case 30 -> {
                int siq = cursor.readUnsignedByte();
                value = (siq & 0x01) != 0;
                rawQuality = siq & 0xF0;
                timestamp = decodeCp56Time(cursor.readBytes(7));
            }
            case 31 -> {
                int diq = cursor.readUnsignedByte();
                value = diq & 0x03;
                rawQuality = diq & 0xF0;
                timestamp = decodeCp56Time(cursor.readBytes(7));
            }
            case 32 -> {
                int vti = cursor.readUnsignedByte();
                value = decodeSignedSevenBit(vti);
                rawQuality = cursor.readUnsignedByte();
                timestamp = decodeCp56Time(cursor.readBytes(7));
            }
            case 33 -> {
                value = cursor.readLittleEndianLong(4);
                rawQuality = cursor.readUnsignedByte();
                timestamp = decodeCp56Time(cursor.readBytes(7));
            }
            case 45, 46, 47 -> value = cursor.readUnsignedByte();
            case 48, 49 -> {
                value = cursor.readSignedLittleEndian(2);
                rawQuality = cursor.readUnsignedByte();
            }
            case 50 -> {
                value = Float.intBitsToFloat((int) cursor.readLittleEndianLong(4));
                rawQuality = cursor.readUnsignedByte();
            }
            case 51 -> {
                value = cursor.readLittleEndianLong(4);
                rawQuality = cursor.readUnsignedByte();
            }
            case 58, 59, 60 -> {
                value = cursor.readUnsignedByte();
                timestamp = decodeCp56Time(cursor.readBytes(7));
            }
            case 61, 62 -> {
                value = cursor.readSignedLittleEndian(2);
                rawQuality = cursor.readUnsignedByte();
                timestamp = decodeCp56Time(cursor.readBytes(7));
            }
            case 63 -> {
                value = Float.intBitsToFloat((int) cursor.readLittleEndianLong(4));
                rawQuality = cursor.readUnsignedByte();
                timestamp = decodeCp56Time(cursor.readBytes(7));
            }
            case 64 -> {
                value = cursor.readLittleEndianLong(4);
                rawQuality = cursor.readUnsignedByte();
                timestamp = decodeCp56Time(cursor.readBytes(7));
            }
            case 100, 101 -> value = cursor.readUnsignedByte();
            case 102 -> value = null;
            case 103 -> {
                timestamp = decodeCp56Time(cursor.readBytes(7));
                value = timestamp;
            }
            default -> throw new IllegalArgumentException("暂不支持解析 IEC101 TypeId: " + typeId);
        }
        return new Iec101InformationObject(address, value, qualityScore(rawQuality), rawQuality, timestamp);
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeCommandElement(int typeId,
                                               Object value,
                                               boolean select,
                                               int qualifier) {
        int selectBit = select ? 0x80 : 0;
        int commandQualifier = selectBit | ((qualifier & 0x1F) << 2);
        int setPointQualifier = selectBit | (qualifier & 0x7F);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        switch (typeId) {
            case 45, 58 -> output.write((booleanValue(value) ? 1 : 0) | commandQualifier);
            case 46, 59, 47, 60 -> output.write((integerValue(value) & 0x03) | commandQualifier);
            case 48, 61 -> {
                int normalized = (int) Math.round(doubleValue(value) * 32767.0d);
                writeLittleEndian(output, normalized, 2);
                output.write(setPointQualifier);
            }
            case 49, 62 -> {
                writeLittleEndian(output, integerValue(value), 2);
                output.write(setPointQualifier);
            }
            case 50, 63 -> {
                writeLittleEndian(output, Float.floatToIntBits((float) doubleValue(value)), 4);
                output.write(setPointQualifier);
            }
            case 51, 64 -> {
                writeLittleEndian(output, integerValue(value), 4);
                output.write(setPointQualifier);
            }
            default -> throw new IllegalArgumentException("不支持的 IEC101 写命令 TypeId: " + typeId);
        }
        if (typeId >= 58 && typeId <= 64) {
            output.writeBytes(encodeCp56Time(System.currentTimeMillis()));
        }
        return output.toByteArray();
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int qualityScore(int rawQuality) {
        if ((rawQuality & 0x80) != 0) {
            return 0;
        }
        if ((rawQuality & 0x40) != 0 || (rawQuality & 0x20) != 0 || (rawQuality & 0x10) != 0) {
            return 50;
        }
        if ((rawQuality & 0x01) != 0) {
            return 80;
        }
        return 100;
    }

    /**
     * 解析或转换业务数据。
     */
    private static int decodeSignedSevenBit(int value) {
        int result = value & 0x7F;
        return (result & 0x40) != 0 ? result - 0x80 : result;
    }

    /**
     * 解析或转换业务数据。
     */
    private static Long decodeCp24Time(byte[] bytes) {
        int millisecondsOfMinute = readUnsignedLittleEndian(bytes, 0, 2);
        int minute = bytes[2] & 0x3F;
        LocalDateTime now = LocalDateTime.now();
        try {
            LocalDateTime time = LocalDateTime.of(now.toLocalDate(),
                    LocalTime.of(now.getHour(), minute,
                            millisecondsOfMinute / 1000, (millisecondsOfMinute % 1000) * 1_000_000));
            return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeException exception) {
            return null;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private static Long decodeCp56Time(byte[] bytes) {
        int millisecondsOfMinute = readUnsignedLittleEndian(bytes, 0, 2);
        int minute = bytes[2] & 0x3F;
        int hour = bytes[3] & 0x1F;
        int day = bytes[4] & 0x1F;
        int month = bytes[5] & 0x0F;
        int year = 2000 + (bytes[6] & 0x7F);
        try {
            LocalDateTime time = LocalDateTime.of(
                    LocalDate.of(year, month, day),
                    LocalTime.of(hour, minute,
                            millisecondsOfMinute / 1000, (millisecondsOfMinute % 1000) * 1_000_000));
            return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeException exception) {
            return null;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeCp56Time(long timestamp) {
        LocalDateTime time = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        int millisecondsOfMinute = time.getSecond() * 1000 + time.getNano() / 1_000_000;
        return new byte[]{
                (byte) (millisecondsOfMinute & 0xFF),
                (byte) ((millisecondsOfMinute >>> 8) & 0xFF),
                (byte) time.getMinute(),
                (byte) time.getHour(),
                (byte) time.getDayOfMonth(),
                (byte) time.getMonthValue(),
                (byte) (time.getYear() - 2000)
        };
    }

    /**
     * 查询并返回业务数据。
     */
    private static int readUnsignedLittleEndian(byte[] bytes, int offset, int length) {
        int value = 0;
        for (int index = 0; index < length; index++) {
            value |= (bytes[offset + index] & 0xFF) << (index * 8);
        }
        return value;
    }

    /**
     * 写入或持久化业务数据。
     */
    private static void writeLittleEndian(ByteArrayOutputStream output, int value, int length) {
        for (int index = 0; index < length; index++) {
            output.write((value >>> (index * 8)) & 0xFF);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return integerValue(value) != 0;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int integerValue(Object value) {
        return new java.math.BigDecimal(String.valueOf(value)).intValue();
    }

    /**
     * 执行当前业务逻辑。
     */
    private static double doubleValue(Object value) {
        return Double.parseDouble(String.valueOf(value));
    }

    /**
     * 定义当前模块的业务组件。
     */
    private static final class Cursor {

        private final byte[] bytes;
        private int position;

        /**
         * 创建当前组件实例。
         */
        private Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        /**
         * 查询并返回业务数据。
         */
        private int readUnsignedByte() {
            require(1);
            return bytes[position++] & 0xFF;
        }

        /**
         * 查询并返回业务数据。
         */
        private int readLittleEndian(int length) {
            require(length);
            int value = readUnsignedLittleEndian(bytes, position, length);
            position += length;
            return value;
        }

        /**
         * 查询并返回业务数据。
         */
        private long readLittleEndianLong(int length) {
            require(length);
            long value = 0;
            for (int index = 0; index < length; index++) {
                value |= (long) (bytes[position + index] & 0xFF) << (index * 8);
            }
            position += length;
            return value;
        }

        /**
         * 查询并返回业务数据。
         */
        private long readSignedLittleEndian(int length) {
            long value = readLittleEndianLong(length);
            int bits = length * 8;
            if (bits < Long.SIZE && (value & (1L << (bits - 1))) != 0) {
                value |= -1L << bits;
            }
            return value;
        }

        /**
         * 查询并返回业务数据。
         */
        private byte[] readBytes(int length) {
            require(length);
            byte[] result = java.util.Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return result;
        }

        /**
         * 执行当前业务逻辑。
         */
        private int remaining() {
            return bytes.length - position;
        }

        /**
         * 校验业务条件和参数边界。
         */
        private void require(int length) {
            if (position + length > bytes.length) {
                throw new IllegalArgumentException("IEC101 ASDU 信息对象长度不足");
            }
        }
    }
}
