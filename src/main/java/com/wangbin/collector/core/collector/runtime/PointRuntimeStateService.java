package com.wangbin.collector.core.collector.runtime;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.scheduler.AdaptiveCollectionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 点位自适应采集运行态的唯一管理入口。
 */
@Slf4j
@Service
public class PointRuntimeStateService {

    private final ConcurrentMap<String, PointRuntimeState> states = new ConcurrentHashMap<>();

    /**
     * 处理组件生命周期。
     */
    public void initializeDevice(String deviceId, List<DataPoint> points) {
        removeDevice(deviceId);
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point != null) {
                initialize(deviceId, point);
            }
        }
    }

    /**
     * 处理组件生命周期。
     */
    public PointRuntimeStateSnapshot initialize(String deviceId, DataPoint point) {
        AdaptiveCollectionUtil.normalizeConfiguration(point);
        PointRuntimeState state = new PointRuntimeState(resolveBaseInterval(point));
        states.put(stateKey(deviceId, point.getPointId()), state);
        return state.snapshot();
    }

    /**
     * 执行当前业务逻辑。
     */
    public PointRuntimeStateSnapshot adjust(String deviceId,
                                            DataPoint point,
                                            Object currentValue,
                                            long adjustWindow) {
        if (point == null) {
            throw new IllegalArgumentException("数据点不能为空");
        }
        PointRuntimeState state = states.computeIfAbsent(
                stateKey(deviceId, point.getPointId()), ignored -> new PointRuntimeState(resolveBaseInterval(point)));
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (!AdaptiveCollectionUtil.needAdjust(state.getLastAdjustTime(), adjustWindow)) {
                return state.snapshot();
            }
            double changeRate = AdaptiveCollectionUtil.calculateChangeRate(currentValue, state.getLastValue());
            adjustState(point, state, currentValue, changeRate);
            state.setLastAdjustTime(now);
            return state.snapshot();
        }
    }

    /**
     * 记录或统计业务状态。
     */
    public PointRuntimeStateSnapshot reset(String deviceId, DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("数据点不能为空");
        }
        AdaptiveCollectionUtil.normalizeConfiguration(point);
        PointRuntimeState state = states.computeIfAbsent(
                stateKey(deviceId, point.getPointId()), ignored -> new PointRuntimeState(resolveBaseInterval(point)));
        synchronized (state) {
            state.setCurrentCollectionInterval(resolveBaseInterval(point));
            state.setStableCount(0);
            state.setLastValue(null);
            state.setChangeRate(0D);
            state.setLastAdjustTime(0L);
            return state.snapshot();
        }
    }

    /**
     * 查询并返回业务数据。
     */
    public PointRuntimeStateSnapshot snapshot(String deviceId, DataPoint point) {
        if (point == null) {
            return new PointRuntimeStateSnapshot(0L, 0, null, 0D, 0L);
        }
        PointRuntimeState state = states.get(stateKey(deviceId, point.getPointId()));
        if (state == null) {
            return new PointRuntimeStateSnapshot(resolveBaseInterval(point), 0, null, 0D, 0L);
        }
        synchronized (state) {
            return state.snapshot();
        }
    }

    /**
     * 查询并返回业务数据。
     */
    public Map<String, PointRuntimeStateSnapshot> snapshots(String deviceId) {
        String prefix = String.valueOf(deviceId) + "|";
        Map<String, PointRuntimeStateSnapshot> result = new ConcurrentHashMap<>();
        states.forEach((key, state) -> {
            if (key.startsWith(prefix)) {
                synchronized (state) {
                    result.put(key.substring(prefix.length()), state.snapshot());
                }
            }
        });
        return Map.copyOf(result);
    }

    /**
     * 清理或删除业务数据。
     */
    public void removeDevice(String deviceId) {
        String prefix = String.valueOf(deviceId) + "|";
        states.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 执行当前业务逻辑。
     */
    private void adjustState(DataPoint point,
                             PointRuntimeState state,
                             Object currentValue,
                             double changeRate) {
        long currentInterval = state.getCurrentCollectionInterval();
        long baseInterval = resolveBaseInterval(point);
        long minInterval = point.getMinCollectionInterval();
        long maxInterval = point.getMaxCollectionInterval();
        double threshold = point.getPointChangeThreshold();
        int stableCount = state.getStableCount();
        long newInterval = currentInterval;

        if (changeRate < threshold) {
            stableCount++;
            if (stableCount >= AdaptiveCollectionUtil.DEFAULT_STABLE_THRESHOLD) {
                newInterval = (long) Math.min(currentInterval * 1.5D, maxInterval);
                stableCount = 0;
            }
        } else {
            newInterval = (long) Math.max(currentInterval * 0.8D, minInterval);
            stableCount = 0;
        }
        if (Math.abs(newInterval - baseInterval) > baseInterval * 2) {
            newInterval = (long) (baseInterval + (newInterval - baseInterval) * 0.5D);
        }

        state.setLastValue(currentValue);
        state.setChangeRate(changeRate);
        state.setStableCount(stableCount);
        state.setCurrentCollectionInterval(newInterval);
        log.debug("点位自适应运行态已更新: 点位={}, interval={}ms, changeRate={}",
                point.getPointId(), newInterval, changeRate);
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveBaseInterval(DataPoint point) {
        Long configured = point.getBaseCollectionInterval();
        return configured != null && configured > 0
                ? configured : AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String stateKey(String deviceId, String pointId) {
        return String.valueOf(deviceId) + "|" + String.valueOf(pointId);
    }
}
