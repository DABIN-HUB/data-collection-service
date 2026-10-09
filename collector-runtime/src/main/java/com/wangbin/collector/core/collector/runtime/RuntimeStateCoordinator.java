package com.wangbin.collector.core.collector.runtime;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
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
    private static final long DEFAULT_INTERVAL_MS = 2_000L;
    private static final long MIN_FRESHNESS_MS = 5_000L;
    private static final long MIN_FIRST_VALUE_TIMEOUT_MS = 30_000L;
    private final CollectionScheduler collectionScheduler;
    private final ConfigManager configManager;
    private final AcquisitionRuntimeTracker acquisitionRuntimeTracker;
    private final PointRuntimeStateService pointRuntimeStateService;

    public DeviceRuntimeState snapshot(String deviceId) {
        DeviceRuntimeSnapshot runtime = collectionScheduler.getDeviceRuntimeSnapshot(deviceId);
        DeviceInfo device = configManager.getDevice(deviceId);
        List<DataPoint> configured = configManager.getDataPoints(deviceId);
        AcquisitionRuntimeTracker.WindowSnapshot facts = runtime.configVersion() == collectionScheduler.getAppliedConfigVersion(deviceId)
                ? acquisitionRuntimeTracker.snapshot(deviceId, runtime.generation())
                : new AcquisitionRuntimeTracker.WindowSnapshot(runtime.generation(), 0L, 0L, 0L, Map.of());
        long now = System.currentTimeMillis();
        DeviceConnection connection = configManager.getConnectionConfig(deviceId);
        long deviceInterval = device == null || device.getCollectionInterval() == null
                ? DEFAULT_INTERVAL_MS : Math.max(1L, device.getCollectionInterval().longValue());
        long readTimeout = connection == null || connection.getReadTimeout() == null
                ? 0L : Math.max(0L, connection.getReadTimeout().longValue());
        long firstValueTimeout = Math.max(MIN_FIRST_VALUE_TIMEOUT_MS, add(multiply(deviceInterval, 3L), readTimeout));
        long firstValueDeadlineAt = runtime.startedAt() <= 0L ? 0L
                : runtime.startedAt() > Long.MAX_VALUE - firstValueTimeout
                ? Long.MAX_VALUE : runtime.startedAt() + firstValueTimeout;
        Map<String, PointAcquisitionSnapshot> points = new LinkedHashMap<>();
        int observed = 0;
        int failed = 0;
        int stale = 0;
        long lastValueAt = 0L;
        int participating = 0;
        int good = 0;
        boolean polling = false;
        if (configured != null) {
            for (DataPoint point : configured) {
                if (point == null || point.getPointId() == null || point.getPointId().isBlank()) continue;
                AcquisitionRuntimeTracker.PointFactSnapshot fact = facts.points().get(point.getPointId());
                boolean included = point.isEnabled() && !"W".equalsIgnoreCase(point.getReadWrite());
                boolean pollPoint = fact != null && fact.polling();
                boolean eventPoint = (fact != null && fact.event())
                        || "SUBSCRIPTION".equalsIgnoreCase(point.getCollectionMode())
                        || "EVENT".equalsIgnoreCase(point.getCollectionMode());
                polling |= included && pollPoint;
                if (included) participating++;
                PointAcquisitionSnapshot.Mode mode = mode(pollPoint, eventPoint);
                long attemptedAt = fact == null ? 0L : fact.lastAttemptAt();
                long valueAt = fact == null ? 0L : fact.lastObservedAt();
                long failureAt = fact == null ? 0L : fact.lastFailureAt();
                if (included) lastValueAt = Math.max(lastValueAt, valueAt);

                if (included && valueAt > 0L) observed++;
                long interval = point.getBaseCollectionInterval() != null && point.getBaseCollectionInterval() > 0L
                        ? point.getBaseCollectionInterval() : deviceInterval;
                interval = Math.max(interval, pointRuntimeStateService.snapshot(deviceId, point).currentCollectionInterval());
                // 订阅/事件没有变化不等于通信失败；仅配置了有效期时才按时间过期。
                long freshnessMs = pollPoint ? Math.max(MIN_FRESHNESS_MS, add(multiply(interval, 3L), readTimeout))
                        : point.getCacheDuration() != null && point.getCacheDuration() > 0
                        ? multiply(point.getCacheDuration().longValue(), 1_000L) : Long.MAX_VALUE;
                boolean outdated = valueAt > 0L && (now - valueAt > freshnessMs || !runtime.running());
                long pointDeadlineAt = runtime.startedAt() <= 0L ? 0L
                        : add(runtime.startedAt(), Math.max(MIN_FIRST_VALUE_TIMEOUT_MS, freshnessMs));
                if (included && pollPoint) firstValueDeadlineAt = Math.max(firstValueDeadlineAt, pointDeadlineAt);
                boolean latestFailed = included && fact != null && fact.failureReason() != null
                        && failureAt > 0L && failureAt >= valueAt;
                boolean noFirstValue = included && pollPoint && runtime.running() && runtime.connected()
                        && valueAt == 0L && pointDeadlineAt > 0L && now > pointDeadlineAt;
                PointAcquisitionSnapshot.Outcome outcome = latestFailed || noFirstValue
                        ? PointAcquisitionSnapshot.Outcome.FAILED
                        : outdated ? PointAcquisitionSnapshot.Outcome.STALE
                        : valueAt > 0L ? PointAcquisitionSnapshot.Outcome.OBSERVED
                        : PointAcquisitionSnapshot.Outcome.WAITING;
                if (included && outcome == PointAcquisitionSnapshot.Outcome.OBSERVED) good++;
                if (included && outcome == PointAcquisitionSnapshot.Outcome.FAILED) failed++;
                if (included && outcome == PointAcquisitionSnapshot.Outcome.STALE) stale++;
                points.put(point.getPointId(), new PointAcquisitionSnapshot(point.getPointId(), mode, outcome,
                        fact == null ? null : fact.qualityCode(), attemptedAt, valueAt, failureAt,
                        fact == null ? 0 : fact.consecutiveFailures(),
                        latestFailed ? fact.failureReason() : noFirstValue ? "NO_VALUE" : null,
                        fact == null ? null : fact.errorMessage(), outdated || latestFailed, included));
            }
        }
        long firstValueAt = observed > 0 ? facts.firstValueAt() : 0L;
        DeviceRuntimeState.AcquisitionStatus acquisition;
        if (device == null) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.UNKNOWN;
        } else if (!runtime.running() && !runtime.starting()) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.STOPPED;
        } else if (participating == 0) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.IDLE;
        } else if (firstValueAt == 0L) {
            acquisition = failed > 0 ? DeviceRuntimeState.AcquisitionStatus.FAILED
                    : DeviceRuntimeState.AcquisitionStatus.WAITING;
        } else if (stale == participating) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.STALE;
        } else if (failed > 0 || stale > 0 || good < participating) {
            acquisition = DeviceRuntimeState.AcquisitionStatus.PARTIAL;
        } else {
            acquisition = DeviceRuntimeState.AcquisitionStatus.ACTIVE;
        }
        String healthReason = runtime.degradedReason();
        boolean firstValueExpired = polling && firstValueAt == 0L && runtime.running() && runtime.connected()
                && firstValueDeadlineAt > 0L && now > firstValueDeadlineAt;
        if (firstValueExpired) {
            healthReason = "NO_FIRST_VALUE";
            acquisition = DeviceRuntimeState.AcquisitionStatus.FAILED;
        }
        DeviceRuntimeState.DeviceHealth health;
        if (!runtime.running() || !runtime.connected()) {
            health = DeviceRuntimeState.DeviceHealth.OFFLINE;
        } else if (good == participating && good > 0 && !firstValueExpired
                && facts.protocolReadyAt() > 0L && runtime.phase() != DeviceRuntimePhase.FAILED
                && !runtime.reconnecting()) {
            health = DeviceRuntimeState.DeviceHealth.ONLINE_HEALTHY;
        } else if (good > 0 && (failed > 0 || stale > 0 || good < participating)) {
            health = DeviceRuntimeState.DeviceHealth.ONLINE_PARTIAL;
        } else if (observed > 0 || runtime.phase() == DeviceRuntimePhase.FAILED
                || points.values().stream().anyMatch(point -> point.participating()
                && point.outcome() == PointAcquisitionSnapshot.Outcome.FAILED
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
                acquisition, runtime.generation(), firstValueAt, polling ? firstValueDeadlineAt : 0L,
                lastValueAt, facts.lastEventAt(),
                configured == null ? 0 : configured.size(), observed, failed, stale,
                Map.copyOf(points), now);
    }

    /** 当前配置中的所有设备均可查询，包括尚未启动的设备。 */
    public List<DeviceRuntimeState> snapshots() {
        return configManager.getAllDeviceIds().stream().sorted().map(this::snapshot).toList();
    }

    /** 兼容旧运行接口，健康字段来自与实时查询相同的一次事实投影。 */
    public DeviceRuntimeSnapshot runtimeSnapshot(String deviceId) {
        return project(snapshot(deviceId));
    }

    public List<DeviceRuntimeSnapshot> runtimeSnapshots() {
        return snapshots().stream().map(this::project).toList();
    }

    private DeviceRuntimeSnapshot project(DeviceRuntimeState state) {
        return state.runtime().withHealth(state, collectionScheduler.getDesiredState(state.deviceId()).name());
    }

    private static long multiply(long value, long factor) {
        return value > Long.MAX_VALUE / factor ? Long.MAX_VALUE : value * factor;
    }

    private static long add(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private PointAcquisitionSnapshot.Mode mode(boolean polling, boolean event) {
        if (polling && event) return PointAcquisitionSnapshot.Mode.HYBRID;
        if (polling) return PointAcquisitionSnapshot.Mode.POLLING;
        if (event) return PointAcquisitionSnapshot.Mode.EVENT;
        return PointAcquisitionSnapshot.Mode.UNKNOWN;
    }
}
