package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

/**
 * 定义当前模块的业务契约。
 */
public interface McFrameCodec {

    /**
     * 执行当前业务逻辑。
     */
    String frameType();

    /**
     * 创建并返回业务对象。
     */
    byte[] buildBatchRead(McAddress address, DeviceConnection config);

    /**
     * 创建并返回业务对象。
     */
    byte[] buildBatchWrite(McAddress address, byte[] payload, DeviceConnection config);

    /**
     * 创建并返回业务对象。
     */
    byte[] buildRandomRead(McRandomReadRequest request, DeviceConnection config);

    /**
     * 创建并返回业务对象。
     */
    byte[] buildRandomWrite(McRandomWriteRequest request, DeviceConnection config);

    /**
     * 校验业务条件和参数边界。
     */
    default byte[] validateResponse(byte[] request, byte[] response) {
        return response;
    }

    /**
     * 解析或转换业务数据。
     */
    byte[] parseReadPayload(byte[] response);

    /**
     * 执行当前业务逻辑。
     */
    int rawReadPayloadLength(McAddress address);

    /**
     * 解析或转换业务数据。
     */
    byte[] normalizeReadPayload(McAddress address, byte[] payload);

    /**
     * 解析或转换业务数据。
     */
    byte[] normalizeWritePayload(McAddress address, byte[] payload);

    /**
     * 校验业务条件和参数边界。
     */
    void ensureWriteSuccess(byte[] response);

    /**
     * 查询并返回业务数据。
     */
    int readEndCode(byte[] response);
}
