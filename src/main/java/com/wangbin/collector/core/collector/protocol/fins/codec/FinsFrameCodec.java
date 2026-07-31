package com.wangbin.collector.core.collector.protocol.fins.codec;

import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsConnectionConfig;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;

import java.util.Arrays;

/**
 * 定义当前模块的业务组件。
 */
public final class FinsFrameCodec {

    private static final int HEADER_LENGTH = 10;
    private static final int RESPONSE_MIN_LENGTH = 14;
    private static final int COMMAND_MEMORY_AREA = 0x01;
    private static final int SUBCOMMAND_MEMORY_READ = 0x01;
    private static final int SUBCOMMAND_MEMORY_WRITE = 0x02;

    /**
     * 创建当前组件实例。
     */
    private FinsFrameCodec() {
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildReadRequest(FinsConnectionConfig config, int sid, FinsAddress address) {
        return buildMemoryReadRequest(config, sid, address.getMemoryArea(), address.getWordAddress(),
                address.getBitOffset() != null ? address.getBitOffset() : 0,
                address.readUnitCount(), address.isBitUnit());
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildBatchReadRequest(FinsConnectionConfig config,
                                               int sid,
                                               FinsMemoryArea memoryArea,
                                               int startWord,
                                               int unitCount,
                                               boolean bitUnit) {
        return buildMemoryReadRequest(config, sid, memoryArea, startWord, 0, unitCount, bitUnit);
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildWriteRequest(FinsConnectionConfig config,
                                           int sid,
                                           FinsAddress address,
                                           byte[] payload) {
        return buildMemoryWriteRequest(config, sid, address.getMemoryArea(), address.getWordAddress(),
                address.getBitOffset() != null ? address.getBitOffset() : 0,
                address.readUnitCount(), address.isBitUnit(), payload);
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildBatchWriteRequest(FinsConnectionConfig config,
                                                int sid,
                                                FinsMemoryArea memoryArea,
                                                int startWord,
                                                int unitCount,
                                                boolean bitUnit,
                                                byte[] payload) {
        return buildMemoryWriteRequest(config, sid, memoryArea, startWord, 0, unitCount, bitUnit, payload);
    }

    /**
     * 解析或转换业务数据。
     */
    public static FinsResponse parseReadResponse(byte[] frame, int expectedSid) {
        return parseResponse(frame, expectedSid, COMMAND_MEMORY_AREA, SUBCOMMAND_MEMORY_READ);
    }

    /**
     * 解析或转换业务数据。
     */
    public static FinsResponse parseWriteResponse(byte[] frame, int expectedSid) {
        return parseResponse(frame, expectedSid, COMMAND_MEMORY_AREA, SUBCOMMAND_MEMORY_WRITE);
    }

    /**
     * 创建并返回业务对象。
     */
    public static byte[] buildCommandRequest(FinsConnectionConfig config,
                                             int sid,
                                             int mainCommand,
                                             int subCommand,
                                             byte[] payload) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        byte[] frame = new byte[HEADER_LENGTH + 2 + safePayload.length];
        System.arraycopy(buildHeader(config, sid), 0, frame, 0, HEADER_LENGTH);
        frame[10] = (byte) (mainCommand & 0xFF);
        frame[11] = (byte) (subCommand & 0xFF);
        System.arraycopy(safePayload, 0, frame, 12, safePayload.length);
        return frame;
    }

    /**
     * 解析或转换业务数据。
     */
    public static FinsResponse parseResponse(byte[] frame,
                                             int expectedSid,
                                             int expectedMainCommand,
                                             int expectedSubCommand) {
        if (frame == null || frame.length < RESPONSE_MIN_LENGTH) {
            throw new IllegalArgumentException("FINS response is too short");
        }
        int sid = frame[9] & 0xFF;
        if (expectedSid >= 0 && sid != (expectedSid & 0xFF)) {
            throw new IllegalArgumentException("Unexpected FINS SID: expected=" + expectedSid + ", actual=" + sid);
        }
        int mainCommand = frame[10] & 0xFF;
        int subCommand = frame[11] & 0xFF;
        if (mainCommand != expectedMainCommand || subCommand != expectedSubCommand) {
            throw new IllegalArgumentException("Unexpected FINS command echo: " + mainCommand + "/" + subCommand);
        }
        int endCode = ((frame[12] & 0xFF) << 8) | (frame[13] & 0xFF);
        return new FinsResponse(sid, mainCommand, subCommand, endCode,
                Arrays.copyOfRange(frame, RESPONSE_MIN_LENGTH, frame.length));
    }

    /**
     * 创建并返回业务对象。
     */
    private static byte[] buildMemoryReadRequest(FinsConnectionConfig config,
                                                 int sid,
                                                 FinsMemoryArea memoryArea,
                                                 int startWord,
                                                 int bitOffset,
                                                 int unitCount,
                                                 boolean bitUnit) {
        byte[] header = buildHeader(config, sid);
        byte[] frame = new byte[HEADER_LENGTH + 8];
        System.arraycopy(header, 0, frame, 0, HEADER_LENGTH);
        frame[10] = (byte) COMMAND_MEMORY_AREA;
        frame[11] = (byte) SUBCOMMAND_MEMORY_READ;
        frame[12] = (byte) memoryArea.code(bitUnit);
        frame[13] = (byte) ((startWord >> 8) & 0xFF);
        frame[14] = (byte) (startWord & 0xFF);
        frame[15] = (byte) (bitOffset & 0xFF);
        frame[16] = (byte) ((unitCount >> 8) & 0xFF);
        frame[17] = (byte) (unitCount & 0xFF);
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    private static byte[] buildMemoryWriteRequest(FinsConnectionConfig config,
                                                  int sid,
                                                  FinsMemoryArea memoryArea,
                                                  int startWord,
                                                  int bitOffset,
                                                  int unitCount,
                                                  boolean bitUnit,
                                                  byte[] payload) {
        byte[] header = buildHeader(config, sid);
        byte[] frame = new byte[HEADER_LENGTH + 8 + payload.length];
        System.arraycopy(header, 0, frame, 0, HEADER_LENGTH);
        frame[10] = (byte) COMMAND_MEMORY_AREA;
        frame[11] = (byte) SUBCOMMAND_MEMORY_WRITE;
        frame[12] = (byte) memoryArea.code(bitUnit);
        frame[13] = (byte) ((startWord >> 8) & 0xFF);
        frame[14] = (byte) (startWord & 0xFF);
        frame[15] = (byte) (bitOffset & 0xFF);
        frame[16] = (byte) ((unitCount >> 8) & 0xFF);
        frame[17] = (byte) (unitCount & 0xFF);
        System.arraycopy(payload, 0, frame, 18, payload.length);
        return frame;
    }

    /**
     * 创建并返回业务对象。
     */
    private static byte[] buildHeader(FinsConnectionConfig config, int sid) {
        byte[] header = new byte[HEADER_LENGTH];
        header[0] = (byte) 0x80;
        header[1] = 0x00;
        header[2] = 0x02;
        header[3] = (byte) config.getPlcNetwork();
        header[4] = (byte) config.getPlcNode();
        header[5] = (byte) config.getPlcUnit();
        header[6] = (byte) config.getLocalNetwork();
        header[7] = (byte) config.getLocalNode();
        header[8] = (byte) config.getLocalUnit();
        header[9] = (byte) (sid & 0xFF);
        return header;
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record FinsResponse(int sid,
                               int mainCommand,
                               int subCommand,
                               int endCode,
                               byte[] payload) {
        /**
         * 构造标准业务结果。
         */
        public boolean success() {
            return endCode == 0;
        }
    }
}
