package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

import java.util.concurrent.atomic.AtomicInteger;

public class Mc4eBinaryFrameCodec implements McFrameCodec {

    private final AtomicInteger serialCounter = new AtomicInteger();

    @Override
    public String frameType() {
        return "4E_BINARY";
    }

    @Override
    public byte[] buildBatchRead(McAddress address, DeviceConnection config) {
        return McFrameBuilder.build4eBatchRead(address, config, nextSerial());
    }

    @Override
    public byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config) {
        return McFrameBuilder.build4eBatchWrite(address, payload, config, nextSerial());
    }

    @Override
    public byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config) {
        return McFrameBuilder.build4eRandomRead(request, config, nextSerial());
    }

    @Override
    public byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config) {
        return McFrameBuilder.build4eRandomWrite(request, config, nextSerial());
    }

    @Override
    public byte[] parseReadPayload(byte[] response) {
        return McResponseParser.parse4eBinaryReadPayload(response);
    }

    @Override
    public int rawReadPayloadLength(McAddress address) {
        return address != null ? address.getExpectedPayloadLength() : 0;
    }

    @Override
    public byte[] normalizeReadPayload(McAddress address, byte[] payload) {
        return payload != null ? payload.clone() : new byte[0];
    }

    @Override
    public byte[] normalizeWritePayload(McAddress address, byte[] payload) {
        return payload != null ? payload.clone() : new byte[0];
    }

    @Override
    public void ensureWriteSuccess(byte[] response) {
        McResponseParser.ensure4eBinaryWriteSuccess(response);
    }

    @Override
    public int readEndCode(byte[] response) {
        return McResponseParser.read4eBinaryEndCode(response);
    }

    private int nextSerial() {
        return serialCounter.updateAndGet(current -> (current + 1) & 0xFFFF);
    }
}
