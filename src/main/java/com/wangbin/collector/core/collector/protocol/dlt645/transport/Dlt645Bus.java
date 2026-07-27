package com.wangbin.collector.core.collector.protocol.dlt645.transport;

import com.wangbin.collector.core.collector.protocol.dlt645.Dlt645ProtocolException;
import com.wangbin.collector.core.collector.protocol.dlt645.codec.Dlt645DataCodec;
import com.wangbin.collector.core.collector.protocol.dlt645.codec.Dlt645FrameCodec;
import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645Address;
import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645ControlCode;
import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645Frame;
import com.wangbin.collector.core.connection.serial.SerialChannel;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * DL/T 645 共享总线请求执行器。
 */
public class Dlt645Bus {

    private static final int MAX_FOLLOWING_FRAMES = 16;

    private final SharedSerialChannelManager.Lease lease;
    private final int timeoutMs;
    private final int retryCount;
    private final int wakeUpByteCount;
    private final int interFrameDelayMs;

    public Dlt645Bus(SharedSerialChannelManager.Lease lease,
                     int timeoutMs,
                     int retryCount,
                     int wakeUpByteCount,
                     int interFrameDelayMs) {
        this.lease = lease;
        this.timeoutMs = Math.max(1, timeoutMs);
        this.retryCount = Math.max(0, retryCount);
        this.wakeUpByteCount = Math.max(0, wakeUpByteCount);
        this.interFrameDelayMs = Math.max(0, interFrameDelayMs);
    }

    public byte[] readData(Dlt645Address address, String identifier) throws Exception {
        byte[] dataIdentifier = Dlt645DataCodec.encodeDataIdentifier(identifier);
        return lease.execute(channel -> readDataLocked(channel, address, dataIdentifier));
    }

    public boolean writeData(Dlt645Address address,
                             String identifier,
                             byte[] password,
                             byte[] operatorCode,
                             byte[] value) throws Exception {
        if (password == null || password.length != 4 || operatorCode == null || operatorCode.length != 4) {
            throw new Dlt645ProtocolException("写数据密码和操作者代码必须各为 4 字节");
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(Dlt645DataCodec.encodeDataIdentifier(identifier));
        data.writeBytes(password);
        data.writeBytes(operatorCode);
        data.writeBytes(value);
        Dlt645Frame response = lease.execute(channel -> exchange(channel,
                new Dlt645Frame(address, Dlt645ControlCode.WRITE_DATA.requestCode(), data.toByteArray())));
        validateResponse(response, address, Dlt645ControlCode.WRITE_DATA);
        return true;
    }

    public Dlt645Address readAddress() throws Exception {
        Dlt645Frame response = lease.execute(channel -> exchange(channel,
                new Dlt645Frame(Dlt645Address.BROADCAST,
                        Dlt645ControlCode.READ_ADDRESS.requestCode(), new byte[0])));
        validateResponse(response, response.address(), Dlt645ControlCode.READ_ADDRESS);
        return response.address();
    }

    public boolean isOpen() {
        return lease.isOpen();
    }

    private byte[] readDataLocked(SerialChannel channel,
                                  Dlt645Address address,
                                  byte[] identifier) throws Exception {
        Dlt645Frame response = exchange(channel,
                new Dlt645Frame(address, Dlt645ControlCode.READ_DATA.requestCode(), identifier));
        validateResponse(response, address, Dlt645ControlCode.READ_DATA);
        byte[] firstData = response.data();
        validateIdentifier(firstData, identifier);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(Arrays.copyOfRange(firstData, 4, firstData.length));

        int sequence = 0;
        while (response.hasFollowingData()) {
            if (++sequence > MAX_FOLLOWING_FRAMES) {
                throw new Dlt645ProtocolException("DL/T 645 后续数据帧数量超过限制");
            }
            byte[] followingRequest = Arrays.copyOf(identifier, identifier.length + 1);
            followingRequest[followingRequest.length - 1] = (byte) sequence;
            response = exchange(channel, new Dlt645Frame(address,
                    Dlt645ControlCode.READ_FOLLOWING_DATA.requestCode(), followingRequest));
            validateResponse(response, address, Dlt645ControlCode.READ_FOLLOWING_DATA);
            byte[] followingData = response.data();
            validateIdentifier(followingData, identifier);
            int payloadOffset = followingData.length > 4 && (followingData[4] & 0xFF) == sequence ? 5 : 4;
            payload.writeBytes(Arrays.copyOfRange(followingData, payloadOffset, followingData.length));
        }
        return payload.toByteArray();
    }

    private Dlt645Frame exchange(SerialChannel channel, Dlt645Frame request) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            try {
                channel.write(Dlt645FrameCodec.encode(request, wakeUpByteCount));
                if (interFrameDelayMs > 0) {
                    Thread.sleep(interFrameDelayMs);
                }
                return Dlt645FrameCodec.read(channel, timeoutMs);
            } catch (Exception exception) {
                lastFailure = exception;
            }
        }
        throw new Dlt645ProtocolException("DL/T 645 请求失败，已达到最大重试次数", lastFailure);
    }

    private void validateResponse(Dlt645Frame response,
                                  Dlt645Address expectedAddress,
                                  Dlt645ControlCode expectedCode) throws Dlt645ProtocolException {
        if (!response.response()) {
            throw new Dlt645ProtocolException("收到的 DL/T 645 帧不是从站响应");
        }
        if (!response.address().equals(expectedAddress)) {
            throw new Dlt645ProtocolException("DL/T 645 响应电表地址不匹配");
        }
        if (response.functionCode() != expectedCode.functionCode()) {
            throw new Dlt645ProtocolException("DL/T 645 响应控制码不匹配");
        }
        if (response.abnormal()) {
            byte[] data = response.data();
            int errorCode = data.length == 0 ? 0 : data[0] & 0xFF;
            throw new Dlt645ProtocolException(String.format("电表返回异常应答，错误码=0x%02X", errorCode));
        }
    }

    private void validateIdentifier(byte[] responseData, byte[] identifier) throws Dlt645ProtocolException {
        if (responseData.length < 4 || !Arrays.equals(identifier, Arrays.copyOf(responseData, 4))) {
            throw new Dlt645ProtocolException("DL/T 645 响应数据标识不匹配");
        }
    }
}
