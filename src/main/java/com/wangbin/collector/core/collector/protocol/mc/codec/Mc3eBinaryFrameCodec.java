package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

/**
 * 定义当前模块的业务组件。
 */
public class Mc3eBinaryFrameCodec implements McFrameCodec {

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public String frameType() {
        return "3E_BINARY";
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public byte[] buildBatchRead(McAddress address, DeviceConnection config) {
        return McFrameBuilder.buildBatchRead(address, config);
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config) {
        return McFrameBuilder.buildBatchWrite(address, payload, config);
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config) {
        return McFrameBuilder.buildRandomRead(request, config);
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config) {
        return McFrameBuilder.buildRandomWrite(request, config);
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public byte[] parseReadPayload(byte[] response) {
        return McResponseParser.parseReadPayload(response);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public int rawReadPayloadLength(McAddress address) {
        return address != null ? address.getExpectedPayloadLength() : 0;
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public byte[] normalizeReadPayload(McAddress address, byte[] payload) {
        return payload != null ? payload.clone() : new byte[0];
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public byte[] normalizeWritePayload(McAddress address, byte[] payload) {
        return payload != null ? payload.clone() : new byte[0];
    }

    /**
     * 校验业务条件和参数边界。
     */
    @Override
    public void ensureWriteSuccess(byte[] response) {
        McResponseParser.ensureWriteSuccess(response);
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public int readEndCode(byte[] response) {
        return McResponseParser.readEndCode(response);
    }
}
