package com.wangbin.collector.core.collector.runtime;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 供应用层注入的单设备统一运行态投影；协议与传输名称来自配置，不猜测协议握手能力。 */
@Service
@RequiredArgsConstructor
public class RuntimeStateCoordinator {
    private final CollectionScheduler collectionScheduler;
    private final ConfigManager configManager;
    private final AcquisitionRuntimeTracker acquisitionRuntimeTracker;

    public DeviceRuntimeState snapshot(String deviceId) {
        DeviceRuntimeSnapshot runtime = collectionScheduler.getDeviceRuntimeSnapshot(deviceId);
        DeviceInfo device = configManager.getDevice(deviceId);
        List<DataPoint> configured = configManager.getDataPoints(deviceId);
        AcquisitionRuntimeTracker.WindowSnapshot facts = acquisitionRuntimeTracker.snapshot(deviceId, runtime.generation());
        long now = System.currentTimeMillis();
        long deviceInterval = device == null || device.getCollectionInterval() == null
                ? 2_000L : Math.max(1L, device.getCollectionInterval().longValue());
        long firstValueTimeout = Math.max(30_000L,
                deviceInterval > Long.MAX_VALUE / 3L ? Long.MAX_VALUE : deviceInterval * 3L);
        long firstValueDeadlineAt = runtime.startedAt() <= 0L ? 0L
                : runtime.startedAt() > Long.MAX_VALUE - firstValueTimeout
                ? Long.MAX_VALUE : runtime.startedAt() + firstValueTimeout;
        boolean firstValueExpired = runtime.running() && runtime.connected() && facts.firstValueAt() == 0L
                && firstValueDeadlineAt > 0L && now > firstValueDeadlineAt;
        Map<String, PointAcquisitionSnapshot> points = new LinkedHashMap<>();
        int observed = 0;
        int failed = 0;
        int stale = 0;
        long lastValueAt = 0L;
        boolean polling = false;
        boolean event = facts.lastEventAt() > 0L;
        if (configured != null) {
            for (DataPoint point : configured) {
                if (point == null || point.getPointId() == null || point.getPointId().isBlank()) continue;
                AcquisitionRuntimeTracker.PointFactSnapshot fact = facts.points().get(point.getPointId());
                boolean pollPoint = fact != null && fact.polling();
                boolean eventPoint = fact != null && fact.event();
                polling |= pollPoint;
                event |= eventPoint;
                PointAcquisitionSnapshot.Mode mode = mode(pollPoint, eventPoint);
                long attemptedAt = fact == null ? 0L : fact.lastAttemptAt();
                long valueAt = fact == null ? 0L : fact.lastObservedAt();
                long failureAt = fact == null ? 0L : fact.lastFailureAt();
                lastValueAt = Math.max(lastValueAt, valueAt);
                if (valueAt > 0L) observed++;
                long interval = point.getBaseCollectionInterval() != null && point.getBaseCollectionInterval() > 0L
                        ? point.getBaseCollectionInterval() : 2_000L;
                long freshnessMs = Math.max(5_000L, interval > Long.MAX_VALUE / 3L ? Long.MAX_VALUE : interval * 3L);
                boolean outdated = valueAt > 0L && (now - valueAt > freshnessMs || !runtime.running());
                boolean latestFailed = fact != null && failureAt > 0L && failureAt >= valueAt;
                boolean noFirstValue = firstValueExpired && fact != null && fact.lastAttemptAt() == 0L;
                PointAcquisitionSnapshot.Outcome outcome = latestFailed || noFirstValue
                        ? PointAcquisitionSnapshot.Outcome.FAILED
                        : outdated ? PointAcquisitionSnapshot.Outcome.STALE
                        : valueAt > 0L ? PointAcquisitionSnapshot.Outcome.OBSERVED
                        : PointAcquisitionSnapshot.Outcome.WAITING;
                if (outcome == PointAcquisitionSnapshot.Outcome.FAILED) failed++;
                if (outcome == PointAcquisitionSnapshot.Outcome.STALE) stale++;
                points.put(point.getPointId(), new PointAcquisitionSnapshot(point.getPointId(), mode, outcome,
                        fact == null ? null : fact.qualityCode(), attemptedAt, valueAt, failureAt,
                        fact == null ? 0 : fact.consecutiveFailures(),
                        fact == null ? null : fact.failureReason() != null ? fact.failureReason()
                                : noFirstValue ? "NO_VALUE" : null,
                        fact == null ? null : fact.errorMessage(), outdated || latestFailed));
            }
        }
        DeviceRuntimeState.AcquisitionStatus acquisition;
        if (device == null) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.UNKNOWN;
        } else if (!runtime.running() && !runtime.starting()) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.STOPPED;
        } else if (configured == null || configured.isEmpty()) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.IDLE;
        } else if (facts.firstValueAt() == 0L) {
            acquisition = failed > 0 ? DeviceRuntimeState.AcquisitionStatus.FAILED
                    : DeviceRuntimeState.AcquisitionStatus.WAITING;
        } else if (failed > 0 || stale > 0 || observed < points.size()) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.PARTIAL;
        } else if (lastValueAt > 0L && now - lastValueAt > 5_000L && !polling && event) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.STALE;
        } else {
            acquisition = DeviceRuntimeState.AcquisitionStatus.ACTIVE;
        }
        String healthReason = runtime.degradedReason();
        if (firstValueExpired) {
            healthReason = "NO_FIRST_VALUE";
            acquisition = DeviceRuntimeState.AcquisitionStatus.FAILED;
        }
        int good = (int) points.values().stream()
                .filter(point -> point.outcome() == PointAcquisitionSnapshot.Outcome.OBSERVED).count();
        DeviceRuntimeState.DeviceHealth health;
        if (!runtime.running() || !runtime.connected()) {
            health = DeviceRuntimeState.DeviceHealth.OFFLINE;
        } else if (good == points.size() && good > 0 && !firstValueExpired
                && facts.protocolReadyAt() > 0L && runtime.phase() != DeviceRuntimePhase.FAILED) {
            health = DeviceRuntimeState.DeviceHealth.ONLINE_HEALTHY;
        } else if (good > 0 && (failed > 0 || stale > 0 || good < points.size())) {
            health = DeviceRuntimeState.DeviceHealth.ONLINE_PARTIAL;
        } else if (observed > 0 || runtime.phase() == DeviceRuntimePhase.FAILED
                || points.values().stream().anyMatch(point -> point.outcome() == PointAcquisitionSnapshot.Outcome.FAILED
                && !"NO_VALUE".equals(point.failureReason()))) {
            health = DeviceRuntimeState.DeviceHealth.DEGRADED;
        } else {
            health = DeviceRuntimeState.DeviceHealth.ONLINE_NO_DATA;
        }
        if (healthReason == null && health != DeviceRuntimeState.DeviceHealth.ONLINE_HEALTHY) {
            healthReason = health.name();
        }
        DeviceRuntimeState.ProtocolStatus protocol = device == null
                ? DeviceRuntimeState.ProtocolStatus.UNKNOWN
                : !runtime.running() && !runtime.starting()
                ? DeviceRuntimeState.ProtocolStatus.STOPPED
                : runtime.running() && !runtime.connected()
                ? DeviceRuntimeState.ProtocolStatus.ERROR
                : runtime.connected() && facts.protocolReadyAt() > 0L
                ? DeviceRuntimeState.ProtocolStatus.READY
                : runtime.phase() == DeviceRuntimePhase.FAILED
                ? DeviceRuntimeState.ProtocolStatus.ERROR
                : DeviceRuntimeState.ProtocolStatus.NEGOTIATING;
        DeviceRuntimeState.TransportStatus transport = !runtime.running() && !runtime.starting()
                ? DeviceRuntimeState.TransportStatus.UNKNOWN
                : runtime.starting() && !runtime.connected() ? DeviceRuntimeState.TransportStatus.CONNECTING
                : runtime.connected() ? DeviceRuntimeState.TransportStatus.CONNECTED
                : DeviceRuntimeState.TransportStatus.DISCONNECTED;
        return new DeviceRuntimeState(deviceId, runtime,
                device == null ? null : device.getProtocolType(),
                device == null ? null : device.getConnectionType(),
                transport, protocol, health, healthReason,
                health == DeviceRuntimeState.DeviceHealth.ONLINE_HEALTHY && protocol == DeviceRuntimeState.ProtocolStatus.READY,
                acquisition, runtime.generation(), facts.firstValueAt(), firstValueDeadlineAt,
                lastValueAt, facts.lastEventAt(),
                configured == null ? 0 : configured.size(), observed, failed, stale,
                Map.copyOf(points), now);
    }

    /** 当前配置中的所有设备均可查询，包括尚未启动的设备。 */
    public List<DeviceRuntimeState> snapshots() {
        return configManager.getAllDeviceIds().stream().sorted().map(this::snapshot).toList();
    }

    private PointAcquisitionSnapshot.Mode mode(boolean polling, boolean event) {
        if (polling && event) return PointAcquisitionSnapshot.Mode.HYBRID;
        if (polling) return PointAcquisitionSnapshot.Mode.POLLING;
        if (event) return PointAcquisitionSnapshot.Mode.EVENT;
        return PointAcquisitionSnapshot.Mode.UNKNOWN;
    }
}
