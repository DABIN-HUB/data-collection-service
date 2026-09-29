package com.wangbin.collector.core.collector.protocol.ethernetip.util;

import org.apache.plc4x.java.eip.readwrite.CIPDataTypeCode;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 对 PLC4X 0.13.0 无法表示的 Program: 符号执行独立的 UCMM 标量读取。
 * 每次请求使用受超时约束的独立会话，不复用 PLC4X 的内部会话句柄。
 */
public final class ProgramTagCipClient {
    private static final int MAX_RESPONSE_BYTES = 4096;
    private static final byte[] CONTEXT = "PRGTAG01".getBytes(StandardCharsets.US_ASCII);
    private final String host;
    private final int port;
    private final int timeout;

    public ProgramTagCipClient(String host, int port, int timeout) {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || timeout < 1) {
            throw new IllegalArgumentException("EtherNet/IP Program tag requires host, port and positive timeout");
        }
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    public Object read(String tag) throws IOException {
        byte[] request = encodeRead(tag);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            output.write(packet(0x65, 0, new byte[]{1, 0, 0, 0}));
            Packet registration = receive(input, 0x65, 0);
            if (registration.payload.length != 4 || registration.session == 0) {
                throw new IOException("EtherNet/IP 注册会话响应无效");
            }
            ByteBuffer body = ByteBuffer.allocate(16 + request.length).order(ByteOrder.LITTLE_ENDIAN);
            body.putInt(0).putShort((short) 0).putShort((short) 2);
            body.putShort((short) 0).putShort((short) 0);
            body.putShort((short) 0xB2).putShort((short) request.length).put(request);
            output.write(packet(0x6F, registration.session, body.array()));
            Packet reply = receive(input, 0x6F, registration.session);
            byte[] data = reply.payload;
            if (data.length < 20 || ushort(data, 6) != 2 || ushort(data, 8) != 0
                    || ushort(data, 10) != 0 || ushort(data, 12) != 0xB2
                    || ushort(data, 14) != data.length - 16) {
                throw new IOException("EtherNet/IP UCMM 响应结构无效");
            }
            int service = Byte.toUnsignedInt(data[16]);
            int status = Byte.toUnsignedInt(data[18]);
            int extendedWords = Byte.toUnsignedInt(data[19]);
            int valueOffset = 20 + extendedWords * 2;
            if (service != 0xCC || valueOffset > data.length) {
                throw new IOException("EtherNet/IP CIP 读取响应结构无效");
            }
            if (status != 0) {
                throw new IOException("EtherNet/IP CIP 读取失败，状态=" + status);
            }
            if (data.length < valueOffset + 2) {
                throw new IOException("EtherNet/IP CIP 读取响应缺少类型");
            }
            int typeCode = ushort(data, valueOffset);
            CIPDataTypeCode type = CIPDataTypeCode.enumForValue(typeCode);
            if (type == null) {
                throw new IOException("EtherNet/IP CIP 未知数据类型=" + typeCode);
            }
            int offset = valueOffset + 2;
            int required = switch (type) {
                case BOOL, SINT -> 1;
                case INT -> 2;
                case DINT, REAL -> 4;
                case LINT, LREAL -> 8;
                default -> throw new IOException("EtherNet/IP Program 标量类型暂不支持=" + type);
            };
            if (data.length != offset + required) {
                throw new IOException("EtherNet/IP CIP 标量长度与类型不一致");
            }
            ByteBuffer value = ByteBuffer.wrap(data, offset, required).order(ByteOrder.LITTLE_ENDIAN);
            return switch (type) {
                case BOOL -> data[offset] != 0;
                case SINT -> data[offset];
                case INT -> value.getShort();
                case DINT -> value.getInt();
                case LINT -> value.getLong();
                case REAL -> value.getFloat();
                case LREAL -> value.getDouble();
                default -> throw new IOException("EtherNet/IP Program 标量类型暂不支持=" + type);
            };
        }
    }

    /** 将 Program:Main 保持为同一个 ANSI Extended Symbol Segment。 */
    static byte[] encodeRead(String tag) {
        if (tag == null || !tag.matches("Program:[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)+")) {
            throw new IllegalArgumentException("EtherNet/IP Program tag format invalid");
        }
        String[] elements = tag.split("\\.");
        ByteArrayOutputStream path = new ByteArrayOutputStream();
        for (String element : elements) {
            byte[] symbol = element.getBytes(StandardCharsets.US_ASCII);
            if (symbol.length > 255) {
                throw new IllegalArgumentException("EtherNet/IP Program tag segment too long");
            }
            path.write(0x91);
            path.write(symbol.length);
            path.writeBytes(symbol);
            if ((symbol.length & 1) != 0) {
                path.write(0);
            }
        }
        if (path.size() / 2 > 255) {
            throw new IllegalArgumentException("EtherNet/IP Program tag path too long");
        }
        ByteArrayOutputStream cip = new ByteArrayOutputStream();
        cip.write(0x4C);
        cip.write(path.size() / 2);
        cip.writeBytes(path.toByteArray());
        cip.write(1);
        cip.write(0);
        return cip.toByteArray();
    }

    private static byte[] packet(int command, int session, byte[] payload) {
        ByteBuffer packet = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.putShort((short) command).putShort((short) payload.length);
        packet.putInt(session).putInt(0).put(CONTEXT).putInt(0).put(payload);
        return packet.array();
    }

    private static Packet receive(DataInputStream input, int command, int expectedSession) throws IOException {
        byte[] header = new byte[24];
        input.readFully(header);
        int length = ushort(header, 2);
        int session = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int status = ByteBuffer.wrap(header, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (ushort(header, 0) != command || length > MAX_RESPONSE_BYTES || status != 0
                || (expectedSession != 0 && session != expectedSession)
                || !Arrays.equals(CONTEXT, Arrays.copyOfRange(header, 12, 20))) {
            throw new IOException("EtherNet/IP 封装响应无效");
        }
        byte[] payload = new byte[length];
        input.readFully(payload);
        return new Packet(session, payload);
    }

    private static int ushort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private record Packet(int session, byte[] payload) {
    }
}
