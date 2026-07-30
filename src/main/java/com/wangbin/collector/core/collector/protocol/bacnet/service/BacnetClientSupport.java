package com.wangbin.collector.core.collector.protocol.bacnet.service;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetCovNotificationDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSegmentSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
public class BacnetClientSupport {

    private final AtomicLong invokeIdMismatchCount;
    private final AtomicLong covNotificationCount;
    private final AtomicLong segmentedResponseCount;

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

    public void recordInvokeIdMismatch() {
        invokeIdMismatchCount.incrementAndGet();
    }

    public void recordSegmentedResponse() {
        segmentedResponseCount.incrementAndGet();
    }

    public boolean isSegmentedComplexAck(byte[] frame) {
        return BacnetSegmentSupport.isSegmentedComplexAck(frame);
    }

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
            log.warn("Decode BACnet COV notification failed, endpoint={}", endpointLabel, ex);
            return null;
        }
    }
}
