package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

public class Mc3eAsciiFrameCodec implements McFrameCodec {

    @Override
    public String frameType() {
        return "3E_ASCII";
    }

    @Override
    public byte[] buildBatchRead(McAddress address, DeviceConnection config) {
        return McFrameBuilder.buildAsciiBatchRead(address, config);
    }

    @Override
    public byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config) {
        return McFrameBuilder.buildAsciiBatchWrite(address, payload, config);
    }

    @Override
    public byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config) {
        return McFrameBuilder.buildAsciiRandomRead(request, config);
    }

    @Override
    public byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config) {
        return McFrameBuilder.buildAsciiRandomWrite(request, config);
    }

    @Override
    public byte[] parseReadPayload(byte[] response) {
        return McResponseParser.parseAsciiReadPayload(response);
    }

    @Override
    public int rawReadPayloadLength(McAddress address) {
        return McAsciiCodecSupport.rawReadPayloadLength(address);
    }

    @Override
    public byte[] normalizeReadPayload(McAddress address, byte[] payload) {
        return McAsciiCodecSupport.decodeReadPayload(address, payload);
    }

    @Override
    public byte[] normalizeWritePayload(McAddress address, byte[] payload) {
        return McAsciiCodecSupport.encodeWritePayload(address, payload);
    }

    @Override
    public void ensureWriteSuccess(byte[] response) {
        McResponseParser.ensureAsciiWriteSuccess(response);
    }

    @Override
    public int readEndCode(byte[] response) {
        return McResponseParser.readAsciiEndCode(response);
    }
}
