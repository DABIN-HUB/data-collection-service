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
     * 仅当消息存在且仍在隔离集合中时，原子地将其恢复为待调度状态。
     *
     * @param message 已清除错误并设置为到期待发送状态的消息
     * @return 隔离状态未发生变化且恢复成功时返回 true
     */
    default boolean replayIsolated(CloudOutboxMessage message) {
        return false;
    }

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
    default List<CloudOutboxMessage> list(CloudOutboxStatus status, String localDeviceId, int limit) {
        return List.of();
    }

    boolean hasPendingForDevice(String localDeviceId);
}
