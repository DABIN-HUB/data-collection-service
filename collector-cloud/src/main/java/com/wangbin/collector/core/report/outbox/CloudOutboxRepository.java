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
     * 按下一次可调度时间重新写回消息。
     */
    void reschedule(CloudOutboxMessage message);

    /**
     * 仅当消息仍存在时重新写回，避免 ACK 已完成后的迟到发布回调复活消息。
     *
     * @param message 待更新的发件箱消息
     * @return 消息仍存在并完成写回时返回 true
     */
    boolean rescheduleIfPresent(CloudOutboxMessage message);

    /**
     * 完成并移除消息。
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
