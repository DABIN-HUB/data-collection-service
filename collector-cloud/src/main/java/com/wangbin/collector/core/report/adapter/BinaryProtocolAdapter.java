package com.wangbin.collector.core.report.adapter;

import com.wangbin.collector.core.report.model.message.IoTMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 二进制协议编码器。
 */
@Slf4j
public class BinaryProtocolAdapter {

    private static final byte MAGIC_NUMBER = (byte) 0x7E;
    private static final byte PROTOCOL_VERSION = 0x01;
    private static final byte MESSAGE_TYPE_REQUEST = 0x01;
    private final JsonProtocolAdapter jsonAdapter = new JsonProtocolAdapter();

    /**
     * 将上报数据编码为 TCP 二进制报文。
     */
    public byte[] encodeToBinary(IoTMessage message) {
        if (message == null) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            byte[] messageIdBytes = toBytes(message.getMessageId());
            byte[] methodBytes = toBytes(message.getMethod());
            byte[] bodyBytes = buildBodyBytes(message);

            int totalLength = 7
                    + Short.BYTES + messageIdBytes.length
                    + Short.BYTES + methodBytes.length
                    + bodyBytes.length;

            dos.writeByte(MAGIC_NUMBER);
            dos.writeByte(PROTOCOL_VERSION);
            dos.writeByte(MESSAGE_TYPE_REQUEST);
            dos.writeInt(totalLength);

            dos.writeShort(messageIdBytes.length);
            if (messageIdBytes.length > 0) {
                dos.write(messageIdBytes);
            }

            dos.writeShort(methodBytes.length);
            if (methodBytes.length > 0) {
                dos.write(methodBytes);
            }

            if (bodyBytes.length > 0) {
                dos.write(bodyBytes);
            }

            dos.flush();
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("失败 to encode binary 消息", e);
            return null;
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private byte[] buildBodyBytes(IoTMessage message) {
        String jsonBody = jsonAdapter.encodeToJson(message);
        if (jsonBody == null) {
            return new byte[0];
        }
        return jsonBody.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解析或转换业务数据。
     */
    private byte[] toBytes(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }
}