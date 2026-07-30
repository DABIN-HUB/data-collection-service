package com.wangbin.collector.core.collector.protocol.iec101.transport;

import com.wangbin.collector.core.collector.protocol.iec101.Iec101ProtocolException;
import com.wangbin.collector.core.collector.protocol.iec101.codec.Iec101Ft12Codec;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101Frame;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101FrameType;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101LinkConfig;
import com.wangbin.collector.core.collector.protocol.iec101.link.Iec101LinkStateMachine;
import com.wangbin.collector.core.connection.serial.SerialChannel;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;

import java.util.ArrayList;
import java.util.List;

/**
 * IEC101 非平衡控制站总线。
 */
public class Iec101Bus {

    private static final int FUNCTION_RESET_REMOTE_LINK = 0;
    private static final int FUNCTION_SEND_CONFIRMED_USER_DATA = 3;
    private static final int FUNCTION_REQUEST_LINK_STATUS = 9;
    private static final int FUNCTION_REQUEST_CLASS_1 = 10;
    private static final int FUNCTION_REQUEST_CLASS_2 = 11;
    private static final int SECONDARY_USER_DATA = 8;
    private static final int SECONDARY_NO_DATA = 9;

    private final SharedSerialChannelManager.Lease lease;
    private final Iec101LinkConfig config;
    private final int timeoutMs;
    private final int retryCount;
    private final int interFrameDelayMs;
    private final Iec101LinkStateMachine linkState = new Iec101LinkStateMachine();

    public Iec101Bus(SharedSerialChannelManager.Lease lease,
                     Iec101LinkConfig config,
                     int timeoutMs,
                     int retryCount,
                     int interFrameDelayMs) {
        this.lease = lease;
        this.config = config;
        this.timeoutMs = Math.max(1, timeoutMs);
        this.retryCount = Math.max(0, retryCount);
        this.interFrameDelayMs = Math.max(0, interFrameDelayMs);
    }

    public void resetLink(int linkAddress) throws Exception {
        lease.execute(channel -> {
            Iec101Frame request = fixedRequest(
                    linkState.primaryControl(FUNCTION_RESET_REMOTE_LINK, false), linkAddress);
            Iec101Frame response = exchange(channel, request, false);
            validateAcknowledgement(response, linkAddress);
            linkState.reset();
            return null;
        });
    }

    public void requestLinkStatus(int linkAddress) throws Exception {
        lease.execute(channel -> {
            Iec101Frame response = exchange(channel,
                    fixedRequest(linkState.primaryControl(FUNCTION_REQUEST_LINK_STATUS, false), linkAddress),
                    false);
            validateSecondaryResponse(response, linkAddress);
            return null;
        });
    }

    public List<byte[]> sendAsdu(int linkAddress, byte[] asdu, int maxClassPolls) throws Exception {
        return lease.execute(channel -> {
            List<byte[]> responses = new ArrayList<>();
            Iec101Frame request = new Iec101Frame(
                    Iec101FrameType.VARIABLE,
                    linkState.primaryControl(FUNCTION_SEND_CONFIRMED_USER_DATA, true),
                    linkAddress,
                    asdu);
            Iec101Frame response = exchange(channel, request, true);
            collectUserData(response, linkAddress, responses);
            boolean accessDemand = response.accessDemand();
            int polls = 0;
            while ((accessDemand || polls == 0) && polls++ < Math.max(1, maxClassPolls)) {
                Iec101Frame classResponse = requestClassLocked(channel, linkAddress, true);
                collectUserData(classResponse, linkAddress, responses);
                accessDemand = classResponse.accessDemand();
                if (classResponse.functionCode() == SECONDARY_NO_DATA && !accessDemand) {
                    break;
                }
            }
            return responses;
        });
    }

    public List<byte[]> requestClass(int linkAddress, boolean classOne) throws Exception {
        return lease.execute(channel -> {
            List<byte[]> responses = new ArrayList<>();
            Iec101Frame response = requestClassLocked(channel, linkAddress, classOne);
            collectUserData(response, linkAddress, responses);
            return responses;
        });
    }

    public boolean isOpen() {
        return lease.isOpen();
    }

    private Iec101Frame requestClassLocked(SerialChannel channel,
                                           int linkAddress,
                                           boolean classOne) throws Exception {
        int function = classOne ? FUNCTION_REQUEST_CLASS_1 : FUNCTION_REQUEST_CLASS_2;
        Iec101Frame request = fixedRequest(linkState.primaryControl(function, true), linkAddress);
        return exchange(channel, request, true);
    }

    private Iec101Frame exchange(SerialChannel channel,
                                 Iec101Frame request,
                                 boolean frameCountConfirmed) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            try {
                channel.write(Iec101Ft12Codec.encode(request, config.linkAddressSize()));
                if (interFrameDelayMs > 0) {
                    Thread.sleep(interFrameDelayMs);
                }
                Iec101Frame response = Iec101Ft12Codec.read(channel, config.linkAddressSize(), timeoutMs);
                validateSecondaryResponse(response, request.linkAddress());
                if (response.dataFlowControl()) {
                    throw new Iec101ProtocolException("IEC101 从站数据流控制忙");
                }
                if (frameCountConfirmed) {
                    linkState.markConfirmedSuccess();
                }
                return response;
            } catch (Exception exception) {
                lastFailure = exception;
            }
        }
        throw new Iec101ProtocolException("IEC101 链路请求失败，已达到最大重试次数", lastFailure);
    }

    private void collectUserData(Iec101Frame response,
                                 int linkAddress,
                                 List<byte[]> target) throws Iec101ProtocolException {
        validateSecondaryResponse(response, linkAddress);
        if (response.type() == Iec101FrameType.VARIABLE
                && response.functionCode() == SECONDARY_USER_DATA
                && response.userData().length > 0) {
            target.add(response.userData());
        }
    }

    private void validateAcknowledgement(Iec101Frame response,
                                         int linkAddress) throws Iec101ProtocolException {
        validateSecondaryResponse(response, linkAddress);
        if (response.type() == Iec101FrameType.SINGLE_ACK || response.functionCode() == 0) {
            return;
        }
        throw new Iec101ProtocolException("IEC101 链路复位未收到确认");
    }

    private void validateSecondaryResponse(Iec101Frame response,
                                           int linkAddress) throws Iec101ProtocolException {
        if (response.type() == Iec101FrameType.SINGLE_ACK) {
            return;
        }
        if (response.linkAddress() != linkAddress) {
            throw new Iec101ProtocolException("IEC101 响应链路地址不匹配");
        }
        if ((response.control() & 0x40) != 0) {
            throw new Iec101ProtocolException("IEC101 响应控制域错误");
        }
    }

    private Iec101Frame fixedRequest(int control, int linkAddress) {
        return new Iec101Frame(Iec101FrameType.FIXED, control, linkAddress, new byte[0]);
    }
}
