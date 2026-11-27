package com.wangbin.collector.core.report.outbox;

import com.wangbin.collector.core.cloud.config.CloudAckCommitMode;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.inbound.MqttAckReply;
import com.wangbin.collector.core.report.inbound.MqttAckReplyObserver;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一处理发布结果和平台业务确认，保证消息提交幂等。
 */
@Slf4j
@Component
public class CloudOutboxCoordinator implements MqttAckReplyObserver {

    private final CloudOutboxRepository repository;
    private final ShadowManager shadowManager;
    private final ReportProperties reportProperties;
    @Nullable
    private final CloudReportResultRecorder resultRecorder;

    /**
     * 创建当前组件实例。
     */
    @Autowired
    public CloudOutboxCoordinator(CloudOutboxRepository repository,
                                  ShadowManager shadowManager,
                                  ReportProperties reportProperties,
                                  @Nullable CloudReportResultRecorder resultRecorder) {
        this.repository = repository;
        this.shadowManager = shadowManager;
        this.reportProperties = reportProperties;
        this.resultRecorder = resultRecorder;
    }

    /**
     * 保留无结果存储时的构造方式，兼容仅加载云模块的调用方。
     */
    CloudOutboxCoordinator(CloudOutboxRepository repository,
                           ShadowManager shadowManager,
                           ReportProperties reportProperties) {
        this(repository, shadowManager, reportProperties, null);
    }

    /**
     * 处理发布结果，迟到回调不得复活已经被 ACK 完成的消息。
     */
    public void handlePublishResult(String messageId, ReportResult result, Throwable throwable) {
        repository.find(messageId).ifPresent(message -> {
            if (throwable == null && result != null && result.isSuccess()) {
                if (!isPublishResultApplicable(message)) {
                    log.debug("忽略非发布窗口内的云端上报成功结果，消息={}，状态={}",
                            messageId, message.getStatus());
                    return;
                }
                message.setPublishedAt(System.currentTimeMillis());
                if (isAwaitingAck(result)) {
                    long timeout = metadataLong(result.getMetadata(),
                            CloudOutboxMetadataKeys.ACK_TIMEOUT_MS,
                            reportProperties.getCloud().getAck().getTimeoutMs());
                    message.setStatus(CloudOutboxStatus.WAITING_ACK);
                    message.setNextAttemptAt(System.currentTimeMillis() + Math.max(1000L, timeout));
                    message.setLastError(null);
                    repository.rescheduleIfPresent(message);
                } else {
                    complete(message, CloudReportResultStatus.PUBLISHED);
                }
                return;
            }
            String error = throwable != null ? throwable.getMessage()
                    : result != null ? result.getErrorMessage() : "上报未返回结果";
            rescheduleFailure(message, error);
        });
    }

    /**
     * 记录或统计业务状态。
     */
    public void markWaitingConfig(CloudOutboxMessage message) {
        message.setStatus(CloudOutboxStatus.WAITING_CONFIG);
        message.setLastError("上报配置暂不可用");
        message.setNextAttemptAt(System.currentTimeMillis()
                + Math.max(1000L, reportProperties.getOutbox().getWaitingConfigRetryMs()));
        repository.reschedule(message);
    }

    /**
     * 在调用真实发送前持久化发布窗口，允许合法快速 ACK 匹配当前消息。
     */
    public boolean markPublishing(String messageId, long leaseUntil) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        return repository.find(messageId)
                .filter(message -> message.getStatus() != CloudOutboxStatus.ISOLATED)
                .map(message -> {
                    message.setStatus(CloudOutboxStatus.PUBLISHING);
                    message.setNextAttemptAt(leaseUntil);
                    message.setLastError(null);
                    return repository.rescheduleIfPresent(message);
                })
                .orElse(false);
    }

    public boolean isAwaitingAck(ReportResult result) {
        if (result == null || result.getMetadata() == null) {
            return false;
        }
        boolean pending = metadataBoolean(result.getMetadata(), CloudOutboxMetadataKeys.ACK_PENDING);
        Object commitMode = result.getMetadata().get(CloudOutboxMetadataKeys.ACK_COMMIT_ON);
        return pending && commitMode != null
                && CloudAckCommitMode.ACK_SUCCESS.name().equalsIgnoreCase(String.valueOf(commitMode));
    }

    /**
     * 处理平台 ACK，只有已进入真实发布窗口或等待 ACK 的消息才能被完成。
     */
    @Override
    public void onAck(MqttAckReply ackReply) {
        if (ackReply == null || ackReply.messageId() == null) {
            return;
        }
        repository.find(ackReply.messageId()).ifPresent(message -> {
            if (message.getStatus() != CloudOutboxStatus.PUBLISHING
                    && message.getStatus() != CloudOutboxStatus.WAITING_ACK) {
                log.debug("忽略非等待 ACK 状态的云端确认，消息={}，状态={}",
                        ackReply.messageId(), message.getStatus());
                return;
            }
            if (ackReply.code() == 0) {
                long ackAt = System.currentTimeMillis();
                // 快速业务 ACK 可能先于异步发布回调；此时以确认时间近似记录发布时间。
                if (message.getPublishedAt() <= 0) {
                    message.setPublishedAt(ackAt);
                }
                message.setAckReceivedAt(ackAt);
                complete(message, CloudReportResultStatus.ACK_CONFIRMED);
            } else {
                rescheduleFailure(message, "平台业务确认失败: " + ackReply.message());
            }
        });
    }

    /**
     * 执行当前业务逻辑。
     */
    private void complete(CloudOutboxMessage message, CloudReportResultStatus resultStatus) {
        long completedAt = System.currentTimeMillis();
        for (CloudOutboxMessage.CloudOutboxCommit commit : message.resolveCommits()) {
            shadowManager.markOutboxReported(
                    commit.getLocalDeviceId(),
                    commit.getProperties(),
                    commit.getWindowStart(),
                    commit.getWindowEnd(),
                    false);
        }
        repository.complete(message.getMessageId());
        for (CloudOutboxMessage.CloudOutboxCommit commit : message.resolveCommits()) {
            if (!repository.hasPendingForDevice(commit.getLocalDeviceId())) {
                shadowManager.markOutboxReported(
                        commit.getLocalDeviceId(),
                        Map.of(),
                        commit.getWindowStart(),
                        commit.getWindowEnd(),
                        true);
            }
        }
        recordResult(message, resultStatus, completedAt);
    }

    /**
     * 执行当前业务逻辑。
     */
    private void rescheduleFailure(CloudOutboxMessage message, String error) {
        int attempts = message.getAttempts() + 1;
        message.setAttempts(attempts);
        message.setLastError(error);
        if (attempts >= Math.max(1, reportProperties.getOutbox().getMaxRetryTimes())) {
            message.setStatus(CloudOutboxStatus.ISOLATED);
            repository.rescheduleIfPresent(message);
            log.error("云端上报消息已进入隔离区，消息={}，设备={}，尝试次数={}",
                    message.getMessageId(), message.getLocalDeviceId(), attempts);
            recordResult(message, CloudReportResultStatus.FAILED, System.currentTimeMillis());
            return;
        }
        message.setStatus(CloudOutboxStatus.PENDING);
        message.setNextAttemptAt(System.currentTimeMillis() + retryDelay(attempts));
        repository.rescheduleIfPresent(message);
    }

    /**
     * 执行当前业务逻辑。
     */
    private long retryDelay(int attempts) {
        long base = Math.max(100L, reportProperties.getRetryBackoffMs());
        long max = Math.max(base, reportProperties.getMaxRetryBackoffMs());
        return Math.min(max, base * (1L << Math.min(Math.max(0, attempts - 1), 10)));
    }

    /**
     * 向可选的结果存储端口提交终态元数据；记录失败不得影响既有提交状态机。
     */
    private void recordResult(CloudOutboxMessage message,
                              CloudReportResultStatus status,
                              long completedAt) {
        if (resultRecorder == null || message == null || message.getMessageId() == null) {
            return;
        }
        try {
            Set<String> deviceIds = new LinkedHashSet<>();
            if (message.getLocalDeviceId() != null && !message.getLocalDeviceId().isBlank()) {
                deviceIds.add(message.getLocalDeviceId());
            }
            for (CloudOutboxMessage.CloudOutboxCommit commit : message.resolveCommits()) {
                if (commit != null && commit.getLocalDeviceId() != null && !commit.getLocalDeviceId().isBlank()) {
                    deviceIds.add(commit.getLocalDeviceId());
                }
            }
            resultRecorder.record(new CloudReportResult(
                    message.getMessageId(),
                    List.copyOf(deviceIds),
                    message.getDeviceName(),
                    message.getGatewayDeviceId(),
                    status,
                    message.getPublishedAt(),
                    message.getAckReceivedAt(),
                    message.getCreatedAt(),
                    completedAt,
                    message.getAttempts(),
                    status == CloudReportResultStatus.FAILED ? "已达到重试上限" : null));
        } catch (RuntimeException exception) {
            log.warn("云上报终态记录失败，消息={}，异常类型={}，记录缺口不影响已完成的上报状态",
                    message.getMessageId(), exception.getClass().getSimpleName());
        }
    }

    private boolean isPublishResultApplicable(CloudOutboxMessage message) {
        return message.getStatus() == CloudOutboxStatus.PUBLISHING
                || message.getStatus() == CloudOutboxStatus.WAITING_ACK;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean metadataBoolean(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 执行当前业务逻辑。
     */
    private long metadataLong(Map<String, Object> metadata, String key, long defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }
}
