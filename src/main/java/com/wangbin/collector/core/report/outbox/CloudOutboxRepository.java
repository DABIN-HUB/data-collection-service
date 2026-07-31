package com.wangbin.collector.core.report.outbox;

import java.util.List;
import java.util.Optional;

/**
 * 云端上报发件箱仓储。
 */
public interface CloudOutboxRepository {

    /**
     * 写入或持久化业务数据。
     */
    CloudOutboxMessage saveIfAbsent(CloudOutboxMessage message, long leaseUntil);

    /**
     * 查询并返回业务数据。
     */
    Optional<CloudOutboxMessage> find(String messageId);

    /**
     * 执行当前业务逻辑。
     */
    List<CloudOutboxMessage> claimDue(long now, int limit, long leaseUntil);

    /**
     * 执行当前业务逻辑。
     */
    void reschedule(CloudOutboxMessage message);

    /**
     * 执行当前业务逻辑。
     */
    void complete(String messageId);

    /**
     * 记录或统计业务状态。
     */
    long countPending();

    /**
     * 记录或统计业务状态。
     */
    long countIsolated();

    /**
     * 执行当前业务逻辑。
     */
    long oldestCreatedAt();

    /**
     * 执行当前业务逻辑。
     */
    boolean hasPendingForDevice(String localDeviceId);
}
