package com.wangbin.collector.core.collector.protocol.bacnet.client;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetConfirmedCovNotificationCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetCovNotificationDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrame;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSegmentSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSimpleAckDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSubscribeCovCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSubscribeCovPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetTransportFrameSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetWritePropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetWritePropertyMultipleCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetClientSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.transport.BacnetMstpTokenManager;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class BacnetMstpClient implements AutoCloseable {

    private final BacnetMstpTokenManager tokenManager;
    private final int remoteMacAddress;
    private final AtomicLong requestRetryCount = new AtomicLong(0);
    private final AtomicLong requestTimeoutCount = new AtomicLong(0);
    private final AtomicLong invokeIdMismatchCount = new AtomicLong(0);
    private final AtomicLong covNotificationCount = new AtomicLong(0);
    private final AtomicLong segmentedResponseCount = new AtomicLong(0);
    private final BacnetClientSupport clientSupport =
            new BacnetClientSupport(invokeIdMismatchCount, covNotificationCount, segmentedResponseCount);

    private volatile Consumer<BacnetCovNotification> covNotificationHandler;

    /**
     * 创建当前组件实例。
     */
    public BacnetMstpClient(BacnetMstpTokenManager tokenManager, int remoteMacAddress) {
        this.tokenManager = tokenManager;
        this.remoteMacAddress = remoteMacAddress;
        this.tokenManager.setIncomingFrameHandler(this::handleIncomingFrame);
    }

    public void setCovNotificationHandler(Consumer<BacnetCovNotification> handler) {
        this.covNotificationHandler = handler;
    }

    /**
     * 查询并返回业务数据。
     */
    public BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request,
                                                   long timeoutMs,
                                                   long segmentTimeoutMs,
                                                   int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetReadPropertyCodec.encode(request));
        return exchangeConfirmed(npdu, timeoutMs, segmentTimeoutMs, retries,
                frame -> BacnetReadPropertyResponseDecoder.decode(frame, request.getInvokeId()));
    }

    /**
     * 查询并返回业务数据。
     */
    public BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                   long timeoutMs,
                                                                   long segmentTimeoutMs,
                                                                   int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetReadPropertyMultipleCodec.encode(request));
        return exchangeConfirmed(npdu, timeoutMs, segmentTimeoutMs, retries,
                frame -> BacnetReadPropertyMultipleResponseDecoder.decode(frame, request.getInvokeId()));
    }

    /**
     * 写入或持久化业务数据。
     */
    public void writeProperty(BacnetWritePropertyRequest request, long timeoutMs, int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetWritePropertyCodec.encode(request));
        exchangeConfirmed(npdu, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetWritePropertyCodec.SERVICE_CHOICE_WRITE_PROPERTY);
            return null;
        });
    }

    /**
     * 写入或持久化业务数据。
     */
    public void writePropertyMultiple(BacnetWritePropertyMultipleRequest request, long timeoutMs, int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetWritePropertyMultipleCodec.encode(request));
        exchangeConfirmed(npdu, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetWritePropertyMultipleCodec.SERVICE_CHOICE_WRITE_PROPERTY_MULTIPLE);
            return null;
        });
    }

    /**
     * 维护注册或订阅关系。
     */
    public void subscribeCov(BacnetSubscribeCovRequest request, long timeoutMs, int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetSubscribeCovCodec.encode(request));
        exchangeConfirmed(npdu, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetSubscribeCovCodec.SERVICE_CHOICE_SUBSCRIBE_COV);
            return null;
        });
    }

    /**
     * 维护注册或订阅关系。
     */
    public void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request, long timeoutMs, int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetSubscribeCovPropertyCodec.encode(request));
        exchangeConfirmed(npdu, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetSubscribeCovPropertyCodec.SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY);
            return null;
        });
    }

    /**
     * 执行当前业务逻辑。
     */
    public void acknowledgeConfirmedCovNotification(int invokeId) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetConfirmedCovNotificationCodec.encodeAck(invokeId));
        tokenManager.sendUnconfirmed(remoteMacAddress, npdu, 1000);
    }

    public long getRequestRetryCount() { return requestRetryCount.get(); }
    public long getRequestTimeoutCount() { return requestTimeoutCount.get(); }
    public long getInvokeIdMismatchCount() { return invokeIdMismatchCount.get(); }
    public long getCovNotificationCount() { return covNotificationCount.get(); }
    public long getSegmentedResponseCount() { return segmentedResponseCount.get(); }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void close() {
        tokenManager.setIncomingFrameHandler(null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> T exchangeConfirmed(byte[] requestNpdu,
                                    long timeoutMs,
                                    long segmentTimeoutMs,
                                    int retries,
                                    FrameDecoder<T> decoder) throws Exception {
        int attempts = Math.max(0, retries) + 1;
        int resolvedTimeout = resolveTimeout(timeoutMs);
        int resolvedSegmentTimeout = resolveTimeout(segmentTimeoutMs > 0 ? segmentTimeoutMs : timeoutMs);
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            long deadline = System.currentTimeMillis() + resolvedTimeout;
            tokenManager.beginConfirmedRequest(remoteMacAddress, requestNpdu, resolvedTimeout);
            try {
                while (System.currentTimeMillis() <= deadline) {
                    BacnetMstpFrame frame = tokenManager.awaitConversationFrame(Math.max(1L, deadline - System.currentTimeMillis()));
                    if (frame.frameType() == com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrameType.REPLY_POSTPONED) {
                        continue;
                    }
                    byte[] wrapped = BacnetTransportFrameSupport.wrapNpdu(frame.data());
                    if (!BacnetSegmentSupport.isSegmentedComplexAck(wrapped)) {
                        try {
                            return decoder.decode(wrapped);
                        } catch (Exception ex) {
                            if (clientSupport.isInvokeIdMismatch(ex)) {
                                clientSupport.recordInvokeIdMismatch();
                                lastFailure = ex;
                                continue;
                            }
                            throw ex;
                        }
                    }
                    clientSupport.recordSegmentedResponse();
                    byte[] assembled = collectSegmentedComplexAck(frame, resolvedSegmentTimeout);
                    return decoder.decode(assembled);
                }
            } catch (SocketTimeoutException ex) {
                lastFailure = ex;
            } finally {
                tokenManager.finishConversation();
            }

            lastFailure = new SocketTimeoutException("BACnet MS/TP receive timed out after " + resolvedTimeout + "ms");
            requestTimeoutCount.incrementAndGet();
            if (attempt < attempts) {
                requestRetryCount.incrementAndGet();
                log.debug("BACnet MS/TP 请求超时后重试, 远端MAC={}, 重试次数={}/{}",
                        remoteMacAddress, attempt + 1, attempts);
            }
        }
        throw lastFailure;
    }

    /**
     * 执行当前业务逻辑。
     */
    private byte[] collectSegmentedComplexAck(BacnetMstpFrame firstFrame, int segmentTimeoutMs) throws Exception {
        com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetSegmentAssembler assembler =
                new com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetSegmentAssembler();
        return assembler.collect(BacnetTransportFrameSupport.wrapNpdu(firstFrame.data()),
                segmentTimeoutMs,
                (timeout, unit) -> {
                    BacnetMstpFrame nextFrame = tokenManager.awaitConversationFrame((int) unit.toMillis(timeout));
                    return nextFrame != null ? BacnetTransportFrameSupport.wrapNpdu(nextFrame.data()) : null;
                },
                (invokeId, sequenceNumber, proposedWindowSize) -> tokenManager.sendConversationFrame(
                        remoteMacAddress,
                        BacnetTransportFrameSupport.unwrapBvlc(BacnetSegmentSupport.encodeSegmentAck(
                                invokeId,
                                sequenceNumber,
                                proposedWindowSize)),
                        true));
    }

    /**
     * 处理当前业务流程。
     */
    private void handleIncomingFrame(BacnetMstpFrame frame) {
        if (frame == null || !frame.frameType().isDataFrame()) {
            return;
        }
        try {
            byte[] wrapped = BacnetTransportFrameSupport.wrapNpdu(frame.data());
            if (!BacnetCovNotificationDecoder.isUnconfirmedCovNotification(wrapped)
                    && !BacnetCovNotificationDecoder.isConfirmedCovNotification(wrapped)) {
                return;
            }
            clientSupport.handleCovNotification(
                    wrapped,
                    "remoteMac=" + remoteMacAddress,
                    invokeId -> {
                        try {
                            acknowledgeConfirmedCovNotification(invokeId);
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    },
                    covNotificationHandler);
        } catch (Exception ex) {
            log.warn("解码 BACnet MS/TP COV 通知 失败, 远端MAC={}", remoteMacAddress, ex);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveTimeout(long timeoutMs) {
        if (timeoutMs <= 0) {
            return 5000;
        }
        if (timeoutMs > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) timeoutMs;
    }

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    private interface FrameDecoder<T> {
        /**
         * 解析或转换业务数据。
         */
        T decode(byte[] frame) throws Exception;
    }
}
