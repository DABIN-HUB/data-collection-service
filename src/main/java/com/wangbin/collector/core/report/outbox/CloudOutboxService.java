package com.wangbin.collector.core.report.outbox;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.service.ReportManager;
import com.wangbin.collector.core.report.service.support.ReportConfigProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 云端上报持久化发件箱服务。
 */
@Slf4j
@Service
public class CloudOutboxService {

    private final CloudOutboxRepository repository;
    private final CloudOutboxCoordinator coordinator;
    private final ReportManager reportManager;
    private final ReportConfigProvider reportConfigProvider;
    private final ReportProperties reportProperties;

    /**
     * 创建当前组件实例。
     */
    public CloudOutboxService(CloudOutboxRepository repository,
                              CloudOutboxCoordinator coordinator,
                              ReportManager reportManager,
                              ReportConfigProvider reportConfigProvider,
                              ReportProperties reportProperties) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.reportManager = reportManager;
        this.reportConfigProvider = reportConfigProvider;
        this.reportProperties = reportProperties;
    }

    /**
     * 执行当前业务逻辑。
     */
    public String stage(String localDeviceId,
                        long shadowVersion,
                        long windowStart,
                        long windowEnd,
                        ReportData data) {
        if (!enabled()) {
            return null;
        }
        validate(localDeviceId, data);
        String messageId = ensureMessageId(data);
        long now = System.currentTimeMillis();
        Map<String, Object> metadata = data.getMetadata();
        CloudOutboxMessage message = new CloudOutboxMessage(
                messageId,
                localDeviceId,
                String.valueOf(metadata.get(CloudOutboxMetadataKeys.PRODUCT_KEY)),
                data.getDeviceId(),
                String.valueOf(metadata.get(CloudOutboxMetadataKeys.GATEWAY_DEVICE_ID)),
                shadowVersion,
                windowStart,
                windowEnd,
                now,
                now + leaseMs(),
                0,
                CloudOutboxStatus.PENDING,
                null,
                CloudOutboxMessage.ReportDataSnapshot.from(data),
                null);
        return repository.saveIfAbsent(message, now + leaseMs()).getMessageId();
    }

    /**
     * 执行当前业务逻辑。
     */
    public String stageBatch(List<CloudOutboxMessage.CloudOutboxCommit> commits,
                             ReportData data) {
        if (!enabled()) {
            return null;
        }
        if (commits == null || commits.size() < 2 || data == null) {
            throw new IllegalArgumentException("聚合发件箱消息至少需要两个设备提交项");
        }
        for (CloudOutboxMessage.CloudOutboxCommit commit : commits) {
            if (commit == null || commit.getLocalDeviceId() == null
                    || commit.getLocalDeviceId().isBlank()) {
                throw new IllegalArgumentException("聚合发件箱提交项缺少本地设备ID");
            }
        }
        Object productKey = data.getMetadata().get(CloudOutboxMetadataKeys.PRODUCT_KEY);
        Object gatewayDeviceId = data.getMetadata().get(CloudOutboxMetadataKeys.GATEWAY_DEVICE_ID);
        if (productKey == null || String.valueOf(productKey).isBlank()
                || gatewayDeviceId == null || String.valueOf(gatewayDeviceId).isBlank()) {
            throw new IllegalArgumentException("聚合发件箱消息缺少网关云身份");
        }
        String messageId = ensureMessageId(data);
        long now = System.currentTimeMillis();
        CloudOutboxMessage message = new CloudOutboxMessage(
                messageId,
                commits.get(0).getLocalDeviceId(),
                String.valueOf(productKey),
                data.getDeviceId(),
                String.valueOf(gatewayDeviceId),
                0L,
                0L,
                0L,
                now,
                now + leaseMs(),
                0,
                CloudOutboxStatus.PENDING,
                null,
                CloudOutboxMessage.ReportDataSnapshot.from(data),
                List.copyOf(commits));
        return repository.saveIfAbsent(message, now + leaseMs()).getMessageId();
    }

    /**
     * 将已 claim 的消息推进到真实发布窗口。
     */
    public boolean markPublishing(String messageId) {
        if (!enabled() || messageId == null) {
            return true;
        }
        return coordinator.markPublishing(messageId, System.currentTimeMillis() + leaseMs());
    }

    public void handlePublishResult(String messageId, ReportResult result, Throwable throwable) {
        if (enabled() && messageId != null) {
            coordinator.handlePublishResult(messageId, result, throwable);
        }
    }

    public boolean isAwaitingAck(ReportResult result) {
        return enabled() && coordinator.isAwaitingAck(result);
    }

    public long getPendingCount() {
        if (!enabled()) {
            return 0L;
        }
        try {
            return repository.countPending();
        } catch (RuntimeException exception) {
            log.debug("读取云端发件箱待发送数量失败", exception);
            return -1L;
        }
    }

    public long getOldestMessageAgeMillis() {
        if (!enabled()) {
            return 0L;
        }
        long createdAt;
        try {
            createdAt = repository.oldestCreatedAt();
        } catch (RuntimeException exception) {
            log.debug("读取云端发件箱最老消息时间失败", exception);
            return -1L;
        }
        return createdAt <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - createdAt);
    }

    public long getIsolatedCount() {
        if (!enabled()) {
            return 0L;
        }
        try {
            return repository.countIsolated();
        } catch (RuntimeException exception) {
            log.debug("读取云端发件箱隔离数量失败", exception);
            return -1L;
        }
    }

    public boolean isEnabled() {
        return enabled();
    }

    @Scheduled(fixedDelayString = "${collector.report.outbox.poll-interval-ms:1000}",
            initialDelayString = "${collector.report.outbox.poll-interval-ms:1000}")
    /**
     * 处理当前业务流程。
     */
    public void dispatchDueMessages() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<CloudOutboxMessage> messages = repository.claimDue(
                now,
                Math.max(1, reportProperties.getOutbox().getClaimBatchSize()),
                now + leaseMs());
        for (CloudOutboxMessage message : messages) {
            dispatch(message);
        }
    }

    /**
     * 调度到期发件箱消息，claim 后会先进入 PUBLISHING 再调用真实发送。
     */
    private void dispatch(CloudOutboxMessage message) {
        ReportConfig config = reportConfigProvider.getConfig(message.getGatewayDeviceId());
        if (config == null || !config.validate()) {
            coordinator.markWaitingConfig(message);
            return;
        }
        ReportData data = message.toReportData();
        if (data == null) {
            coordinator.handlePublishResult(
                    message.getMessageId(), null, new IllegalStateException("发件箱消息缺少上报快照"));
            return;
        }
        if (!markPublishing(message.getMessageId())) {
            return;
        }
        reportManager.reportAsync(data, config).whenComplete((result, throwable) ->
                coordinator.handlePublishResult(message.getMessageId(), result, throwable));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private String ensureMessageId(ReportData data) {
        Object existing = data.getMetadata().get(MessageConstant.FIELD_MESSAGE_ID);
        if (existing != null && !String.valueOf(existing).isBlank()) {
            return String.valueOf(existing);
        }
        String messageId = UUID.randomUUID().toString();
        data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, messageId);
        return messageId;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validate(String localDeviceId, ReportData data) {
        if (localDeviceId == null || localDeviceId.isBlank() || data == null) {
            throw new IllegalArgumentException("发件箱消息的本地设备ID和上报数据不能为空");
        }
        Object productKey = data.getMetadata().get(CloudOutboxMetadataKeys.PRODUCT_KEY);
        Object gatewayDeviceId = data.getMetadata().get(CloudOutboxMetadataKeys.GATEWAY_DEVICE_ID);
        if (productKey == null || String.valueOf(productKey).isBlank()
                || data.getDeviceId() == null || data.getDeviceId().isBlank()
                || gatewayDeviceId == null || String.valueOf(gatewayDeviceId).isBlank()) {
            throw new IllegalArgumentException("发件箱消息缺少固定云身份或网关标识");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private long leaseMs() {
        return Math.max(1000L, reportProperties.getOutbox().getLeaseMs());
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean enabled() {
        return reportProperties.getOutbox().isEnabled() && reportProperties.mqttEnabled();
    }
}
