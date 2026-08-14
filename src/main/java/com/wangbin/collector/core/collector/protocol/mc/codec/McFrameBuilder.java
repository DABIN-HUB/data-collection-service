package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

/**
 * 定义当前模块的业务组件。
 */
public final class McFrameBuilder {

    private static final int MC_3E_BINARY_HEADER_LENGTH = 21;
    private static final int MC_4E_BINARY_HEADER_LENGTH = 25;
    private static final int BATCH_READ_COMMAND = 0x0401;
    private static final int BATCH_WRITE_COMMAND = 0x1401;
    private static final int RANDOM_READ_COMMAND = 0x0403;
    private static final int RANDOM_WRITE_COMMAND = 0x1402;
    private static final int WORD_UNIT_SUBCOMMAND = 0x0000;
    private static final int BIT_UNIT_SUBCOMMAND = 0x0001;

    /**
     * 创建当前组件实例。
     */
    private McFrameBuilder() {
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildBatchRead(McAddress address, DeviceConnection config) {
        byte[] frame = new byte[MC_3E_BINARY_HEADER_LENGTH];
        writeHeader(frame, 0, address, config, 12);
        writeUInt16(frame, 11, BATCH_READ_COMMAND);
        writeUInt16(frame, 13, subcommand(address));
        writeDeviceSpec(frame, 15, address);
        writeUInt16(frame, 19, address.getReadUnitCount());
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        byte[] frame = new byte[MC_3E_BINARY_HEADER_LENGTH + safePayload.length];
        writeHeader(frame, 0, address, config, 12 + safePayload.length);
        writeUInt16(frame, 11, BATCH_WRITE_COMMAND);
        writeUInt16(frame, 13, subcommand(address));
        writeDeviceSpec(frame, 15, address);
        writeUInt16(frame, 19, address.getReadUnitCount());
        System.arraycopy(safePayload, 0, frame, 21, safePayload.length);
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config) {
        int wordCount = request != null ? request.getWordAddressCount() : 0;
        byte[] frame = new byte[15 + (wordCount * 4)];
        writeHeader(frame, 0, null, config, 6 + (wordCount * 4));
        writeUInt16(frame, 11, RANDOM_READ_COMMAND);
        frame[13] = (byte) (wordCount & 0xFF);
        frame[14] = 0x00;
        int offset = 15;
        if (request != null) {
            for (McAddress address : request.getWordAddresses()) {
                writeDeviceSpec(frame, offset, address);
                offset += 4;
            }
        }
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config) {
        int wordCount = request != null ? request.getWordItemCount() : 0;
        int payloadLength = request != null
                ? request.getWordItems().stream().mapToInt(item -> 4 + item.getPayload().length).sum()
                : 0;
        byte[] frame = new byte[15 + payloadLength];
        writeHeader(frame, 0, null, config, 6 + payloadLength);
        writeUInt16(frame, 11, RANDOM_WRITE_COMMAND);
        frame[13] = (byte) (wordCount & 0xFF);
        frame[14] = 0x00;
        int offset = 15;
        if (request != null) {
            for (McRandomWriteItem item : request.getWordItems()) {
                writeDeviceSpec(frame, offset, item.getAddress());
                offset += 4;
                byte[] payload = item.getPayload();
                System.arraycopy(payload, 0, frame, offset, payload.length);
                offset += payload.length;
            }
        }
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildAsciiBatchRead(McAddress address, DeviceConnection config) {
        String body = buildAsciiRequestBody(BATCH_READ_COMMAND, subcommand(address), address, null)
                + McAsciiCodecSupport.formatHex(address.getReadUnitCount(), 4);
        return buildAsciiHeader(config, body).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildAsciiBatchWrite(McAddress address, byte[] payload, DeviceConnection config) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        String body = buildAsciiRequestBody(BATCH_WRITE_COMMAND, subcommand(address), address, null)
                + McAsciiCodecSupport.formatHex(address.getReadUnitCount(), 4)
                + new String(safePayload, java.nio.charset.StandardCharsets.US_ASCII);
        return buildAsciiHeader(config, body).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildAsciiRandomRead(McRandomReadRequest request, DeviceConnection config) {
        int wordCount = request != null ? request.getWordAddressCount() : 0;
        StringBuilder body = new StringBuilder()
                .append(McAsciiCodecSupport.formatHex(RANDOM_READ_COMMAND, 4))
                .append("0000")
                .append(McAsciiCodecSupport.formatHex(wordCount, 2))
                .append("00");
        if (request != null) {
            for (McAddress address : request.getWordAddresses()) {
                body.append(McAsciiCodecSupport.formatDeviceNumber(address))
                        .append(McAsciiCodecSupport.deviceCodeText(address.getDeviceCode()));
            }
        }
        return buildAsciiHeader(config, body.toString()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildAsciiRandomWrite(McRandomWriteRequest request, DeviceConnection config) {
        int wordCount = request != null ? request.getWordItemCount() : 0;
        StringBuilder body = new StringBuilder()
                .append(McAsciiCodecSupport.formatHex(RANDOM_WRITE_COMMAND, 4))
                .append("0000")
                .append(McAsciiCodecSupport.formatHex(wordCount, 2))
                .append("00");
        if (request != null) {
            for (McRandomWriteItem item : request.getWordItems()) {
                body.append(McAsciiCodecSupport.formatDeviceNumber(item.getAddress()))
                        .append(McAsciiCodecSupport.deviceCodeText(item.getAddress().getDeviceCode()))
                        .append(new String(item.getPayload(), java.nio.charset.StandardCharsets.US_ASCII));
            }
        }
        return buildAsciiHeader(config, body.toString()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] build4eBatchRead(McAddress address,
                                          DeviceConnection config,
                                          int serialNo) {
        byte[] frame = new byte[MC_4E_BINARY_HEADER_LENGTH];
        write4eHeader(frame, config, serialNo, 12);
        writeUInt16(frame, 15, BATCH_READ_COMMAND);
        writeUInt16(frame, 17, subcommand(address));
        writeDeviceSpec(frame, 19, address);
        writeUInt16(frame, 23, address.getReadUnitCount());
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] build4eBatchWrite(McAddress address,
                                           byte[] payload,
                                           DeviceConnection config,
                                           int serialNo) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        byte[] frame = new byte[MC_4E_BINARY_HEADER_LENGTH + safePayload.length];
        write4eHeader(frame, config, serialNo, 12 + safePayload.length);
        writeUInt16(frame, 15, BATCH_WRITE_COMMAND);
        writeUInt16(frame, 17, subcommand(address));
        writeDeviceSpec(frame, 19, address);
        writeUInt16(frame, 23, address.getReadUnitCount());
        System.arraycopy(safePayload, 0, frame, MC_4E_BINARY_HEADER_LENGTH, safePayload.length);
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] build4eRandomRead(McRandomReadRequest request,
                                           DeviceConnection config,
                                           int serialNo) {
        int wordCount = request != null ? request.getWordAddressCount() : 0;
        byte[] frame = new byte[19 + (wordCount * 4)];
        write4eHeader(frame, config, serialNo, 6 + (wordCount * 4));
        writeUInt16(frame, 15, RANDOM_READ_COMMAND);
        frame[17] = (byte) (wordCount & 0xFF);
        frame[18] = 0x00;
        int offset = 19;
        if (request != null) {
            for (McAddress address : request.getWordAddresses()) {
                writeDeviceSpec(frame, offset, address);
                offset += 4;
            }
        }
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] build4eRandomWrite(McRandomWriteRequest request,
                                            DeviceConnection config,
                                            int serialNo) {
        int payloadLength = request != null
                ? request.getWordItems().stream().mapToInt(item -> 4 + item.getPayload().length).sum()
                : 0;
        int wordCount = request != null ? request.getWordItemCount() : 0;
        byte[] frame = new byte[19 + payloadLength];
        write4eHeader(frame, config, serialNo, 6 + payloadLength);
        writeUInt16(frame, 15, RANDOM_WRITE_COMMAND);
        frame[17] = (byte) (wordCount & 0xFF);
        frame[18] = 0x00;
        int offset = 19;
        if (request != null) {
            for (McRandomWriteItem item : request.getWordItems()) {
                writeDeviceSpec(frame, offset, item.getAddress());
                offset += 4;
                byte[] payload = item.getPayload();
                System.arraycopy(payload, 0, frame, offset, payload.length);
                offset += payload.length;
            }
        }
        return frame;
    }

    /**
     * 写入或持久化业务数据。
     */
    private static void writeHeader(byte[] frame,
                                    int offset,
                                    McAddress address,
                                    DeviceConnection config,
                                    int requestLength) {
        frame[offset] = 0x50;
        frame[offset + 1] = 0x00;
        frame[offset + 2] = (byte) intValue(config, "networkNo", 0);
        frame[offset + 3] = (byte) intValue(config, "pcNo", 0xFF);
        writeUInt16(frame, offset + 4, intValue(config, "ioNo", 0x03FF));
        frame[offset + 6] = (byte) intValue(config, "stationNo", 0);
        writeUInt16(frame, offset + 7, requestLength);
        writeUInt16(frame, offset + 9, intValue(config, "monitoringTimer", 16));
    }

    /**
     * 写入或持久化业务数据。
     */
    private static void write4eHeader(byte[] frame,
                                      DeviceConnection config,
                                      int serialNo,
                                      int requestLength) {
        frame[0] = 0x54;
        frame[1] = 0x00;
        writeUInt16(frame, 2, serialNo);
        frame[4] = 0x00;
        frame[5] = 0x00;
        frame[6] = (byte) intValue(config, "networkNo", 0);
        frame[7] = (byte) intValue(config, "pcNo", 0xFF);
        writeUInt16(frame, 8, intValue(config, "ioNo", 0x03FF));
        frame[10] = (byte) intValue(config, "stationNo", 0);
        writeUInt16(frame, 11, requestLength);
        writeUInt16(frame, 13, intValue(config, "monitoringTimer", 16));
    }

    /**
     * 写入或持久化业务数据。
     */
    private static void writeDeviceSpec(byte[] frame, int offset, McAddress address) {
        int deviceNumber = address.getDeviceNumber();
        frame[offset] = (byte) (deviceNumber & 0xFF);
        frame[offset + 1] = (byte) ((deviceNumber >> 8) & 0xFF);
        frame[offset + 2] = (byte) ((deviceNumber >> 16) & 0xFF);
        frame[offset + 3] = (byte) address.getDeviceCode().getCode();
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int subcommand(McAddress address) {
        return address.isBitDevice() ? BIT_UNIT_SUBCOMMAND : WORD_UNIT_SUBCOMMAND;
    }

    /**
     * 创建并返回业务对象。
     */
    private static String buildAsciiHeader(DeviceConnection config, String body) {
        String safeBody = body != null ? body : "";
        StringBuilder header = new StringBuilder()
                .append("5000")
                .append(McAsciiCodecSupport.formatHex(intValue(config, "networkNo", 0), 2))
                .append(McAsciiCodecSupport.formatHex(intValue(config, "pcNo", 0xFF), 2))
                .append(McAsciiCodecSupport.formatHex(intValue(config, "ioNo", 0x03FF), 4))
                .append(McAsciiCodecSupport.formatHex(intValue(config, "stationNo", 0), 2))
                .append(McAsciiCodecSupport.formatHex(4 + safeBody.length(), 4))
                .append(McAsciiCodecSupport.formatHex(intValue(config, "monitoringTimer", 16), 4))
                .append(safeBody);
        return header.toString();
    }

    /**
     * 创建并返回业务对象。
     */
    private static String buildAsciiRequestBody(int command,
                                                int subcommand,
                                                McAddress address,
                                                String trailing) {
        StringBuilder body = new StringBuilder()
                .append(McAsciiCodecSupport.formatHex(command, 4))
                .append(McAsciiCodecSupport.formatHex(subcommand, 4))
                .append(McAsciiCodecSupport.formatDeviceNumber(address))
                .append(McAsciiCodecSupport.deviceCodeText(address.getDeviceCode()));
        if (trailing != null) {
            body.append(trailing);
        }
        return body.toString();
    }

    /**
     * 写入或持久化业务数据。
     */
    private static void writeUInt16(byte[] frame, int offset, int value) {
        frame[offset] = (byte) (value & 0xFF);
        frame[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int intValue(DeviceConnection config, String key, int defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        Integer configured = config.getInt(key, defaultValue);
        return configured != null ? configured : defaultValue;
    }
}
