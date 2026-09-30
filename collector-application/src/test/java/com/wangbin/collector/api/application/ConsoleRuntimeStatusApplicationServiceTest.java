package com.wangbin.collector.api.application;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.cache.constant.CacheMetricKeys;
import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.scheduler.PerformanceStatsSnapshot;
import com.wangbin.collector.monitor.metrics.CacheMetricsSnapshot;
import com.wangbin.collector.monitor.metrics.CacheMonitorService;
import com.wangbin.collector.monitor.metrics.CloudReportMetricKeys;
import com.wangbin.collector.monitor.metrics.CloudReportMonitorService;
import com.wangbin.collector.monitor.metrics.ConsoleRuntimeStatusSnapshot;
import com.wangbin.collector.monitor.metrics.DeviceMonitorService;
import com.wangbin.collector.monitor.metrics.DeviceStatusSnapshot;
import com.wangbin.collector.monitor.metrics.ExceptionMonitorService;
import com.wangbin.collector.monitor.metrics.ExceptionStatsSnapshot;
import com.wangbin.collector.monitor.metrics.ExceptionSummary;
import com.wangbin.collector.monitor.metrics.RuntimeComponentStatus;
import com.wangbin.collector.monitor.metrics.RuntimeHealthLevel;
import com.wangbin.collector.monitor.metrics.StorageMetricsSnapshot;
import com.wangbin.collector.monitor.metrics.SystemResourceMonitorService;
import com.wangbin.collector.monitor.metrics.SystemResourceSnapshot;
import com.wangbin.collector.monitor.metrics.TdengineMonitorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsoleRuntimeStatusApplicationServiceTest {

    private static final String ERROR_STORAGE_DEFAULT = "历史存储状态异常";

    @Mock
    private CacheMonitorService cacheMonitorService;

    @Mock
    private DeviceMonitorService deviceMonitorService;

    @Mock
    private SystemResourceMonitorService systemResourceMonitorService;

    @Mock
    private ExceptionMonitorService exceptionMonitorService;

    @Mock
    private CloudReportMonitorService cloudReportMonitorService;

    @Mock
    private TdengineMonitorService tdengineMonitorService;

    @Mock
    private CollectionScheduler collectionScheduler;

    private ConsoleRuntimeStatusApplicationService applicationService;
    private CacheMetricsSnapshot cacheSnapshot;
    private DeviceStatusSnapshot deviceSnapshot;
    private SystemResourceSnapshot systemSnapshot;
    private ExceptionStatsSnapshot exceptionSnapshot;
    private PerformanceStatsSnapshot performanceSnapshot;
    private Map<String, Object> reportSnapshot;
    private StorageMetricsSnapshot storageSnapshot;

    @BeforeEach
    void setUp() {
        applicationService = new ConsoleRuntimeStatusApplicationService(cacheMonitorService,
                deviceMonitorService,
                systemResourceMonitorService,
                exceptionMonitorService,
                cloudReportMonitorService,
                tdengineMonitorService,
                collectionScheduler);
        cacheSnapshot = cache("HEALTHY");
        deviceSnapshot = devices(2, 2, 2, 2, 0, 0, List.of());
        systemSnapshot = system(10.0D, 10_000L, 100_000L, 0L, 0L);
        exceptionSnapshot = exceptions(0L, List.of());
        performanceSnapshot = performance();
        reportSnapshot = report("READY", "云上报配置就绪");
        storageSnapshot = storage(StorageMetricsSnapshot.Status.OK, "TDengine 连接正常");
        stubSnapshots();
    }

    @Test
    void getRuntimeStatusShouldReturnOkWithStableUniqueComponentsAndRawSnapshots() {
        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertEquals(RuntimeHealthLevel.OK, status.getLevel());
        assertEquals("系统运行正常", status.getMessage());
        assertTrue(status.getRisks().isEmpty());
        assertUniqueComponentCodes(status);
        assertComponentLevel(status, "cache-health", RuntimeHealthLevel.OK);
        assertComponentLevel(status, "devices-health", RuntimeHealthLevel.OK);
        assertComponentLevel(status, "system-health", RuntimeHealthLevel.OK);
        assertComponentLevel(status, "exceptions-health", RuntimeHealthLevel.OK);
        assertComponentLevel(status, "performance-health", RuntimeHealthLevel.OK);
        assertComponentLevel(status, "report-health", RuntimeHealthLevel.OK);
        assertComponentLevel(status, "storage-health", RuntimeHealthLevel.OK);
        assertSame(cacheSnapshot, status.getCache());
        assertSame(deviceSnapshot, status.getDevices());
        assertSame(systemSnapshot, status.getSystem());
        assertSame(exceptionSnapshot, status.getExceptions());
        assertSame(performanceSnapshot, status.getPerformance());
        assertSame(storageSnapshot, status.getStorage());
        assertEquals("READY", status.getReport().get(CommonMapKeys.STATUS));
    }

    @Test
    void readFailureShouldProduceSingleErrorComponentWithoutRawDuplicateComponent() {
        when(deviceMonitorService.getDeviceStatus()).thenThrow(new RuntimeException("monitor down"));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertEquals(RuntimeHealthLevel.ERROR, status.getLevel());
        assertUniqueComponentCodes(status);
        assertFalse(hasComponent(status, "devices"));
        RuntimeComponentStatus devices = component(status, "devices-health");
        assertEquals(RuntimeHealthLevel.ERROR, devices.getLevel());
        assertEquals("设备连接指标读取失败: monitor down", devices.getMessage());
        assertEquals(List.of("设备连接指标读取失败: monitor down"), status.getRisks());
    }

    @Test
    void normalNullSnapshotShouldBeUnknownWithoutReadFailureRisk() {
        when(deviceMonitorService.getDeviceStatus()).thenReturn(null);

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertEquals(RuntimeHealthLevel.UNKNOWN, status.getLevel());
        assertUniqueComponentCodes(status);
        assertComponentLevel(status, "devices-health", RuntimeHealthLevel.UNKNOWN);
        assertTrue(status.getRisks().isEmpty());
    }

    @Test
    void readFailuresShouldMarkCoreAndOptionalComponentsAsError() {
        when(systemResourceMonitorService.getResources()).thenThrow(new RuntimeException("system down"));
        when(cloudReportMonitorService.getCloudReportMetrics()).thenThrow(new RuntimeException("report down"));
        when(tdengineMonitorService.getStorageMetrics()).thenThrow(new RuntimeException("storage down"));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertEquals(RuntimeHealthLevel.ERROR, status.getLevel());
        assertComponentLevel(status, "system-health", RuntimeHealthLevel.ERROR);
        assertComponentLevel(status, "report-health", RuntimeHealthLevel.ERROR);
        assertComponentLevel(status, "storage-health", RuntimeHealthLevel.ERROR);
        assertEquals(List.of(
                "系统资源指标读取失败: system down",
                "云端上报指标读取失败: report down",
                "历史存储指标读取失败: storage down"), status.getRisks());
        assertNoBlankRisk(status);
        assertUniqueComponentCodes(status);
    }

    @ParameterizedTest
    @MethodSource("cpuThresholds")
    void systemHealthShouldRespectCpuThresholdBoundaries(double cpuLoad, RuntimeHealthLevel expectedLevel) {
        when(systemResourceMonitorService.getResources()).thenReturn(system(cpuLoad, 10_000L, 100_000L, 0L, 0L));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertComponentLevel(status, "system-health", expectedLevel);
        assertEquals(expectedLevel, status.getLevel());
    }

    @ParameterizedTest
    @MethodSource("memoryThresholds")
    void systemHealthShouldRespectMemoryThresholdBoundaries(long heapUsed,
                                                            long heapMax,
                                                            RuntimeHealthLevel expectedLevel) {
        when(systemResourceMonitorService.getResources()).thenReturn(system(10.0D, heapUsed, heapMax, 0L, 0L));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertComponentLevel(status, "system-health", expectedLevel);
        assertEquals(expectedLevel, status.getLevel());
    }

    @ParameterizedTest
    @MethodSource("invalidCpuValues")
    void invalidCpuShouldBeUnknownInsteadOfOk(double cpuLoad) {
        when(systemResourceMonitorService.getResources()).thenReturn(system(cpuLoad, 10_000L, 100_000L, 0L, 0L));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus system = component(status, "system-health");
        assertEquals(RuntimeHealthLevel.UNKNOWN, system.getLevel());
        assertEquals("系统资源指标不完整", system.getMessage());
        assertEquals(cpuLoad, (Double) system.getDetails().get("processCpuLoad"));
        assertTrue(status.getRisks().isEmpty());
    }

    @Test
    void heapMaxNotPositiveShouldBeUnknownInsteadOfOk() {
        when(systemResourceMonitorService.getResources()).thenReturn(system(10.0D, 10_000L, 0L, 0L, 0L));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus system = component(status, "system-health");
        assertEquals(RuntimeHealthLevel.UNKNOWN, system.getLevel());
        assertEquals("系统资源指标不完整", system.getMessage());
        assertEquals(-1.0D, (Double) system.getDetails().get("heapUsage"));
        assertTrue(status.getRisks().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("outboxCases")
    void systemHealthShouldRespectOutboxPriority(long pending,
                                                 long isolated,
                                                 RuntimeHealthLevel expectedLevel) {
        when(systemResourceMonitorService.getResources()).thenReturn(system(10.0D, 10_000L, 100_000L, pending, isolated));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertComponentLevel(status, "system-health", expectedLevel);
        assertEquals(expectedLevel, status.getLevel());
    }

    @ParameterizedTest
    @MethodSource("deviceCases")
    void deviceHealthShouldRespectDangerMissingAndWarningPriority(int danger,
                                                                  int warning,
                                                                  List<String> missingConnections,
                                                                  RuntimeHealthLevel expectedLevel) {
        when(deviceMonitorService.getDeviceStatus()).thenReturn(devices(2, 1, 2, 1, warning, danger, missingConnections));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertComponentLevel(status, "devices-health", expectedLevel);
        assertEquals(expectedLevel, status.getLevel());
    }

    @Test
    void exceptionHealthShouldWarnWhenTotalExceptionsExistsEvenWithoutRecentRecords() {
        when(exceptionMonitorService.getStats()).thenReturn(exceptions(100L, List.of()));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus exceptions = component(status, "exceptions-health");
        assertEquals(RuntimeHealthLevel.WARN, exceptions.getLevel());
        assertEquals("存在采集或系统异常记录", exceptions.getMessage());
        assertEquals(RuntimeHealthLevel.WARN, status.getLevel());
        assertEquals(List.of("存在采集或系统异常记录"), status.getRisks());
    }

    @Test
    void exceptionHealthShouldWarnWhenTotalExceptionsExistsWithRecentRecords() {
        when(exceptionMonitorService.getStats()).thenReturn(exceptions(1L, List.of(exceptionSummary())));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus exceptions = component(status, "exceptions-health");
        assertEquals(RuntimeHealthLevel.WARN, exceptions.getLevel());
        assertEquals("存在采集或系统异常记录", exceptions.getMessage());
        assertEquals(RuntimeHealthLevel.WARN, status.getLevel());
        assertEquals(List.of("存在采集或系统异常记录"), status.getRisks());
    }

    @Test
    void exceptionHealthShouldStayOkWhenTotalIsZeroEvenIfRecentRecordsExist() {
        when(exceptionMonitorService.getStats()).thenReturn(exceptions(0L, List.of(exceptionSummary())));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus exceptions = component(status, "exceptions-health");
        assertEquals(RuntimeHealthLevel.OK, exceptions.getLevel());
        assertEquals("暂无异常记录", exceptions.getMessage());
        assertEquals(RuntimeHealthLevel.OK, status.getLevel());
        assertTrue(status.getRisks().isEmpty());
    }

    @Test
    void exceptionHealthShouldKeepRecentCountOnlyAsDetails() {
        when(exceptionMonitorService.getStats()).thenReturn(exceptions(100L,
                List.of(exceptionSummary(), exceptionSummary(), exceptionSummary())));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus exceptions = component(status, "exceptions-health");
        assertEquals(RuntimeHealthLevel.WARN, exceptions.getLevel());
        assertEquals(100L, exceptions.getDetails().get("totalExceptions"));
        assertEquals(3, exceptions.getDetails().get("recentCount"));
    }

    @Test
    void exceptionHealthShouldBeUnknownWhenSnapshotIsNull() {
        when(exceptionMonitorService.getStats()).thenReturn(null);

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus exceptions = component(status, "exceptions-health");
        assertEquals(RuntimeHealthLevel.UNKNOWN, exceptions.getLevel());
        assertEquals("异常统计不可用", exceptions.getMessage());
        assertEquals(RuntimeHealthLevel.UNKNOWN, status.getLevel());
        assertTrue(status.getRisks().isEmpty());
    }

    @Test
    void exceptionHealthShouldBeErrorWhenStatsReadFails() {
        when(exceptionMonitorService.getStats()).thenThrow(new RuntimeException("stats down"));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus exceptions = component(status, "exceptions-health");
        assertEquals(RuntimeHealthLevel.ERROR, exceptions.getLevel());
        assertEquals("异常统计指标读取失败: stats down", exceptions.getMessage());
        assertEquals(RuntimeHealthLevel.ERROR, status.getLevel());
        assertEquals(List.of("异常统计指标读取失败: stats down"), status.getRisks());
    }

    @ParameterizedTest
    @MethodSource("reportCases")
    void reportHealthShouldMapStatusAndRisks(String rawStatus,
                                             RuntimeHealthLevel expectedLevel,
                                             RuntimeHealthLevel expectedOverall,
                                             boolean expectRisk,
                                             String expectedMessage) {
        when(cloudReportMonitorService.getCloudReportMetrics()).thenReturn(report(rawStatus, null));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus report = component(status, "report-health");
        assertEquals(expectedLevel, report.getLevel());
        assertEquals(expectedMessage, report.getMessage());
        assertEquals(expectedOverall, status.getLevel());
        assertEquals(expectRisk, status.getRisks().contains(expectedMessage));
        assertNoBlankRisk(status);
    }

    @ParameterizedTest
    @MethodSource("storageCases")
    void storageHealthShouldMapStatusAndRisks(StorageMetricsSnapshot.Status rawStatus,
                                              String message,
                                              RuntimeHealthLevel expectedLevel,
                                              RuntimeHealthLevel expectedOverall,
                                              List<String> expectedRisks) {
        when(tdengineMonitorService.getStorageMetrics()).thenReturn(storage(rawStatus, message));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        RuntimeComponentStatus storage = component(status, "storage-health");
        assertEquals(expectedLevel, storage.getLevel());
        assertEquals(expectedOverall, status.getLevel());
        assertEquals(expectedRisks, status.getRisks());
        assertNoBlankRisk(status);
    }

    @ParameterizedTest
    @MethodSource("overallCases")
    void overallHealthShouldRespectPriorityAndTreatDisabledAsNeutral(SystemResourceSnapshot system,
                                                                     Map<String, Object> report,
                                                                     StorageMetricsSnapshot storage,
                                                                     RuntimeHealthLevel expectedOverall) {
        when(systemResourceMonitorService.getResources()).thenReturn(system);
        when(cloudReportMonitorService.getCloudReportMetrics()).thenReturn(report);
        when(tdengineMonitorService.getStorageMetrics()).thenReturn(storage);

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertEquals(expectedOverall, status.getLevel());
    }

    @Test
    void risksShouldBeDeduplicatedAndKeepOriginalOrder() {
        when(systemResourceMonitorService.getResources()).thenReturn(system(80.0D, 10_000L, 100_000L, 0L, 0L));
        when(cloudReportMonitorService.getCloudReportMetrics()).thenReturn(report("WARN", "系统资源存在风险"));

        ConsoleRuntimeStatusSnapshot status = applicationService.getRuntimeStatus();

        assertEquals(List.of("系统资源存在风险"), status.getRisks());
        assertNoBlankRisk(status);
    }

    private static Stream<Arguments> cpuThresholds() {
        return Stream.of(
                Arguments.of(74.999D, RuntimeHealthLevel.OK),
                Arguments.of(75.0D, RuntimeHealthLevel.WARN),
                Arguments.of(89.999D, RuntimeHealthLevel.WARN),
                Arguments.of(90.0D, RuntimeHealthLevel.ERROR),
                Arguments.of(Double.POSITIVE_INFINITY, RuntimeHealthLevel.ERROR)
        );
    }

    private static Stream<Arguments> memoryThresholds() {
        return Stream.of(
                Arguments.of(79_999L, 100_000L, RuntimeHealthLevel.OK),
                Arguments.of(80_000L, 100_000L, RuntimeHealthLevel.WARN),
                Arguments.of(89_999L, 100_000L, RuntimeHealthLevel.WARN),
                Arguments.of(90_000L, 100_000L, RuntimeHealthLevel.ERROR),
                Arguments.of(Long.MAX_VALUE, 1L, RuntimeHealthLevel.ERROR)
        );
    }

    private static Stream<Arguments> invalidCpuValues() {
        return Stream.of(
                Arguments.of(-1.0D),
                Arguments.of(Double.NaN)
        );
    }

    private static Stream<Arguments> outboxCases() {
        return Stream.of(
                Arguments.of(1L, 0L, RuntimeHealthLevel.WARN),
                Arguments.of(0L, 1L, RuntimeHealthLevel.ERROR),
                Arguments.of(1L, 1L, RuntimeHealthLevel.ERROR)
        );
    }

    private static Stream<Arguments> deviceCases() {
        return Stream.of(
                Arguments.of(0, 0, List.of(), RuntimeHealthLevel.OK),
                Arguments.of(0, 1, List.of(), RuntimeHealthLevel.WARN),
                Arguments.of(1, 5, List.of(), RuntimeHealthLevel.ERROR),
                Arguments.of(0, 5, List.of("dev-missing"), RuntimeHealthLevel.ERROR)
        );
    }

    private static Stream<Arguments> reportCases() {
        return Stream.of(
                Arguments.of("OK", RuntimeHealthLevel.OK, RuntimeHealthLevel.OK, false, "云端上报状态未知"),
                Arguments.of("READY", RuntimeHealthLevel.OK, RuntimeHealthLevel.OK, false, "云端上报状态未知"),
                Arguments.of("WARN", RuntimeHealthLevel.WARN, RuntimeHealthLevel.WARN, true, "云端上报状态未知"),
                Arguments.of("ERROR", RuntimeHealthLevel.ERROR, RuntimeHealthLevel.ERROR, true, "云端上报状态未知"),
                Arguments.of("DISABLED", RuntimeHealthLevel.DISABLED, RuntimeHealthLevel.OK, false, "云端上报状态未知"),
                Arguments.of("SOMETHING", RuntimeHealthLevel.UNKNOWN, RuntimeHealthLevel.UNKNOWN, false, "云端上报状态未知"),
                Arguments.of(null, RuntimeHealthLevel.UNKNOWN, RuntimeHealthLevel.UNKNOWN, false, "云端上报状态未知")
        );
    }

    private static Stream<Arguments> storageCases() {
        return Stream.of(
                Arguments.of(StorageMetricsSnapshot.Status.OK, "TDengine 连接正常", RuntimeHealthLevel.OK,
                        RuntimeHealthLevel.OK, List.of()),
                Arguments.of(StorageMetricsSnapshot.Status.ERROR, "TDengine 连接检测失败", RuntimeHealthLevel.ERROR,
                        RuntimeHealthLevel.ERROR, List.of("TDengine 连接检测失败")),
                Arguments.of(StorageMetricsSnapshot.Status.ERROR, null, RuntimeHealthLevel.ERROR,
                        RuntimeHealthLevel.ERROR, List.of(ERROR_STORAGE_DEFAULT)),
                Arguments.of(StorageMetricsSnapshot.Status.DISABLED, "TDengine 未启用", RuntimeHealthLevel.DISABLED,
                        RuntimeHealthLevel.OK, List.of()),
                Arguments.of(StorageMetricsSnapshot.Status.UNKNOWN, "TDengine 数据源不可用", RuntimeHealthLevel.UNKNOWN,
                        RuntimeHealthLevel.UNKNOWN, List.of())
        );
    }

    private static Stream<Arguments> overallCases() {
        return Stream.of(
                Arguments.of(system(10.0D, 10_000L, 100_000L, 0L, 0L), report("READY", "云上报配置就绪"),
                        storage(StorageMetricsSnapshot.Status.OK, "TDengine 连接正常"), RuntimeHealthLevel.OK),
                Arguments.of(system(10.0D, 10_000L, 100_000L, 0L, 0L), report("DISABLED", "云上报未启用"),
                        storage(StorageMetricsSnapshot.Status.DISABLED, "TDengine 未启用"), RuntimeHealthLevel.OK),
                Arguments.of(system(10.0D, 10_000L, 100_000L, 0L, 0L), report("SOMETHING", "云上报状态未知"),
                        storage(StorageMetricsSnapshot.Status.OK, "TDengine 连接正常"), RuntimeHealthLevel.UNKNOWN),
                Arguments.of(system(10.0D, 10_000L, 100_000L, 1L, 0L), report("SOMETHING", "云上报状态未知"),
                        storage(StorageMetricsSnapshot.Status.OK, "TDengine 连接正常"), RuntimeHealthLevel.WARN),
                Arguments.of(system(10.0D, 10_000L, 100_000L, 1L, 1L), report("WARN", "云上报链路存在风险"),
                        storage(StorageMetricsSnapshot.Status.OK, "TDengine 连接正常"), RuntimeHealthLevel.ERROR)
        );
    }

    private void stubSnapshots() {
        when(cacheMonitorService.getCacheMetrics()).thenReturn(cacheSnapshot);
        when(deviceMonitorService.getDeviceStatus()).thenReturn(deviceSnapshot);
        when(systemResourceMonitorService.getResources()).thenReturn(systemSnapshot);
        when(exceptionMonitorService.getStats()).thenReturn(exceptionSnapshot);
        when(collectionScheduler.getPerformanceSnapshot()).thenReturn(performanceSnapshot);
        when(cloudReportMonitorService.getCloudReportMetrics()).thenReturn(reportSnapshot);
        when(tdengineMonitorService.getStorageMetrics()).thenReturn(storageSnapshot);
    }

    private static CacheMetricsSnapshot cache(String overallStatus) {
        return CacheMetricsSnapshot.builder()
                .totalReads(10L)
                .totalWrites(5L)
                .totalAccess(10L)
                .totalHitRate(90.0D)
                .health(Map.of(CacheMetricKeys.OVERALL_STATUS, overallStatus))
                .build();
    }

    private static DeviceStatusSnapshot devices(int total,
                                                int active,
                                                int expected,
                                                int healthy,
                                                int warning,
                                                int danger,
                                                List<String> missingConnections) {
        return DeviceStatusSnapshot.builder()
                .totalConnections(total)
                .activeConnections(active)
                .expectedConnections(expected)
                .healthyDevices(healthy)
                .warningDevices(warning)
                .dangerDevices(danger)
                .missingConnections(missingConnections)
                .build();
    }

    private static SystemResourceSnapshot system(double cpuLoad,
                                                 long heapUsed,
                                                 long heapMax,
                                                 long outboxPending,
                                                 long outboxIsolated) {
        return SystemResourceSnapshot.builder()
                .heapUsed(heapUsed)
                .heapCommitted(heapMax)
                .heapMax(heapMax)
                .nonHeapUsed(1L)
                .nonHeapCommitted(1L)
                .totalPhysicalMemorySize(1L)
                .freePhysicalMemorySize(1L)
                .processCpuLoad(cpuLoad)
                .systemCpuLoad(cpuLoad)
                .threadCount(8)
                .daemonThreadCount(4)
                .outboxPendingCount(outboxPending)
                .outboxIsolatedCount(outboxIsolated)
                .outboxOldestMessageAgeMillis(0L)
                .build();
    }

    private static ExceptionStatsSnapshot exceptions(long total, List<ExceptionSummary> recent) {
        return ExceptionStatsSnapshot.builder()
                .totalExceptions(total)
                .recent(recent)
                .build();
    }

    private static ExceptionSummary exceptionSummary() {
        return ExceptionSummary.builder()
                .deviceId("dev-1")
                .pointId("p1")
                .category("TIMEOUT")
                .message("timeout")
                .timestamp(1L)
                .build();
    }

    private static PerformanceStatsSnapshot performance() {
        return PerformanceStatsSnapshot.builder()
                .timeSliceCount(4)
                .timeSliceIntervalMs(250)
                .processCpuLoad(10.0D)
                .build();
    }

    private static Map<String, Object> report(String status, String statusText) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put(CommonMapKeys.STATUS, status);
        if (statusText != null) {
            report.put(CloudReportMetricKeys.STATUS_TEXT, statusText);
        }
        return report;
    }

    private static StorageMetricsSnapshot storage(StorageMetricsSnapshot.Status status, String message) {
        return StorageMetricsSnapshot.builder()
                .enabled(status != StorageMetricsSnapshot.Status.DISABLED)
                .status(status)
                .message(message)
                .responseTimeMs(5L)
                .build();
    }

    private static RuntimeComponentStatus component(ConsoleRuntimeStatusSnapshot status, String code) {
        return status.getComponents().stream()
                .filter(item -> code.equals(item.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少组件: " + code));
    }

    private static boolean hasComponent(ConsoleRuntimeStatusSnapshot status, String code) {
        return status.getComponents().stream().anyMatch(item -> code.equals(item.getCode()));
    }

    private static void assertComponentLevel(ConsoleRuntimeStatusSnapshot status,
                                             String code,
                                             RuntimeHealthLevel expectedLevel) {
        assertEquals(expectedLevel, component(status, code).getLevel(), code);
    }

    private static void assertUniqueComponentCodes(ConsoleRuntimeStatusSnapshot status) {
        assertNotNull(status.getComponents());
        long uniqueCount = status.getComponents().stream()
                .map(RuntimeComponentStatus::getCode)
                .distinct()
                .count();
        assertEquals(status.getComponents().size(), uniqueCount);
    }

    private static void assertNoBlankRisk(ConsoleRuntimeStatusSnapshot status) {
        assertNotNull(status.getRisks());
        assertTrue(status.getRisks().stream().noneMatch(risk -> risk == null || risk.trim().isEmpty()));
    }
}
