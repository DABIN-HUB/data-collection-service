package com.wangbin.collector.core.collector.protocol.bacnet.service;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetCovNotificationDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSegmentSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class BacnetClientSupport {

    private final AtomicLong invokeIdMismatchCount;
    private final AtomicLong covNotificationCount;
    private final AtomicLong segmentedResponseCount;

    /**
     * 创建当前组件实例。
     */
    public BacnetClientSupport(AtomicLong invokeIdMismatchCount,
                               AtomicLong covNotificationCount,
                               AtomicLong segmentedResponseCount) {
        this.invokeIdMismatchCount = invokeIdMismatchCount;
        this.covNotificationCount = covNotificationCount;
        this.segmentedResponseCount = segmentedResponseCount;
    }

    public boolean isInvokeIdMismatch(Exception ex) {
        String message = ex.getMessage();
        return message != null && message.contains("invokeId mismatch");
    }

    /**
     * 记录或统计业务状态。
     */
    public void recordInvokeIdMismatch() {
        invokeIdMismatchCount.incrementAndGet();
    }

    /**
     * 记录或统计业务状态。
     */
    public void recordSegmentedResponse() {
        segmentedResponseCount.incrementAndGet();
    }

    public boolean isSegmentedComplexAck(byte[] frame) {
        return BacnetSegmentSupport.isSegmentedComplexAck(frame);
    }

    /**
     * 处理当前业务流程。
     */
    public <T> T handleCovNotification(byte[] frame,
                                       String endpointLabel,
                                       Consumer<Integer> confirmedAckSender,
                                       Consumer<BacnetCovNotification> handler) {
        try {
            BacnetCovNotification notification = BacnetCovNotificationDecoder.decode(frame);
            if (notification.isConfirmed() && notification.getInvokeId() != null) {
                confirmedAckSender.accept(notification.getInvokeId());
            }
            covNotificationCount.incrementAndGet();
            if (handler != null) {
                handler.accept(notification);
            }
            return null;
        } catch (Exception ex) {
            log.warn("解码 BACnet COV 通知 失败, end点位={}", endpointLabel, ex);
            return null;
        }
    }
}
