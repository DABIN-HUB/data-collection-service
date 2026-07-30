package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

public interface McFrameCodec {

    String frameType();

    byte[] buildBatchRead(McAddress address, DeviceConnection config);

    byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config);

    byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config);

    byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config);

    default byte[] validateResponse(byte[] request, byte[] response) {
        return response;
    }

    byte[] parseReadPayload(byte[] response);

    int rawReadPayloadLength(McAddress address);

    byte[] normalizeReadPayload(McAddress address, byte[] payload);

    byte[] normalizeWritePayload(McAddress address, byte[] payload);

    void ensureWriteSuccess(byte[] response);

    int readEndCode(byte[] response);
}
