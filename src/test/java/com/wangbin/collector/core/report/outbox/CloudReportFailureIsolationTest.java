package com.wangbin.collector.core.report.outbox;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.handler.ReportHandler;
import com.wangbin.collector.core.report.inbound.MqttAckReply;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.service.ReportManager;
import com.wangbin.collector.core.report.service.support.ReportConfigProvider;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudReportFailureIsolationTest {

    private ExecutorService executorToShutdown;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executorToShutdown != null) {
            executorToShutdown.shutdownNow();
            assertTrue(executorToShutdown.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void sendFailureShouldKeepOutboxMessageAndRecoveryShouldCompleteIt() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        String messageId = service.stage("dev-1", 1L, 10L, 20L,
                reportData("cloud-dev-1", "msg-fail", "p1"));
        context.repository.forceDue(messageId);

        when(reportManager.reportAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(errorResult("send connect failed")))
                .thenReturn(CompletableFuture.completedFuture(successResult()));

        service.dispatchDueMessages();

        CloudOutboxMessage retrying = context.repository.find(messageId).orElseThrow();
        assertEquals(CloudOutboxStatus.PENDING, retrying.getStatus());
        assertEquals(1, retrying.getAttempts());

        context.repository.forceDue(messageId);
        service.dispatchDueMessages();

        assertTrue(context.repository.find(messageId).isEmpty());
        assertEquals(0L, context.repository.countPending());
    }

    @Test
    void normalPublishShouldCompleteAndClearOutbox() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        String messageId = service.stage("dev-normal", 1L, 10L, 20L,
                reportData("cloud-dev-normal", "msg-normal-success", "p1"));
        context.repository.forceDue(messageId);
        when(reportManager.reportAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(successResult()));

        service.dispatchDueMessages();

        assertTrue(context.repository.find(messageId).isEmpty());
        assertEquals(0L, context.repository.countPending());
    }

    @Test
    void earlyAckDuringPublishWindowShouldCompleteAfterPublishResultArrives() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        String messageId = service.stage("dev-early", 1L, 10L, 20L,
                reportData("cloud-dev-early", "msg-early-ack", "p1"));
        context.repository.forceDue(messageId);
        CompletableFuture<ReportResult> publishFuture = new CompletableFuture<>();
        when(reportManager.reportAsync(any(), any())).thenReturn(publishFuture);

        service.dispatchDueMessages();
        context.coordinator.onAck(new MqttAckReply(messageId, 0, "early ok"));
        publishFuture.complete(awaitingAckResult());

        assertTrue(context.repository.find(messageId).isEmpty());
        assertEquals(0L, context.repository.countPending());
    }

    @Test
    void publishFailureAfterEarlyAckMustNotResurrectMessage() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        String messageId = service.stage("dev-early-fail", 1L, 10L, 20L,
                reportData("cloud-dev-early-fail", "msg-early-fail", "p1"));
        context.repository.forceDue(messageId);
        CompletableFuture<ReportResult> publishFuture = new CompletableFuture<>();
        when(reportManager.reportAsync(any(), any())).thenReturn(publishFuture);

        service.dispatchDueMessages();
        context.coordinator.onAck(new MqttAckReply(messageId, 0, "early ok"));
        publishFuture.complete(errorResult("local publish callback failed after ack"));

        assertTrue(context.repository.find(messageId).isEmpty());
        assertEquals(0L, context.repository.countPending());
    }

    @Test
    void duplicateEarlyAckMustBeIdempotent() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        String messageId = service.stage("dev-early-duplicate", 1L, 10L, 20L,
                reportData("cloud-dev-early-duplicate", "msg-early-duplicate", "p1"));
        context.repository.forceDue(messageId);
        CompletableFuture<ReportResult> publishFuture = new CompletableFuture<>();
        when(reportManager.reportAsync(any(), any())).thenReturn(publishFuture);

        service.dispatchDueMessages();
        context.coordinator.onAck(new MqttAckReply(messageId, 0, "early ok"));
        context.coordinator.onAck(new MqttAckReply(messageId, 0, "early duplicate"));
        publishFuture.complete(awaitingAckResult());

        assertTrue(context.repository.find(messageId).isEmpty());
        assertEquals(0L, context.repository.countPending());
    }

    @Test
    void ackBeforeDispatchMustNotCompletePendingMessage() {
        TestContext context = new TestContext();
        String messageId = context.service(mock(ReportManager.class)).stage("dev-pending", 1L, 10L, 20L,
                reportData("cloud-dev-pending", "msg-pending-before-dispatch", "p1"));

        context.coordinator.onAck(new MqttAckReply(messageId, 0, "stale"));

        CloudOutboxMessage pending = context.repository.find(messageId).orElseThrow();
        assertEquals(CloudOutboxStatus.PENDING, pending.getStatus());
        assertEquals(0, pending.getAttempts());
    }

    @Test
    void isolatedAckMustBeRejected() {
        TestContext context = new TestContext();
        String messageId = context.service(mock(ReportManager.class)).stage("dev-isolated", 1L, 10L, 20L,
                reportData("cloud-dev-isolated", "msg-isolated-ack", "p1"));
        CloudOutboxMessage message = context.repository.find(messageId).orElseThrow();
        message.setStatus(CloudOutboxStatus.ISOLATED);
        context.repository.reschedule(message);

        context.coordinator.onAck(new MqttAckReply(messageId, 0, "late"));

        assertEquals(CloudOutboxStatus.ISOLATED,
                context.repository.find(messageId).orElseThrow().getStatus());
    }

    @Test
    void ackMustNotCompleteMessageBeforePublishResultEntersWaitingAck() {
        TestContext context = new TestContext();
        String messageId = context.service(mock(ReportManager.class)).stage("dev-ack", 1L, 10L, 20L,
                reportData("cloud-dev-ack", "msg-ack-state", "p1"));

        context.coordinator.onAck(new MqttAckReply(messageId, 0, "stale"));

        CloudOutboxMessage pending = context.repository.find(messageId).orElseThrow();
        assertEquals(CloudOutboxStatus.PENDING, pending.getStatus());

        context.coordinator.markWaitingConfig(pending);
        context.coordinator.onAck(new MqttAckReply(messageId, 0, "stale"));
        assertEquals(CloudOutboxStatus.WAITING_CONFIG,
                context.repository.find(messageId).orElseThrow().getStatus());

        context.coordinator.markPublishing(messageId, System.currentTimeMillis() + 1000L);
        context.coordinator.handlePublishResult(messageId, awaitingAckResult(), null);
        assertEquals(CloudOutboxStatus.WAITING_ACK,
                context.repository.find(messageId).orElseThrow().getStatus());

        context.coordinator.onAck(new MqttAckReply(messageId, 0, "ok"));

        assertTrue(context.repository.find(messageId).isEmpty());
    }

    @Test
    void ackBusinessFailureShouldOnlyRescheduleWaitingAckMessage() {
        TestContext context = new TestContext();
        CloudOutboxService service = context.service(mock(ReportManager.class));
        String pendingId = service.stage("dev-pending", 1L, 10L, 20L,
                reportData("cloud-dev-pending", "msg-pending-ack-fail", "p1"));
        String waitingId = service.stage("dev-waiting", 1L, 10L, 20L,
                reportData("cloud-dev-waiting", "msg-waiting-ack-fail", "p2"));
        context.coordinator.markPublishing(waitingId, System.currentTimeMillis() + 1000L);
        context.coordinator.handlePublishResult(waitingId, awaitingAckResult(), null);

        context.coordinator.onAck(new MqttAckReply(pendingId, 500, "stale failure"));
        context.coordinator.onAck(new MqttAckReply(waitingId, 500, "business rejected"));

        CloudOutboxMessage pending = context.repository.find(pendingId).orElseThrow();
        CloudOutboxMessage waiting = context.repository.find(waitingId).orElseThrow();
        assertEquals(CloudOutboxStatus.PENDING, pending.getStatus());
        assertEquals(0, pending.getAttempts());
        assertEquals(CloudOutboxStatus.PENDING, waiting.getStatus());
        assertEquals(1, waiting.getAttempts());
    }

    @Test
    void duplicateUnknownAndOutOfOrderAckShouldBeIdempotentAndMessageScoped() {
        TestContext context = new TestContext();
        CloudOutboxService service = context.service(mock(ReportManager.class));
        List<String> messageIds = List.of(
                service.stage("dev-1", 1L, 10L, 20L, reportData("cloud-dev-1", "msg-1", "p1")),
                service.stage("dev-2", 1L, 10L, 20L, reportData("cloud-dev-2", "msg-2", "p2")),
                service.stage("dev-3", 1L, 10L, 20L, reportData("cloud-dev-3", "msg-3", "p3")));
        for (String messageId : messageIds) {
            context.coordinator.markPublishing(messageId, System.currentTimeMillis() + 1000L);
            context.coordinator.handlePublishResult(messageId, awaitingAckResult(), null);
        }

        context.coordinator.onAck(new MqttAckReply("msg-3", 0, "ok"));
        context.coordinator.onAck(new MqttAckReply("missing", 0, "unknown"));
        context.coordinator.onAck(new MqttAckReply("msg-1", 0, "ok"));
        context.coordinator.onAck(new MqttAckReply("msg-3", 0, "duplicate"));
        context.coordinator.onAck(new MqttAckReply("msg-2", 0, "ok"));

        for (String messageId : messageIds) {
            assertTrue(context.repository.find(messageId).isEmpty());
        }
        assertEquals(0L, context.repository.countPending());
    }

    @Test
    void ackTimeoutRetryShouldReuseSameMessageId() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        String messageId = service.stage("dev-timeout", 1L, 10L, 20L,
                reportData("cloud-dev-timeout", "msg-timeout", "p1"));
        context.coordinator.markPublishing(messageId, System.currentTimeMillis() + 1000L);
        context.coordinator.handlePublishResult(messageId, awaitingAckResult(), null);
        context.repository.forceDue(messageId);

        List<ReportData> dispatched = new ArrayList<>();
        when(reportManager.reportAsync(any(), any())).thenAnswer(invocation -> {
            dispatched.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(awaitingAckResult());
        });

        service.dispatchDueMessages();

        assertEquals(1, dispatched.size());
        assertEquals(messageId, dispatched.get(0).getMetadata().get(MessageConstant.FIELD_MESSAGE_ID));
        assertEquals(CloudOutboxStatus.WAITING_ACK,
                context.repository.find(messageId).orElseThrow().getStatus());
    }

    @Test
    void sendSuccessButOutboxCompleteFailureShouldKeepMessageForAtLeastOnceReplay() {
        FailingCompleteRepository repository = new FailingCompleteRepository();
        TestContext context = new TestContext(repository);
        String messageId = context.service(mock(ReportManager.class)).stage("dev-complete", 1L, 10L, 20L,
                reportData("cloud-dev-complete", "msg-complete", "p1"));
        context.coordinator.markPublishing(messageId, System.currentTimeMillis() + 1000L);

        assertThrows(IllegalStateException.class,
                () -> context.coordinator.handlePublishResult(messageId, successResult(), null));

        assertTrue(repository.find(messageId).isPresent());
        repository.failComplete(false);
        context.coordinator.markPublishing(messageId, System.currentTimeMillis() + 1000L);
        context.coordinator.handlePublishResult(messageId, successResult(), null);
        assertTrue(repository.find(messageId).isEmpty());
    }

    @Test
    void failureStormShouldNotCreatePermanentBacklogAfterRecovery() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        AtomicBoolean recovered = new AtomicBoolean(false);
        when(reportManager.reportAsync(any(), any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(recovered.get() ? successResult() : errorResult("cloud down")));

        for (int i = 0; i < 50; i++) {
            String messageId = service.stage("dev-" + i, 1L, 10L, 20L,
                    reportData("cloud-dev-" + i, "msg-storm-" + i, "p" + i));
            context.repository.forceDue(messageId);
        }

        service.dispatchDueMessages();

        assertEquals(50L, context.repository.countPending());
        assertTrue(context.repository.messages().stream().allMatch(message -> message.getAttempts() == 1));

        recovered.set(true);
        context.repository.forceAllDue();
        service.dispatchDueMessages();

        assertEquals(0L, context.repository.countPending());
    }

    @Test
    void batchFailureShouldRetryWholeOutboxMessageAndRecoveryShouldCommitAllDevices() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        ReportData batchData = reportData("gateway-cloud", "msg-batch", "property-pack");
        String messageId = service.stageBatch(List.of(
                new CloudOutboxMessage.CloudOutboxCommit("dev-a", 3L, 10L, 20L, Map.of("a", 1)),
                new CloudOutboxMessage.CloudOutboxCommit("dev-b", 5L, 10L, 20L, Map.of("b", 2))
        ), batchData);
        context.repository.forceDue(messageId);
        when(reportManager.reportAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(errorResult("partial item rejected")))
                .thenReturn(CompletableFuture.completedFuture(successResult()));

        service.dispatchDueMessages();

        CloudOutboxMessage retrying = context.repository.find(messageId).orElseThrow();
        assertEquals(CloudOutboxStatus.PENDING, retrying.getStatus());
        assertEquals(1, retrying.getAttempts());

        context.repository.forceDue(messageId);
        service.dispatchDueMessages();

        assertTrue(context.repository.find(messageId).isEmpty());
    }

    @Test
    void poisonMessageShouldNotBlockFollowingNormalMessage() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        CloudOutboxService service = context.service(reportManager);
        String poisonId = service.stage("dev-poison", 1L, 10L, 20L,
                reportData("cloud-dev-poison", "msg-poison", "poison"));
        String normalId = service.stage("dev-normal", 1L, 10L, 20L,
                reportData("cloud-dev-normal", "msg-normal", "normal"));
        context.repository.forceAllDue();
        when(reportManager.reportAsync(any(), any())).thenAnswer(invocation -> {
            ReportData data = invocation.getArgument(0);
            if ("poison".equals(data.getPointCode())) {
                return CompletableFuture.completedFuture(errorResult("payload encode failed"));
            }
            return CompletableFuture.completedFuture(successResult());
        });

        service.dispatchDueMessages();

        assertTrue(context.repository.find(poisonId).isPresent());
        assertTrue(context.repository.find(normalId).isEmpty());
        assertEquals(1L, context.repository.countPending());
    }

    @Test
    void multiTargetFailureShouldNotBlockHealthyTarget() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        ReportConfigProvider configProvider = mock(ReportConfigProvider.class);
        when(configProvider.getConfig("gateway-a")).thenReturn(validConfig("gateway-a"));
        when(configProvider.getConfig("gateway-b")).thenReturn(validConfig("gateway-b"));
        CloudOutboxService service = new CloudOutboxService(
                context.repository,
                context.coordinator,
                reportManager,
                configProvider,
                context.properties);
        String okId = service.stage("dev-a", 1L, 10L, 20L,
                reportData("cloud-dev-a", "msg-a", "p-a", "gateway-a"));
        String failedId = service.stage("dev-b", 1L, 10L, 20L,
                reportData("cloud-dev-b", "msg-b", "p-b", "gateway-b"));
        context.repository.forceAllDue();
        when(reportManager.reportAsync(any(), any())).thenAnswer(invocation -> {
            ReportConfig config = invocation.getArgument(1);
            return CompletableFuture.completedFuture("gateway-b".equals(config.getTargetId())
                    ? errorResult("target-b down") : successResult());
        });

        service.dispatchDueMessages();

        assertTrue(context.repository.find(okId).isEmpty());
        assertTrue(context.repository.find(failedId).isPresent());
    }

    @Test
    void persistedOutboxShouldDispatchAfterServiceRestart() {
        InMemoryRepository repository = new InMemoryRepository();
        TestContext first = new TestContext(repository);
        String messageId = first.service(mock(ReportManager.class)).stage("dev-restart", 1L, 10L, 20L,
                reportData("cloud-dev-restart", "msg-restart", "p1"));
        repository.forceDue(messageId);

        TestContext restarted = new TestContext(repository);
        ReportManager reportManager = mock(ReportManager.class);
        when(reportManager.reportAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(successResult()));

        restarted.service(reportManager).dispatchDueMessages();

        assertTrue(repository.find(messageId).isEmpty());
    }

    @Test
    void publishingLeaseExpiryShouldBeRecoverableAfterDispatcherRestart() {
        InMemoryRepository repository = new InMemoryRepository();
        TestContext first = new TestContext(repository);
        ReportManager blockedManager = mock(ReportManager.class);
        String messageId = first.service(blockedManager).stage("dev-publishing-restart", 1L, 10L, 20L,
                reportData("cloud-dev-publishing-restart", "msg-publishing-restart", "p1"));
        repository.forceDue(messageId);
        when(blockedManager.reportAsync(any(), any())).thenReturn(new CompletableFuture<>());

        first.service(blockedManager).dispatchDueMessages();

        assertEquals(CloudOutboxStatus.PUBLISHING,
                repository.find(messageId).orElseThrow().getStatus());

        repository.forceDue(messageId);
        TestContext restarted = new TestContext(repository);
        ReportManager recoveredManager = mock(ReportManager.class);
        when(recoveredManager.reportAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(successResult()));

        restarted.service(recoveredManager).dispatchDueMessages();

        assertTrue(repository.find(messageId).isEmpty());
    }

    @Test
    void noBusinessAckPublishSuccessAndFailureShouldUsePublishingBoundary() {
        TestContext context = new TestContext();
        ReportManager reportManager = mock(ReportManager.class);
        ReportConfigProvider configProvider = mock(ReportConfigProvider.class);
        when(configProvider.getConfig("gateway-1")).thenReturn(validConfig("gateway-1"));
        CloudOutboxService service = new CloudOutboxService(
                context.repository,
                context.coordinator,
                reportManager,
                configProvider,
                context.properties);
        String successId = service.stage("dev-http-ok", 1L, 10L, 20L,
                reportData("cloud-dev-http-ok", "msg-http-ok", "p1"));
        when(reportManager.reportAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(successResult()))
                .thenReturn(CompletableFuture.completedFuture(errorResult("http 500")));

        context.repository.forceDue(successId);
        service.dispatchDueMessages();

        String failureId = service.stage("dev-http-fail", 1L, 10L, 20L,
                reportData("cloud-dev-http-fail", "msg-http-fail", "p2"));
        context.repository.forceDue(failureId);
        service.dispatchDueMessages();

        assertTrue(context.repository.find(successId).isEmpty());
        CloudOutboxMessage failed = context.repository.find(failureId).orElseThrow();
        assertEquals(CloudOutboxStatus.PENDING, failed.getStatus());
        assertEquals(1, failed.getAttempts());
    }

    @Test
    void reportManagerShouldIsolateSlowCloudSendToReportExecutorAndDrain() throws Exception {
        ThreadPoolExecutor reportExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(8),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName("test-report-" + thread.getId());
                    return thread;
                });
        executorToShutdown = reportExecutor;
        BlockingReportHandler handler = new BlockingReportHandler();
        ReportManager manager = new ReportManager(new ReportProperties(), reportExecutor, List.of(handler));
        manager.init();

        CompletableFuture<ReportResult> first = manager.reportAsync(
                reportData("cloud-dev-slow", "msg-slow-1", "p1"), validConfig("gateway-1"));
        assertTrue(handler.awaitEntered());
        CompletableFuture<ReportResult> second = manager.reportAsync(
                reportData("cloud-dev-slow", "msg-slow-2", "p2"), validConfig("gateway-1"));

        assertEquals(1, reportExecutor.getActiveCount());
        assertEquals(1, reportExecutor.getQueue().size());
        assertFalse(first.isDone());
        assertFalse(second.isDone());

        assertTimeoutPreemptively(Duration.ofMillis(500), manager::destroy);
        handler.release();

        assertTrue(first.get(2, TimeUnit.SECONDS).isSuccess());
        assertFalse(second.get(2, TimeUnit.SECONDS).isSuccess());
        waitUntil(() -> reportExecutor.getActiveCount() == 0 && reportExecutor.getQueue().isEmpty());
    }

    private static ReportData reportData(String cloudDeviceName, String messageId, String pointCode) {
        return reportData(cloudDeviceName, messageId, pointCode, "gateway-1");
    }

    private static ReportData reportData(String cloudDeviceName,
                                         String messageId,
                                         String pointCode,
                                         String gatewayDeviceId) {
        ReportData data = new ReportData();
        data.setDeviceId(cloudDeviceName);
        data.setPointCode(pointCode);
        data.setTimestamp(System.currentTimeMillis());
        data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, messageId);
        data.addMetadata(CloudOutboxMetadataKeys.PRODUCT_KEY, "pk-1");
        data.addMetadata(CloudOutboxMetadataKeys.GATEWAY_DEVICE_ID, gatewayDeviceId);
        data.addProperty(pointCode, 20, data.getTimestamp(), "GOOD");
        return data;
    }

    private static ReportResult successResult() {
        return ReportResult.success("p1", "gateway-1");
    }

    private static ReportResult awaitingAckResult() {
        ReportResult result = successResult();
        result.addMetadata(CloudOutboxMetadataKeys.ACK_PENDING, true);
        result.addMetadata(CloudOutboxMetadataKeys.ACK_TIMEOUT_MS, 1000L);
        result.addMetadata(CloudOutboxMetadataKeys.ACK_COMMIT_ON, "ACK_SUCCESS");
        return result;
    }

    private static ReportResult errorResult(String message) {
        return ReportResult.error("p1", message, "gateway-1");
    }

    private static ReportConfig validConfig(String targetId) {
        return validConfig("MQTT", targetId);
    }

    private static ReportConfig validConfig(String protocol, String targetId) {
        ReportConfig config = new ReportConfig();
        config.setProtocol(protocol);
        config.setTargetId(targetId);
        config.setHost("localhost");
        config.setPort(1883);
        config.setParams(new java.util.HashMap<>());
        return config;
    }

    private static void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    private static final class TestContext {
        private final InMemoryRepository repository;
        private final ShadowManager shadowManager = mock(ShadowManager.class);
        private final ReportProperties properties = new ReportProperties();
        private final CloudOutboxCoordinator coordinator;

        private TestContext() {
            this(new InMemoryRepository());
        }

        private TestContext(InMemoryRepository repository) {
            this.repository = repository;
            properties.getOutbox().setMaxRetryTimes(20);
            properties.getOutbox().setLeaseMs(1000L);
            properties.getOutbox().setClaimBatchSize(100);
            properties.setRetryBackoffMs(100L);
            properties.setMaxRetryBackoffMs(500L);
            properties.setRetryJitterEnabled(false);
            this.coordinator = new CloudOutboxCoordinator(repository, shadowManager, properties);
        }

        private CloudOutboxService service(ReportManager reportManager) {
            ReportConfigProvider configProvider = mock(ReportConfigProvider.class);
            when(configProvider.getConfig("gateway-1")).thenReturn(validConfig("gateway-1"));
            return new CloudOutboxService(repository, coordinator, reportManager, configProvider, properties);
        }
    }

    private static class InMemoryRepository implements CloudOutboxRepository {

        private final Map<String, CloudOutboxMessage> messages = new java.util.concurrent.ConcurrentHashMap<>();

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

        private void forceDue(String messageId) {
            CloudOutboxMessage message = messages.get(messageId);
            if (message != null) {
                message.setNextAttemptAt(0L);
            }
        }

        private void forceAllDue() {
            messages.values().forEach(message -> message.setNextAttemptAt(0L));
        }

        private List<CloudOutboxMessage> messages() {
            return new ArrayList<>(messages.values());
        }
    }

    private static final class FailingCompleteRepository extends InMemoryRepository {
        private boolean failComplete = true;

        @Override
        public void complete(String messageId) {
            if (failComplete) {
                throw new IllegalStateException("outbox complete failed");
            }
            super.complete(messageId);
        }

        private void failComplete(boolean failComplete) {
            this.failComplete = failComplete;
        }
    }

    private static final class BlockingReportHandler implements ReportHandler {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger reports = new AtomicInteger();

        @Override
        public String getName() {
            return "blocking-test-handler";
        }

        @Override
        public String getProtocol() {
            return "MQTT";
        }

        @Override
        public void init() {
        }

        @Override
        public ReportResult report(ReportData data, ReportConfig config) throws Exception {
            reports.incrementAndGet();
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return ReportResult.success(data.getPointCode(), config.getTargetId());
        }

        @Override
        public List<ReportResult> batchReport(List<ReportData> dataList, ReportConfig config) {
            return dataList.stream()
                    .map(data -> ReportResult.success(data.getPointCode(), config.getTargetId()))
                    .toList();
        }

        @Override
        public void onConfigUpdate(ReportConfig config) {
        }

        @Override
        public void onConfigRemove(ReportConfig config) {
        }

        @Override
        public Map<String, Object> getStatus() {
            return Map.of("reports", reports.get());
        }

        @Override
        public Map<String, Object> getStatistics() {
            return Map.of("reports", reports.get());
        }

        @Override
        public void resetStatistics() {
            reports.set(0);
        }

        @Override
        public void destroy() {
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}
