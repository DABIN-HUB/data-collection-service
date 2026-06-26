package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

public class Mc3eBinaryFrameCodec implements McFrameCodec {

    @Override
    public String frameType() {
        return "3E_BINARY";
    }

    @Override
    public byte[] buildBatchRead(McAddress address, DeviceConnection config) {
        return McFrameBuilder.buildBatchRead(address, config);
    }

    @Override
    public byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config) {
        return McFrameBuilder.buildBatchWrite(address, payload, config);
    }

    @Override
    public byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config) {
        return McFrameBuilder.buildRandomRead(request, config);
    }

    @Override
    public byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config) {
        return McFrameBuilder.buildRandomWrite(request, config);
    }

    @Override
    public byte[] parseReadPayload(byte[] response) {
        return McResponseParser.parseReadPayload(response);
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
        McResponseParser.ensureWriteSuccess(response);
    }

    @Override
    public int readEndCode(byte[] response) {
        return McResponseParser.readEndCode(response);
    }
}
