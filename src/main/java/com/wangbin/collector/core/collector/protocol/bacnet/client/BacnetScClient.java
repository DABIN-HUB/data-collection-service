package com.wangbin.collector.core.collector.protocol.bacnet.client;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetConfirmedCovNotificationCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetCovNotificationDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSegmentSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSimpleAckDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSubscribeCovCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSubscribeCovPropertyCodec;
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
import com.wangbin.collector.core.connection.adapter.BacnetScConnectionAdapter;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetClientSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetRequestSession;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetSegmentAssembler;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class BacnetScClient implements AutoCloseable {

    private static final int RECEIVE_POLL_TIMEOUT_MS = 250;

    private final BacnetScConnectionAdapter connectionAdapter;
    private final BlockingQueue<byte[]> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong requestRetryCount = new AtomicLong(0);
    private final AtomicLong requestTimeoutCount = new AtomicLong(0);
    private final AtomicLong invokeIdMismatchCount = new AtomicLong(0);
    private final AtomicLong covNotificationCount = new AtomicLong(0);
    private final AtomicLong segmentedResponseCount = new AtomicLong(0);
    private final BacnetClientSupport clientSupport =
            new BacnetClientSupport(invokeIdMismatchCount, covNotificationCount, segmentedResponseCount);
    private final BacnetRequestSession requestSession =
            new BacnetRequestSession(requestRetryCount, requestTimeoutCount, clientSupport);
    private final BacnetSegmentAssembler segmentAssembler = new BacnetSegmentAssembler();
    private final Thread receiverThread;

    private volatile Consumer<BacnetCovNotification> covNotificationHandler;

    /**
     * 创建当前组件实例。
     */
    public BacnetScClient(BacnetScConnectionAdapter connectionAdapter) {
        this.connectionAdapter = connectionAdapter;
        this.receiverThread = new Thread(this::receiveLoop, "bacnet-sc-client-" + connectionAdapter.getDeviceId());
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
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
        return exchange(BacnetReadPropertyCodec.encode(request),
                timeoutMs,
                segmentTimeoutMs,
                retries,
                frame -> BacnetReadPropertyResponseDecoder.decode(frame, request.getInvokeId()));
    }

    /**
     * 查询并返回业务数据。
     */
    public BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                   long timeoutMs,
                                                                   long segmentTimeoutMs,
                                                                   int retries) throws Exception {
        return exchange(BacnetReadPropertyMultipleCodec.encode(request),
                timeoutMs,
                segmentTimeoutMs,
                retries,
                frame -> BacnetReadPropertyMultipleResponseDecoder.decode(frame, request.getInvokeId()));
    }

    /**
     * 写入或持久化业务数据。
     */
    public void writeProperty(BacnetWritePropertyRequest request, long timeoutMs, int retries) throws Exception {
        exchange(BacnetWritePropertyCodec.encode(request), timeoutMs, timeoutMs, retries, frame -> {
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
        exchange(BacnetWritePropertyMultipleCodec.encode(request), timeoutMs, timeoutMs, retries, frame -> {
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
        exchange(BacnetSubscribeCovCodec.encode(request), timeoutMs, timeoutMs, retries, frame -> {
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
        exchange(BacnetSubscribeCovPropertyCodec.encode(request), timeoutMs, timeoutMs, retries, frame -> {
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
        connectionAdapter.send(BacnetConfirmedCovNotificationCodec.encodeAck(invokeId));
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
        running.set(false);
        receiverThread.interrupt();
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> T exchange(byte[] requestFrame,
                           long timeoutMs,
                           long segmentTimeoutMs,
                           int retries,
                           FrameDecoder<T> decoder) throws Exception {
        return requestSession.execute(new BacnetRequestSession.RequestExchange<>() {
            /**
             * 执行当前业务逻辑。
             */
            @Override
            public void beforeAttempt() {
                responseQueue.clear();
            }

            /**
             * 执行当前业务逻辑。
             */
            @Override
            public void sendRequest() throws Exception {
                connectionAdapter.send(requestFrame);
            }

            /**
             * 执行当前业务逻辑。
             */
            @Override
            public byte[] pollResponse(long timeout, TimeUnit unit, int resolvedSegmentTimeoutMs) throws Exception {
                byte[] response = responseQueue.poll(timeout, unit);
                if (response == null) {
                    return null;
                }
                if (!clientSupport.isSegmentedComplexAck(response)) {
                    return response;
                }
                clientSupport.recordSegmentedResponse();
                return segmentAssembler.collect(response,
                        resolvedSegmentTimeoutMs,
                        (segmentPollTimeout, pollUnit) -> responseQueue.poll(segmentPollTimeout, pollUnit),
                        (invokeId, sequenceNumber, proposedWindowSize) ->
                                connectionAdapter.send(BacnetSegmentSupport.encodeSegmentAck(
                                        invokeId, sequenceNumber, proposedWindowSize)));
            }

            /**
             * 解析或转换业务数据。
             */
            @Override
            public T decode(byte[] response) throws Exception {
                return decoder.decode(response);
            }

            /**
             * 执行当前业务逻辑。
             */
            @Override
            public String timeoutMessage(int resolvedTimeoutMs) {
                return "BACnet/SC 接收超时，耗时毫秒=" + resolvedTimeoutMs;
            }
        }, timeoutMs, segmentTimeoutMs, retries, "BACnet/SC 请求设备=" + connectionAdapter.getDeviceId());
    }

    /**
     * 执行当前业务逻辑。
     */
    private void receiveLoop() {
        while (running.get()) {
            try {
                byte[] data = connectionAdapter.receive(RECEIVE_POLL_TIMEOUT_MS);
                if (data == null || data.length == 0) {
                    continue;
                }
                dispatchIncoming(data);
            } catch (Exception ex) {
                if (running.get() && connectionAdapter.isConnected()) {
                    log.warn("BACnet/SC 接收循环 异常停止, 设备={}",
                            connectionAdapter.getDeviceId(), ex);
                }
            }
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void dispatchIncoming(byte[] frame) {
        if (BacnetCovNotificationDecoder.isUnconfirmedCovNotification(frame)
                || BacnetCovNotificationDecoder.isConfirmedCovNotification(frame)) {
            handleCovNotification(frame);
            return;
        }
        if (isOtherUnconfirmedRequest(frame)) {
            return;
        }
        responseQueue.offer(frame);
    }

    /**
     * 处理当前业务流程。
     */
    private void handleCovNotification(byte[] frame) {
        clientSupport.handleCovNotification(
                frame,
                "设备=" + connectionAdapter.getDeviceId(),
                invokeId -> {
                    try {
                        acknowledgeConfirmedCovNotification(invokeId);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                },
                covNotificationHandler);
    }

    private boolean isOtherUnconfirmedRequest(byte[] frame) {
        if (frame == null || frame.length < 8) {
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
            int bvlcType = Byte.toUnsignedInt(buffer.get());
            int function = Byte.toUnsignedInt(buffer.get());
            if (bvlcType != BacnetReadPropertyCodec.BVLC_TYPE_IP
                    || function != BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU) {
                return false;
            }
            int declaredLength = Short.toUnsignedInt(buffer.getShort());
            if (declaredLength != frame.length) {
                return false;
            }
            if (Byte.toUnsignedInt(buffer.get()) != BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION) {
                return false;
            }
            buffer.get();
            int pduHeader = Byte.toUnsignedInt(buffer.get());
            int pduType = (pduHeader >> 4) & 0x0F;
            return pduType == BacnetReadPropertyCodec.APDU_TYPE_UNCONFIRMED_REQUEST;
        } catch (Exception ex) {
            return false;
        }
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
