package com.wangbin.collector.core.collector.runtime;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.config.validator.ProtocolPointValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 只记录当前设备代次的点位采集事实，不把缓存命中或批次提交当作点位成功。 */
@Component
public class AcquisitionRuntimeTracker {
    private final CollectionTaskGuard taskGuard;
    private final List<ProtocolPointValidator> validators;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public AcquisitionRuntimeTracker(CollectionTaskGuard taskGuard, List<ProtocolPointValidator> validators) {
        this.taskGuard = taskGuard;
        this.validators = List.copyOf(validators);
    }

    public AcquisitionRuntimeTracker(CollectionTaskGuard taskGuard) {
        this(taskGuard, List.of());
    }

    /** 启动新代次时重置首次有效值窗口，同时限定可记录的稳定点位身份。 */
    public void open(String deviceId, long generation, List<DataPoint> configuredPoints) {
        open(deviceId, generation, null, configuredPoints);
    }

    /** 按协议扩展校验单点，坏点保留失败事实但不进入读取计划。 */
    public void open(String deviceId, long generation, DeviceInfo device, List<DataPoint> configuredPoints) {
        if (!taskGuard.isCurrent(deviceId, generation)) return;
        Window window = new Window(generation);
        if (configuredPoints != null) {
            for (DataPoint point : configuredPoints) {
                if (point == null || point.getPointId() == null || point.getPointId().isBlank()) continue;
                PointFact fact = new PointFact(point.getPointCode(), point.getAddress(), point.getDataType());
                window.points.put(point.getPointId(), fact);
                if (device == null) continue;
                for (ProtocolPointValidator validator : validators) {
                    if (!validator.supports(device)) continue;
                    try {
                        validator.validate(List.of(point));
                    } catch (IllegalArgumentException invalid) {
                        fact.configError = invalid.getMessage();
                        fact.lastAttemptAt = System.currentTimeMillis();
                        failure(fact, fact.lastAttemptAt, "CONFIG_ERROR");
                        break;
                    }
                }
            }
        }
        windows.put(deviceId, window);
    }

    public boolean isValidPoint(String deviceId, long generation, String pointId) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation) return false;
        synchronized (window) {
            PointFact fact = window.points.get(pointId);
            return fact != null && fact.configError == null && taskGuard.isCurrent(deviceId, generation);
        }
    }

    /** 已实际建出的轮询计划是模式事实；未命中轮询计划的点位仍可能由事件源产生。 */
    public void markPollingPlan(String deviceId, long generation, Set<String> pointIds) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation || pointIds == null) return;
        synchronized (window) {
            if (!taskGuard.isCurrent(deviceId, generation)) return;
            for (String pointId : pointIds) {
                PointFact fact = window.points.get(pointId);
                if (fact != null) fact.polling = true;
            }
        }
    }

    /** 成功完成一次轮询读取后逐点核对，缺失/null 是失败而不是成功点位。 */
    public void recordPollingResults(String deviceId, long generation, List<DataPoint> points,
                                     Map<String, Object> values) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation || points == null) return;
        synchronized (window) {
            if (!taskGuard.isCurrent(deviceId, generation)) return;
            long now = System.currentTimeMillis();
            for (DataPoint point : points) {
                if (point == null) continue;
                PointFact fact = window.points.get(point.getPointId());
                if (fact == null || fact.configError != null) continue;
                // 本代次真实轮询返回了可解码的点值，证明协议读取成功；TCP 连通和空响应均不计入。
                if (values != null && values.get(point.getPointId()) != null && window.protocolReadyAt == 0L) {
                    window.protocolReadyAt = now;
                }
                if (fact.failureReason != null && fact.lastFailureAt >= fact.lastAttemptAt) continue;
                fact.polling = true;
                fact.lastAttemptAt = now;
                if (values == null || values.get(point.getPointId()) == null) {
                    failure(fact, now, values != null && values.containsKey(point.getPointId())
                            ? "NO_VALUE" : "MAPPING_ERROR");
                }
            }
        }
    }

    /** 超时、异常或执行器拒绝时给本轮已请求点位记失败；旧代次一律丢弃。 */
    public void recordPollingFailure(String deviceId, long generation, List<DataPoint> points, String reason) {
        recordPollingFailure(deviceId, generation, points, reason, null);
    }

    public void recordPollingFailure(String deviceId, long generation, List<DataPoint> points,
                                     String reason, String detail) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation || points == null) return;
        synchronized (window) {
            if (!taskGuard.isCurrent(deviceId, generation)) return;
            long now = System.currentTimeMillis();
            for (DataPoint point : points) {
                if (point == null) continue;
                PointFact fact = window.points.get(point.getPointId());
                if (fact != null && fact.configError == null) {
                    fact.polling = true;
                    fact.lastAttemptAt = now;
                    failure(fact, now, normalizeReason(reason));
                    fact.failureMessage = detail;
                }
            }
        }
    }

    /** 现有推送回调只有设备身份：可确认设备有事件，但不能凭空归属到某个点位。 */
    public void recordDeviceEvent(String deviceId, long generation, long collectTime) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation) return;
        synchronized (window) {
            if (!taskGuard.isCurrent(deviceId, generation)) return;
            long now = System.currentTimeMillis();
            long timestamp = normalizedTime(collectTime, now);
            window.lastEventAt = Math.max(window.lastEventAt, timestamp);
        }
    }

    /** 事件仅记录收到点位事实；有效值须由缓存后处理的 recordCachedPoint 确认。 */
    public void recordPointEvent(String deviceId, long generation, String pointId,
                                 boolean succeeded, Integer qualityCode, long collectTime) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation || pointId == null) return;
        synchronized (window) {
            if (!taskGuard.isCurrent(deviceId, generation)) return;
            PointFact fact = window.points.get(pointId);
            if (fact == null || fact.configError != null) return;
            long now = System.currentTimeMillis();
            long timestamp = normalizedTime(collectTime, now);
            if (timestamp < Math.max(fact.lastObservedAt, fact.lastFailureAt)) return;
            fact.event = true;
            fact.lastAttemptAt = timestamp;
            if (succeeded) {
                window.lastEventAt = Math.max(window.lastEventAt, timestamp);
            } else {
                failure(fact, timestamp, "COMM_ERROR");
            }
        }
    }

    /** 外部协议事件可显式上报点位错误，未知类型才降级为通信错误。 */
    public void recordPointFailure(String deviceId, long generation, String pointId, String reason, long at) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation || pointId == null) return;
        synchronized (window) {
            if (!taskGuard.isCurrent(deviceId, generation)) return;
            PointFact fact = window.points.get(pointId);
            if (fact == null || fact.configError != null) return;
            long timestamp = normalizedTime(at, System.currentTimeMillis());
            fact.event = true;
            fact.lastAttemptAt = Math.max(fact.lastAttemptAt, timestamp);
            failure(fact, timestamp, normalizeReason(reason));
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null) return "COMM_ERROR";
        return switch (reason) {
            case "CONFIG_ERROR", "COMM_ERROR", "MAPPING_ERROR", "DECODE_ERROR", "NO_VALUE" -> reason;
            default -> "COMM_ERROR";
        };
    }

    /** 启动失败只能清理自己的代次，不能覆盖已经启动的新窗口。 */
    public void clearIfGeneration(String deviceId, long generation) {
        windows.computeIfPresent(deviceId, (ignored, window) -> window.generation == generation ? null : window);
    }

    /** 仅在后处理已写入实时缓存且点位身份仍匹配时承认一次有效采集。 */
    public void recordCachedPoint(String deviceId, DataPoint point, Long sourceGeneration,
                                  boolean good, Integer qualityCode, long at) {
        Window window = windows.get(deviceId);
        if (window == null || point == null || point.getPointId() == null
                || (sourceGeneration != null && window.generation != sourceGeneration)) return;
        synchronized (window) {
            if (windows.get(deviceId) != window || !taskGuard.isCurrent(deviceId, window.generation)) return;
            PointFact fact = window.points.get(point.getPointId());
            if (fact == null || fact.configError != null || !Objects.equals(fact.pointCode, point.getPointCode())
                    || !Objects.equals(fact.address, point.getAddress())
                    || !Objects.equals(fact.dataType, point.getDataType())) return;
            long timestamp = normalizedTime(at, System.currentTimeMillis());
            if (sourceGeneration == null) fact.event = true;
            fact.lastAttemptAt = Math.max(fact.lastAttemptAt, timestamp);
            if (good) {
                success(window, fact, timestamp, qualityCode);
                if (sourceGeneration == null) window.lastEventAt = Math.max(window.lastEventAt, timestamp);
            } else {
                failure(fact, timestamp, "DECODE_ERROR");
            }
        }
    }

    /** 仅接收当前代次真实协议握手或订阅确认，不能由 TCP/UDP 连接或缓存值推断。 */
    public void recordProtocolReady(String deviceId, long generation) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation) return;
        synchronized (window) {
            if (windows.get(deviceId) == window && taskGuard.isCurrent(deviceId, generation)) {
                window.protocolReadyAt = System.currentTimeMillis();
            }
        }
    }

    /** 同一代次重新连接时，先清除旧会话的握手事实。 */
    public void resetProtocolReady(String deviceId, long generation) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation) return;
        synchronized (window) {
            if (windows.get(deviceId) == window && taskGuard.isCurrent(deviceId, generation)) {
                window.protocolReadyAt = 0L;
                long now = System.currentTimeMillis();
                window.points.values().stream().filter(fact -> fact.lastObservedAt > 0L)
                        .forEach(fact -> failure(fact, now, "COMM_ERROR"));
            }
        }
    }

    /** 停止或删除设备时回收运行态，旧在途回调不能重建它。 */
    public void clear(String deviceId) {
        windows.remove(deviceId);
    }

    public WindowSnapshot snapshot(String deviceId, long generation) {
        Window window = windows.get(deviceId);
        if (window == null || window.generation != generation || !taskGuard.isCurrent(deviceId, generation)) {
            return new WindowSnapshot(generation, 0L, 0L, 0L, Map.of());
        }
        synchronized (window) {
            if (!taskGuard.isCurrent(deviceId, generation)) {
                return new WindowSnapshot(generation, 0L, 0L, 0L, Map.of());
            }
            Map<String, PointFactSnapshot> copy = new LinkedHashMap<>();
            window.points.forEach((id, fact) -> copy.put(id, new PointFactSnapshot(
                    fact.polling, fact.event, fact.lastAttemptAt, fact.lastObservedAt, fact.lastFailureAt,
                    fact.consecutiveFailures, fact.failureReason, fact.qualityCode,
                    fact.configError != null ? fact.configError : fact.failureMessage)));
            return new WindowSnapshot(generation, window.firstValueAt, window.lastEventAt,
                    window.protocolReadyAt, Map.copyOf(copy));
        }
    }

    private void success(Window window, PointFact fact, long timestamp, Integer qualityCode) {
        if (timestamp < Math.max(fact.lastObservedAt, fact.lastFailureAt)) return;
        fact.lastObservedAt = timestamp;
        fact.lastAttemptAt = Math.max(fact.lastAttemptAt, timestamp);
        fact.consecutiveFailures = 0;
        fact.failureReason = null;
        fact.failureMessage = null;
        fact.qualityCode = qualityCode;
        window.firstValueAt = window.firstValueAt == 0L ? timestamp : Math.min(window.firstValueAt, timestamp);
    }

    private void failure(PointFact fact, long timestamp, String reason) {
        if (timestamp < Math.max(fact.lastObservedAt, fact.lastFailureAt)) return;
        fact.lastFailureAt = timestamp;
        fact.consecutiveFailures++;
        fact.failureReason = reason;
        fact.failureMessage = null;
    }

    private long normalizedTime(long eventTime, long now) {
        return eventTime > 0L && eventTime <= now ? eventTime : now;
    }

    public record WindowSnapshot(long generation, long firstValueAt, long lastEventAt,
                                 long protocolReadyAt, Map<String, PointFactSnapshot> points) {
    }

    public record PointFactSnapshot(boolean polling, boolean event, long lastAttemptAt, long lastObservedAt,
                                    long lastFailureAt, int consecutiveFailures,
                                    String failureReason, Integer qualityCode, String errorMessage) {
    }

    private static final class Window {
        private final long generation;
        private final Map<String, PointFact> points = new LinkedHashMap<>();
        private long firstValueAt;
        private long lastEventAt;
        private long protocolReadyAt;

        private Window(long generation) {
            this.generation = generation;
        }
    }

    private static final class PointFact {
        private final String pointCode;
        private final String address;
        private final String dataType;
        private boolean polling;
        private boolean event;
        private long lastAttemptAt;
        private long lastObservedAt;
        private long lastFailureAt;
        private int consecutiveFailures;
        private String failureReason;
        private Integer qualityCode;
        private String configError;
        private String failureMessage;

        private PointFact(String pointCode, String address, String dataType) {
            this.pointCode = pointCode;
            this.address = address;
            this.dataType = dataType;
        }
    }
}
