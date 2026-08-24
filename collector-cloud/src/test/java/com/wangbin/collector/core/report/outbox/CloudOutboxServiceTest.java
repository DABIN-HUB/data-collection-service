package com.wangbin.collector.core.report.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.inbound.MqttAckReply;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.service.ReportManager;
import com.wangbin.collector.core.report.service.support.ReportConfigProvider;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CloudOutboxServiceTest {

    private InMemoryRepository repository;
    private ShadowManager shadowManager;
    private ReportProperties properties;
    private CloudOutboxCoordinator coordinator;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        shadowManager = mock(ShadowManager.class);
        properties = new ReportProperties();
        properties.getOutbox().setMaxRetryTimes(2);
        properties.getOutbox().setLeaseMs(1000L);
        properties.getOutbox().setWaitingConfigRetryMs(1000L);
        coordinator = new CloudOutboxCoordinator(repository, shadowManager, properties);
    }

    @Test
    void shouldKeepMessageUntilBusinessAckAndCommitIdempotently() {
        CloudOutboxMessage message = message("msg-1", "dev-1");
        repository.saveIfAbsent(message, System.currentTimeMillis() + 1000L);

        ReportResult publishResult = ReportResult.success("p1", "gateway");
        publishResult.addMetadata(CloudOutboxMetadataKeys.ACK_PENDING, true);
        publishResult.addMetadata(CloudOutboxMetadataKeys.ACK_TIMEOUT_MS, 5000L);
        publishResult.addMetadata(CloudOutboxMetadataKeys.ACK_COMMIT_ON, "ACK_SUCCESS");
        coordinator.markPublishing("msg-1", System.currentTimeMillis() + 1000L);
        coordinator.handlePublishResult("msg-1", publishResult, null);

        CloudOutboxMessage waiting = repository.find("msg-1").orElseThrow();
        assertEquals(CloudOutboxStatus.WAITING_ACK, waiting.getStatus());

        coordinator.onAck(new MqttAckReply("msg-1", 0, "成功"));
        coordinator.onAck(new MqttAckReply("msg-1", 0, "重复确认"));

        assertTrue(repository.find("msg-1").isEmpty());
        verify(shadowManager, times(2)).markOutboxReported(
                eq("dev-1"), anyMap(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void shouldIsolateMessageAfterRetryLimit() {
        CloudOutboxMessage message = message("msg-2", "dev-2");
        repository.saveIfAbsent(message, System.currentTimeMillis());

        coordinator.handlePublishResult("msg-2", null, new IllegalStateException("第一次失败"));
        coordinator.handlePublishResult("msg-2", null, new IllegalStateException("第二次失败"));

        CloudOutboxMessage isolated = repository.find("msg-2").orElseThrow();
        assertEquals(CloudOutboxStatus.ISOLATED, isolated.getStatus());
        assertEquals(2, isolated.getAttempts());
        assertEquals(1L, repository.countIsolated());
    }

    @Test
    void shouldPersistFixedIdentityAndWaitWhenConfigMissing() {
        ReportManager reportManager = mock(ReportManager.class);
        ReportConfigProvider configProvider = mock(ReportConfigProvider.class);
        when(configProvider.getConfig("gateway-1")).thenReturn(null);
        CloudOutboxService service = new CloudOutboxService(
                repository, coordinator, reportManager, configProvider, properties);

        ReportData data = reportData("dev-cloud-1");
        String messageId = service.stage("dev-local-1", 7L, 10L, 20L, data);
        CloudOutboxMessage stored = repository.find(messageId).orElseThrow();
        assertEquals("dev-local-1", stored.getLocalDeviceId());
        assertEquals("pk-1", stored.getProductKey());
        assertEquals("dev-cloud-1", stored.getDeviceName());
        assertEquals(7L, stored.getShadowVersion());
        assertEquals(messageId,
                stored.toReportData().getMetadata().get(MessageConstant.FIELD_MESSAGE_ID));

        stored.setNextAttemptAt(0L);
        repository.reschedule(stored);
        service.dispatchDueMessages();

        CloudOutboxMessage waiting = repository.find(messageId).orElseThrow();
        assertEquals(CloudOutboxStatus.WAITING_CONFIG, waiting.getStatus());
        assertFalse(waiting.getLastError().isBlank());
    }

    @Test
    void shouldCommitEveryDeviceInAggregatedMessage() {
        CloudOutboxService service = new CloudOutboxService(
                repository,
                coordinator,
                mock(ReportManager.class),
                mock(ReportConfigProvider.class),
                properties);
        ReportData data = reportData("gateway-cloud");
        List<CloudOutboxMessage.CloudOutboxCommit> commits = List.of(
                new CloudOutboxMessage.CloudOutboxCommit(
                        "dev-a", 3L, 10L, 20L, Map.of("a", 1)),
                new CloudOutboxMessage.CloudOutboxCommit(
                        "dev-b", 5L, 10L, 20L, Map.of("b", 2)));

        String messageId = service.stageBatch(commits, data);
        coordinator.markPublishing(messageId, System.currentTimeMillis() + 1000L);
        coordinator.handlePublishResult(
                messageId, ReportResult.success("property-pack", "gateway-1"), null);

        assertTrue(repository.find(messageId).isEmpty());
        verify(shadowManager).markOutboxReported("dev-a", Map.of("a", 1), 10L, 20L, false);
        verify(shadowManager).markOutboxReported("dev-b", Map.of("b", 2), 10L, 20L, false);
        verify(shadowManager).markOutboxReported("dev-a", Map.of(), 10L, 20L, true);
        verify(shadowManager).markOutboxReported("dev-b", Map.of(), 10L, 20L, true);
    }

    @Test
    void publishingStatusShouldRoundTripAsJsonName() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CloudOutboxMessage message = message("msg-json", "dev-json");
        message.setStatus(CloudOutboxStatus.PUBLISHING);

        String json = objectMapper.writeValueAsString(message);
        CloudOutboxMessage restored = objectMapper.readValue(json, CloudOutboxMessage.class);

        assertTrue(json.contains("\"PUBLISHING\""));
        assertEquals(CloudOutboxStatus.PUBLISHING, restored.getStatus());
    }

    private CloudOutboxMessage message(String messageId, String localDeviceId) {
        ReportData data = reportData("cloud-" + localDeviceId);
        data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, messageId);
        return new CloudOutboxMessage(
                messageId,
                localDeviceId,
                "pk-1",
                data.getDeviceId(),
                "gateway-1",
                1L,
                10L,
                20L,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0,
                CloudOutboxStatus.PENDING,
                null,
                CloudOutboxMessage.ReportDataSnapshot.from(data),
                null);
    }

    private ReportData reportData(String cloudDeviceName) {
        ReportData data = new ReportData();
        data.setDeviceId(cloudDeviceName);
        data.setPointCode("p1");
        data.setTimestamp(System.currentTimeMillis());
        data.addMetadata(CloudOutboxMetadataKeys.PRODUCT_KEY, "pk-1");
        data.addMetadata(CloudOutboxMetadataKeys.GATEWAY_DEVICE_ID, "gateway-1");
        data.addProperty("temperature", 20, data.getTimestamp(), "GOOD");
        return data;
    }

    private static final class InMemoryRepository implements CloudOutboxRepository {

        private final Map<String, CloudOutboxMessage> messages = new ConcurrentHashMap<>();

        @Override
        public CloudOutboxMessage saveIfAbsent(CloudOutboxMessage message, long leaseUntil) {
            message.setNextAttemptAt(leaseUntil);
            return messages.computeIfAbsent(message.getMessageId(), key -> message);
        }

        @Override
        public Optional<CloudOutboxMessage> find(String messageId) {
            return Optional.ofNullable(messages.get(messageId));
        }

        @Override
        public List<CloudOutboxMessage> claimDue(long now, int limit, long leaseUntil) {
            List<CloudOutboxMessage> due = new ArrayList<>();
            messages.values().stream()
                    .filter(message -> message.getStatus() != CloudOutboxStatus.ISOLATED)
                    .filter(message -> message.getNextAttemptAt() <= now)
                    .sorted(Comparator.comparingLong(CloudOutboxMessage::getCreatedAt))
                    .limit(limit)
                    .forEach(message -> {
                        message.setNextAttemptAt(leaseUntil);
                        due.add(message);
                    });
            return due;
        }

        @Override
        public void reschedule(CloudOutboxMessage message) {
            messages.put(message.getMessageId(), message);
        }

        @Override
        public boolean rescheduleIfPresent(CloudOutboxMessage message) {
            return messages.replace(message.getMessageId(), message) != null;
        }

        @Override
        public void complete(String messageId) {
            messages.remove(messageId);
        }

        @Override
        public long countPending() {
            return messages.values().stream()
                    .filter(message -> message.getStatus() != CloudOutboxStatus.ISOLATED)
                    .count();
        }

        @Override
        public long countIsolated() {
            return messages.values().stream()
                    .filter(message -> message.getStatus() == CloudOutboxStatus.ISOLATED)
                    .count();
        }

        @Override
        public long oldestCreatedAt() {
            return messages.values().stream()
                    .mapToLong(CloudOutboxMessage::getCreatedAt)
                    .min()
                    .orElse(0L);
        }

        @Override
        public boolean hasPendingForDevice(String localDeviceId) {
            return messages.values().stream()
                    .flatMap(message -> message.resolveCommits().stream())
                    .anyMatch(commit -> localDeviceId.equals(commit.getLocalDeviceId()));
        }
    }
}
