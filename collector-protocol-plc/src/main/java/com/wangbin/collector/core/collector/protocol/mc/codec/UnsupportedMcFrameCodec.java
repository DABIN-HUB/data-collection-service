package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

/**
 * 定义当前模块的业务组件。
 */
public class UnsupportedMcFrameCodec implements McFrameCodec {

    private final String frameType;

    /**
     * 创建当前组件实例。
     */
    public UnsupportedMcFrameCodec(String frameType) {
        this.frameType = frameType;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public String frameType() {
        return frameType;
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public byte[] buildBatchRead(McAddress address, DeviceConnection config) {
        throw unsupported();
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config) {
        throw unsupported();
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config) {
        throw unsupported();
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config) {
        throw unsupported();
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public byte[] parseReadPayload(byte[] response) {
        throw unsupported();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public int rawReadPayloadLength(McAddress address) {
        throw unsupported();
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public byte[] normalizeReadPayload(McAddress address, byte[] payload) {
        throw unsupported();
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public byte[] normalizeWritePayload(McAddress address, byte[] payload) {
        throw unsupported();
    }

    /**
     * 校验业务条件和参数边界。
     */
    @Override
    public void ensureWriteSuccess(byte[] response) {
        throw unsupported();
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public int readEndCode(byte[] response) {
        throw unsupported();
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Mitsubishi MC frame type is not implemented yet: " + frameType);
    }
}
