package com.wangbin.collector.core.report.outbox;

import com.wangbin.collector.core.cloud.config.CloudAckCommitMode;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.inbound.MqttAckReply;
import com.wangbin.collector.core.report.inbound.MqttAckReplyObserver;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 统一处理发布结果和平台业务确认，保证消息提交幂等。
 */
@Slf4j
@Component
public class CloudOutboxCoordinator implements MqttAckReplyObserver {

    private final CloudOutboxRepository repository;
    private final ShadowManager shadowManager;
    private final ReportProperties reportProperties;

    public CloudOutboxCoordinator(CloudOutboxRepository repository,
                                  ShadowManager shadowManager,
                                  ReportProperties reportProperties) {
        this.repository = repository;
        this.shadowManager = shadowManager;
        this.reportProperties = reportProperties;
    }

    public void handlePublishResult(String messageId, ReportResult result, Throwable throwable) {
        repository.find(messageId).ifPresent(message -> {
            if (throwable == null && result != null && result.isSuccess()) {
                if (isAwaitingAck(result)) {
                    long timeout = metadataLong(result.getMetadata(),
                            CloudOutboxMetadataKeys.ACK_TIMEOUT_MS,
                            reportProperties.getCloud().getAck().getTimeoutMs());
                    message.setStatus(CloudOutboxStatus.WAITING_ACK);
                    message.setNextAttemptAt(System.currentTimeMillis() + Math.max(1000L, timeout));
                    message.setLastError(null);
                    repository.reschedule(message);
                } else {
                    complete(message);
                }
                return;
            }
            String error = throwable != null ? throwable.getMessage()
                    : result != null ? result.getErrorMessage() : "上报未返回结果";
            rescheduleFailure(message, error);
        });
    }

    public void markWaitingConfig(CloudOutboxMessage message) {
        message.setStatus(CloudOutboxStatus.WAITING_CONFIG);
        message.setLastError("上报配置暂不可用");
        message.setNextAttemptAt(System.currentTimeMillis()
                + Math.max(1000L, reportProperties.getOutbox().getWaitingConfigRetryMs()));
        repository.reschedule(message);
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

    @Override
    public void onAck(MqttAckReply ackReply) {
        if (ackReply == null || ackReply.messageId() == null) {
            return;
        }
        repository.find(ackReply.messageId()).ifPresent(message -> {
            if (ackReply.code() == 0) {
                complete(message);
            } else {
                rescheduleFailure(message, "平台业务确认失败: " + ackReply.message());
            }
        });
    }

    private void complete(CloudOutboxMessage message) {
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
    }

    private void rescheduleFailure(CloudOutboxMessage message, String error) {
        int attempts = message.getAttempts() + 1;
        message.setAttempts(attempts);
        message.setLastError(error);
        if (attempts >= Math.max(1, reportProperties.getOutbox().getMaxRetryTimes())) {
            message.setStatus(CloudOutboxStatus.ISOLATED);
            repository.reschedule(message);
            log.error("云端上报消息已进入隔离区，messageId={}, deviceId={}, attempts={}",
                    message.getMessageId(), message.getLocalDeviceId(), attempts);
            return;
        }
        message.setStatus(CloudOutboxStatus.PENDING);
        message.setNextAttemptAt(System.currentTimeMillis() + retryDelay(attempts));
        repository.reschedule(message);
    }

    private long retryDelay(int attempts) {
        long base = Math.max(100L, reportProperties.getRetryBackoffMs());
        long max = Math.max(base, reportProperties.getMaxRetryBackoffMs());
        return Math.min(max, base * (1L << Math.min(Math.max(0, attempts - 1), 10)));
    }

    private boolean metadataBoolean(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

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
