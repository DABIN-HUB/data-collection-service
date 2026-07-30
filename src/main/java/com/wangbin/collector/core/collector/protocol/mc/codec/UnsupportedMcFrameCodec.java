package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

public class UnsupportedMcFrameCodec implements McFrameCodec {

    private final String frameType;

    public UnsupportedMcFrameCodec(String frameType) {
        this.frameType = frameType;
    }

    @Override
    public String frameType() {
        return frameType;
    }

    @Override
    public byte[] buildBatchRead(McAddress address, DeviceConnection config) {
        throw unsupported();
    }

    @Override
    public byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config) {
        throw unsupported();
    }

    @Override
    public byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config) {
        throw unsupported();
    }

    @Override
    public byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config) {
        throw unsupported();
    }

    @Override
    public byte[] parseReadPayload(byte[] response) {
        throw unsupported();
    }

    @Override
    public int rawReadPayloadLength(McAddress address) {
        throw unsupported();
    }

    @Override
    public byte[] normalizeReadPayload(McAddress address, byte[] payload) {
        throw unsupported();
    }

    @Override
    public byte[] normalizeWritePayload(McAddress address, byte[] payload) {
        throw unsupported();
    }

    @Override
    public void ensureWriteSuccess(byte[] response) {
        throw unsupported();
    }

    @Override
    public int readEndCode(byte[] response) {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Mitsubishi MC frame type is not implemented yet: " + frameType);
    }
}
