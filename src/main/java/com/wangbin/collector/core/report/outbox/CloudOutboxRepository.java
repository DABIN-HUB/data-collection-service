package com.wangbin.collector.core.report.outbox;

import java.util.List;
import java.util.Optional;

/**
 * 云端上报发件箱仓储。
 */
public interface CloudOutboxRepository {

    CloudOutboxMessage saveIfAbsent(CloudOutboxMessage message, long leaseUntil);

    Optional<CloudOutboxMessage> find(String messageId);

    List<CloudOutboxMessage> claimDue(long now, int limit, long leaseUntil);

    void reschedule(CloudOutboxMessage message);

    void complete(String messageId);

    long countPending();

    long countIsolated();

    long oldestCreatedAt();

    boolean hasPendingForDevice(String localDeviceId);
}
