package com.wangbin.collector.core.collector.protocol.bacnet.transport;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrame;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrameCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrameType;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 管理当前模块的生命周期和状态。
 */
@Slf4j
public class BacnetMstpTokenManager implements AutoCloseable {

    private static final int DEFAULT_READ_POLL_TIMEOUT_MS = 250;

    private final BacnetSerialChannel channel;
    private final int localMacAddress;
    private final int maxMaster;
    private final Integer configuredNextStation;
    private final int tokenClaimTimeoutMs;
    private final int pollForMasterTimeoutMs;

    private final ReentrantLock tokenLock = new ReentrantLock();
    private final Condition tokenAvailable = tokenLock.newCondition();
    private final ReentrantLock conversationLock = new ReentrantLock();
    private final Object sendLock = new Object();
    private final BlockingQueue<BacnetMstpFrame> conversationFrames = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> masterReplies = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean hasToken = new AtomicBoolean(false);
    private final AtomicLong tokenReceiveCount = new AtomicLong(0);
    private final AtomicLong tokenPassCount = new AtomicLong(0);
    private final AtomicLong pollForMasterCount = new AtomicLong(0);
    private final AtomicLong replyToPollCount = new AtomicLong(0);
    private final AtomicLong frameErrorCount = new AtomicLong(0);
    private final AtomicLong crcErrorCount = new AtomicLong(0);

    private volatile Thread receiveThread;
    private volatile long lastFrameReceivedAt = System.currentTimeMillis();
    private volatile Integer conversationDestination;
    private volatile Integer discoveredNextStation;
    private volatile Consumer<BacnetMstpFrame> incomingFrameHandler;

    /**
     * 创建当前组件实例。
     */
    public BacnetMstpTokenManager(BacnetSerialChannel channel,
                                  DeviceConnection config,
                                  Integer preferredNextStation) {
        this.channel = channel;
        this.localMacAddress = resolveMacAddress(firstNonNull(
                config.getIntConfig("localMacAddress", null),
                config.getIntConfig("macAddress", null)),
                "localMacAddress");
        this.maxMaster = resolveMaxMaster(config);
        this.configuredNextStation = sanitizeMasterAddress(firstNonNull(
                config.getIntConfig("nextStationMac", null),
                config.getIntConfig("nextMasterAddress", null),
                preferredNextStation));
        this.tokenClaimTimeoutMs = resolvePositive(config.getIntConfig("tokenClaimTimeoutMs", null), 1000);
        this.pollForMasterTimeoutMs = resolvePositive(config.getIntConfig("pollForMasterTimeoutMs", null), 250);
    }

    /**
     * 处理组件生命周期。
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            receiveThread = new Thread(this::receiveLoop, "bacnet-mstp-recv-" + localMacAddress);
            receiveThread.setDaemon(true);
            receiveThread.start();
        }
    }

    public void setIncomingFrameHandler(Consumer<BacnetMstpFrame> handler) {
        this.incomingFrameHandler = handler;
    }

    /**
     * 执行当前业务逻辑。
     */
    public void beginConfirmedRequest(int destinationMac, byte[] npdu, long timeoutMs) throws Exception {
        conversationLock.lockInterruptibly();
        boolean success = false;
        try {
            acquireToken(timeoutMs);
            conversationFrames.clear();
            conversationDestination = destinationMac;
            sendFrame(BacnetMstpFrameType.BACNET_DATA_EXPECTING_REPLY, destinationMac, npdu);
            success = true;
        } finally {
            if (!success) {
                conversationDestination = null;
                conversationFrames.clear();
                safeReleaseTokenAfterFailure();
                conversationLock.unlock();
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public BacnetMstpFrame awaitConversationFrame(long timeoutMs) throws Exception {
        BacnetMstpFrame frame = conversationFrames.poll(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        if (frame == null) {
            throw new SocketTimeoutException("BACnet MS/TP reply timed out after " + timeoutMs + "ms");
        }
        return frame;
    }

    /**
     * 执行当前业务逻辑。
     */
    public void sendConversationFrame(int destinationMac, byte[] npdu, boolean expectingReply) throws Exception {
        ensureConversationOpen();
        sendFrame(expectingReply
                        ? BacnetMstpFrameType.BACNET_DATA_EXPECTING_REPLY
                        : BacnetMstpFrameType.BACNET_DATA_NOT_EXPECTING_REPLY,
                destinationMac,
                npdu);
    }

    /**
     * 执行当前业务逻辑。
     */
    public void sendUnconfirmed(int destinationMac, byte[] npdu, long timeoutMs) throws Exception {
        conversationLock.lockInterruptibly();
        try {
            acquireToken(timeoutMs);
            sendFrame(BacnetMstpFrameType.BACNET_DATA_NOT_EXPECTING_REPLY, destinationMac, npdu);
            releaseToken();
        } finally {
            conversationLock.unlock();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public void finishConversation() throws Exception {
        try {
            releaseToken();
        } finally {
            conversationDestination = null;
            conversationFrames.clear();
            if (conversationLock.isHeldByCurrentThread()) {
                conversationLock.unlock();
            }
        }
    }

    public int getLocalMacAddress() { return localMacAddress; }
    public long getTokenReceiveCount() { return tokenReceiveCount.get(); }
    public long getTokenPassCount() { return tokenPassCount.get(); }
    public long getPollForMasterCount() { return pollForMasterCount.get(); }
    public long getReplyToPollCount() { return replyToPollCount.get(); }
    public long getFrameErrorCount() { return frameErrorCount.get(); }
    public long getCrcErrorCount() { return crcErrorCount.get(); }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void close() {
        running.set(false);
        Thread thread = receiveThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void receiveLoop() {
        while (running.get()) {
            try {
                BacnetMstpFrame frame = BacnetMstpFrameCodec.read(channel, DEFAULT_READ_POLL_TIMEOUT_MS);
                if (frame == null) {
                    continue;
                }
                lastFrameReceivedAt = System.currentTimeMillis();
                handleFrame(frame);
            } catch (BacnetMstpFrameCodec.CrcException ex) {
                crcErrorCount.incrementAndGet();
                log.debug("BACnet MS/TP CRC 校验 失败, MAC={}", localMacAddress, ex);
            } catch (Exception ex) {
                frameErrorCount.incrementAndGet();
                if (running.get()) {
                    log.warn("BACnet MS/TP 接收循环 失败, MAC={}", localMacAddress, ex);
                }
            }
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void handleFrame(BacnetMstpFrame frame) throws Exception {
        switch (frame.frameType()) {
            case TOKEN -> handleToken(frame);
            case POLL_FOR_MASTER -> handlePollForMaster(frame);
            case REPLY_TO_POLL_FOR_MASTER -> handleReplyToPollForMaster(frame);
            case TEST_REQUEST -> handleTestRequest(frame);
            case BACNET_DATA_EXPECTING_REPLY, BACNET_DATA_NOT_EXPECTING_REPLY, REPLY_POSTPONED -> handleDataFrame(frame);
            case TEST_RESPONSE -> {
            }
            default -> {
            }
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void handleToken(BacnetMstpFrame frame) {
        if (frame.destinationAddress() != localMacAddress) {
            return;
        }
        tokenReceiveCount.incrementAndGet();
        tokenLock.lock();
        try {
            hasToken.set(true);
            tokenAvailable.signalAll();
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void handlePollForMaster(BacnetMstpFrame frame) throws Exception {
        if (frame.destinationAddress() != localMacAddress) {
            return;
        }
        replyToPollCount.incrementAndGet();
        sendFrame(BacnetMstpFrameType.REPLY_TO_POLL_FOR_MASTER, frame.sourceAddress(), new byte[0]);
    }

    /**
     * 处理当前业务流程。
     */
    private void handleReplyToPollForMaster(BacnetMstpFrame frame) {
        masterReplies.offer(frame.sourceAddress());
        discoveredNextStation = sanitizeMasterAddress(frame.sourceAddress());
    }

    /**
     * 处理当前业务流程。
     */
    private void handleTestRequest(BacnetMstpFrame frame) throws Exception {
        if (frame.destinationAddress() != localMacAddress) {
            return;
        }
        sendFrame(BacnetMstpFrameType.TEST_RESPONSE, frame.sourceAddress(), frame.data());
    }

    /**
     * 处理当前业务流程。
     */
    private void handleDataFrame(BacnetMstpFrame frame) {
        if (frame.destinationAddress() != localMacAddress
                && frame.destinationAddress() != BacnetMstpFrame.BROADCAST_ADDRESS) {
            return;
        }
        Integer activeDestination = conversationDestination;
        if (activeDestination != null && frame.sourceAddress() == activeDestination) {
            conversationFrames.offer(frame);
            return;
        }
        Consumer<BacnetMstpFrame> handler = incomingFrameHandler;
        if (handler != null) {
            handler.accept(frame);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void acquireToken(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        tokenLock.lock();
        try {
            while (!hasToken.get()) {
                if (System.currentTimeMillis() - lastFrameReceivedAt >= tokenClaimTimeoutMs) {
                    hasToken.set(true);
                    log.debug("BACnet MS/TP 空闲后获取令牌，MAC={}", localMacAddress);
                    return;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new SocketTimeoutException("BACnet MS/TP token acquire timed out after " + timeoutMs + "ms");
                }
                tokenAvailable.await(Math.min(remaining, 100L), TimeUnit.MILLISECONDS);
            }
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void releaseToken() throws Exception {
        if (!hasToken.get()) {
            return;
        }
        Integer nextStation = resolveNextStation();
        if (nextStation == null || nextStation == localMacAddress) {
            return;
        }
        sendFrame(BacnetMstpFrameType.TOKEN, nextStation, new byte[0]);
        hasToken.set(false);
        tokenPassCount.incrementAndGet();
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolveNextStation() throws Exception {
        if (configuredNextStation != null && configuredNextStation != localMacAddress) {
            return configuredNextStation;
        }
        if (discoveredNextStation != null && discoveredNextStation != localMacAddress) {
            return discoveredNextStation;
        }
        Integer discovered = discoverNextMaster();
        if (discovered != null && discovered != localMacAddress) {
            discoveredNextStation = discovered;
            return discovered;
        }
        return localMacAddress;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Integer discoverNextMaster() throws Exception {
        masterReplies.clear();
        Integer discovered = pollRange(localMacAddress + 1, maxMaster);
        if (discovered != null) {
            return discovered;
        }
        if (localMacAddress > 0) {
            return pollRange(0, localMacAddress - 1);
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Integer pollRange(int start, int end) throws Exception {
        for (int candidate = start; candidate <= end; candidate++) {
            int sanitized = sanitizeMasterAddress(candidate);
            if (sanitized == localMacAddress) {
                continue;
            }
            sendFrame(BacnetMstpFrameType.POLL_FOR_MASTER, sanitized, new byte[0]);
            pollForMasterCount.incrementAndGet();
            Integer reply = masterReplies.poll(pollForMasterTimeoutMs, TimeUnit.MILLISECONDS);
            if (reply != null && reply == sanitized) {
                return reply;
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private void sendFrame(BacnetMstpFrameType frameType, int destinationAddress, byte[] payload) throws Exception {
        byte[] encoded = BacnetMstpFrameCodec.encode(new BacnetMstpFrame(frameType, destinationAddress, localMacAddress, payload));
        synchronized (sendLock) {
            channel.write(encoded);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void safeReleaseTokenAfterFailure() {
        try {
            releaseToken();
        } catch (Exception ex) {
            log.debug("释放 BACnet MS/TP 令牌失败后跳过，MAC={}", localMacAddress, ex);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureConversationOpen() {
        if (!conversationLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("BACnet MS/TP conversation is not owned by the current thread");
        }
        if (conversationDestination == null) {
            throw new IllegalStateException("BACnet MS/TP conversation destination is not set");
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveMacAddress(Integer value, String fieldName) {
        if (value == null || value < 0 || value > 0xFE) {
            throw new IllegalStateException("BACnet MS/TP " + fieldName + " must be between 0 and 254");
        }
        return value;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveMaxMaster(DeviceConnection config) {
        Integer max = config.getIntConfig("maxMaster", null);
        if (max == null) {
            return 127;
        }
        if (max < 0 || max > 127) {
            throw new IllegalStateException("BACnet MS/TP maxMaster must be between 0 and 127");
        }
        return max;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolvePositive(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Integer sanitizeMasterAddress(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 0 || value > 127) {
            throw new IllegalStateException("BACnet MS/TP master address must be between 0 and 127");
        }
        return value;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}