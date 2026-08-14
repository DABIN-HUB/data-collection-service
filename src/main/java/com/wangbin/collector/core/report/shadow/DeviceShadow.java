package com.wangbin.collector.core.report.shadow;

import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.Data;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设备影子，保存设备最新属性、期望属性，以及上报/事件判断所需的运行态信息。
 */
@Data
public class DeviceShadow {

    /** 设备 ID。 */
    private final String deviceId;

    /** 当前最新采集值，对应影子文档 state.reported。 */
    private final Map<String, ValueMeta> latest = new ConcurrentHashMap<>();

    /** 平台或接口下发的期望值，对应影子文档 state.desired。 */
    private final Map<String, ValueMeta> desired = new ConcurrentHashMap<>();

    /** reportField 到原始点位信息的映射，用于上报快照时补充 pointId/pointCode/pointName。 */
    private final Map<String, PointInfo> pointInfos = new ConcurrentHashMap<>();

    /** 最近一次成功上报的属性值，用于和当前采集值比较，判断是否触发变化上报。 */
    private final Map<String, Object> lastReportedValues = new ConcurrentHashMap<>();

    /** 每个字段上一次触发变化上报的时间，用于执行 changeMinIntervalMs 限频。 */
    private final Map<String, Long> lastChangeTriggerAt = new ConcurrentHashMap<>();

    /** 每个字段/事件类型上一次触发事件的时间，用于执行 eventMinIntervalMs 限频。 */
    private final Map<String, Long> lastEventTriggerAt = new ConcurrentHashMap<>();

    /** 相同事件签名上一次触发时间，用于避免同类事件在短时间内重复上报。 */
    private final Map<String, Long> eventSignatureTimes = new ConcurrentHashMap<>();

    /** 当前设备影子生成上报消息时使用的递增序号，写入上报 metadata.seq。 */
    private final AtomicLong sequence = new AtomicLong(0);

    /** 影子版本号。reported 或 desired 变化时递增，不保存历史版本内容。 */
    private final AtomicLong version = new AtomicLong(0);

    /** 这个设备影子第一次创建的时间，毫秒时间戳。 */
    private long createdAt;

    /** 最近一次上报链路标记“已上报”的时间，不等于最近一次采集时间。 */
    private volatile long lastReportAt;

    /** 最近一次完成上报的聚合窗口开始时间，0 表示尚未完成过窗口上报。 */
    private volatile long lastWindowStart;

    /** 最近一次完成上报的聚合窗口结束时间，0 表示尚未完成过窗口上报。 */
    private volatile long lastWindowEnd;

    /** 影子文档最后更新时间，reported 或 desired 变化时刷新。 */
    private volatile long updatedAt;

    /**
     * 创建当前组件实例。
     */
    public DeviceShadow(String deviceId) {
        this.deviceId = deviceId;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastReportAt = now;
        this.updatedAt = now;
    }

    /**
     * 更新或刷新业务状态。
     */
    public void update(String field, ValueMeta valueMeta, DataPoint point) {
        if (field == null || field.isBlank() || valueMeta == null) {
            return;
        }
        ValueMeta previous = latest.put(field, valueMeta);
        if (point != null) {
            pointInfos.put(field, new PointInfo(point.getPointId(), point.getPointCode(), point.getPointName()));
        }
        boolean changed = isMetaChanged(previous, valueMeta);
        if (clearDesiredIfSatisfied(field, valueMeta.getValue())) {
            changed = true;
        }
        if (changed) {
            touch();
        }
    }

    /**
     * 查询并返回业务数据。
     */
    public Map<String, ValueMeta> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(latest));
    }

    /**
     * 执行当前业务逻辑。
     */
    public Map<String, ValueMeta> desiredSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(desired));
    }

    /**
     * 执行当前业务逻辑。
     */
    public Map<String, ValueMeta> deltaSnapshot() {
        Map<String, ValueMeta> delta = new LinkedHashMap<>();
        desired.forEach((field, meta) -> {
            if (meta != null && !isDesiredSatisfied(field, meta.getValue())) {
                delta.put(field, meta);
            }
        });
        return Collections.unmodifiableMap(delta);
    }

    /**
     * 查询并返回业务数据。
     */
    public Map<String, PointInfo> snapshotPointInfos() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pointInfos));
    }

    /**
     * 查询并返回业务数据。
     */
    public Map<String, Object> snapshotLastReportedValues() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lastReportedValues));
    }

    public PointInfo getPointInfo(String field) {
        return pointInfos.get(field);
    }

    public boolean isEmpty() {
        return latest.isEmpty();
    }

    /**
     * 执行当前业务逻辑。
     */
    public long nextSeq() {
        return sequence.incrementAndGet();
    }

    /**
     * 执行当前业务逻辑。
     */
    public long currentVersion() {
        return version.get();
    }

    /**
     * 更新或刷新业务状态。
     */
    public void updateDesired(Map<String, Object> values, String source) {
        if (values == null || values.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        String resolvedSource = source == null || source.isBlank() ? "api" : source;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String field = entry.getKey();
            if (field == null || field.isBlank()) {
                continue;
            }
            ValueMeta meta = new ValueMeta(entry.getValue(), now, "DESIRED", resolvedSource);
            ValueMeta previous = desired.put(field, meta);
            if (isMetaChanged(previous, meta)) {
                changed = true;
            }
            if (isDesiredSatisfied(field, entry.getValue()) && desired.remove(field) != null) {
                changed = true;
            }
        }
        if (changed) {
            touch();
        }
    }

    /**
     * 清理或删除业务数据。
     */
    public void clearDesired(Collection<String> fields) {
        boolean changed = false;
        if (fields == null || fields.isEmpty()) {
            changed = !desired.isEmpty();
            desired.clear();
        } else {
            for (String field : fields) {
                if (field != null && desired.remove(field) != null) {
                    changed = true;
                }
            }
        }
        if (changed) {
            touch();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public void restoreReported(String field, ValueMeta valueMeta) {
        if (field != null && !field.isBlank() && valueMeta != null) {
            latest.put(field, valueMeta);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public void restoreDesired(String field, ValueMeta valueMeta) {
        if (field != null && !field.isBlank() && valueMeta != null) {
            desired.put(field, valueMeta);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public void restoreVersion(long version) {
        this.version.set(Math.max(0, version));
    }

    /**
     * 记录或统计业务状态。
     */
    public void markReportedWindowCommitted(long reportTimestamp, long start, long end) {
        this.lastReportAt = reportTimestamp;
        this.lastWindowStart = start;
        this.lastWindowEnd = end;
        touchReportedState();
    }

    public void setLastWindow(long start, long end) {
        this.lastWindowStart = start;
        this.lastWindowEnd = end;
    }

    public Object getLastReportedValue(String field) {
        return lastReportedValues.get(field);
    }

    /**
     * 记录或统计业务状态。
     */
    public void markReportedValues(Map<String, Object> values) {
        if (values == null) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (field != null) {
                Object previous = lastReportedValues.put(field, value);
                if (!valuesEqual(previous, value)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            touchReportedState();
        }
    }

    /**
     * 判断当前全部最新值是否已经完成上报。
     *
     * @return 全部最新值已上报时返回true
     */
    public boolean allLatestValuesReported() {
        if (latest.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, ValueMeta> entry : latest.entrySet()) {
            ValueMeta valueMeta = entry.getValue();
            Object latestValue = valueMeta == null ? null : valueMeta.getValue();
            if (!lastReportedValues.containsKey(entry.getKey())
                    || !valuesEqual(lastReportedValues.get(entry.getKey()), latestValue)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    public void restoreLastReportedValues(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((field, value) -> {
            if (field != null && !field.isBlank()) {
                lastReportedValues.put(field, value);
            }
        });
    }

    /**
     * 执行当前业务逻辑。
     */
    public long latestValueTimestamp() {
        long latestTimestamp = 0L;
        for (ValueMeta meta : latest.values()) {
            if (meta != null) {
                latestTimestamp = Math.max(latestTimestamp, meta.getTimestamp());
            }
        }
        return latestTimestamp;
    }

    public long getLastChangeTriggerAt(String field) {
        return lastChangeTriggerAt.getOrDefault(field, 0L);
    }

    /**
     * 记录或统计业务状态。
     */
    public void markChangeTrigger(String field, long timestamp) {
        if (field != null) {
            lastChangeTriggerAt.put(field, timestamp);
        }
    }

    public long getLastEventTriggerAt(String field) {
        return lastEventTriggerAt.getOrDefault(field, 0L);
    }

    /**
     * 记录或统计业务状态。
     */
    public void markEventTrigger(String field, long timestamp) {
        if (field != null) {
            lastEventTriggerAt.put(field, timestamp);
        }
    }

    public long getLastEventSignatureAt(String signature) {
        return eventSignatureTimes.getOrDefault(signature, 0L);
    }

    /**
     * 记录或统计业务状态。
     */
    public void markEventSignature(String signature, long timestamp) {
        if (signature != null) {
            eventSignatureTimes.put(signature, timestamp);
        }
    }

    /**
     * 清理或删除业务数据。
     */
    private boolean clearDesiredIfSatisfied(String field, Object reportedValue) {
        ValueMeta desiredMeta = desired.get(field);
        if (desiredMeta == null || !valuesEqual(desiredMeta.getValue(), reportedValue)) {
            return false;
        }
        desired.remove(field);
        return true;
    }

    private boolean isDesiredSatisfied(String field, Object desiredValue) {
        ValueMeta reported = latest.get(field);
        return reported != null && valuesEqual(reported.getValue(), desiredValue);
    }

    private boolean isMetaChanged(ValueMeta previous, ValueMeta current) {
        if (previous == null && current == null) {
            return false;
        }
        if (previous == null || current == null) {
            return true;
        }
        return !valuesEqual(previous.getValue(), current.getValue())
                || !Objects.equals(previous.getQuality(), current.getQuality())
                || !Objects.equals(previous.getSource(), current.getSource())
                || !Objects.equals(previous.getMetadata(), current.getMetadata());
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue()) == 0;
        }
        return Objects.equals(left, right);
    }

    /**
     * 解析或转换业务数据。
     */
    private void touch() {
        updatedAt = System.currentTimeMillis();
        version.incrementAndGet();
    }

    /**
     * 解析或转换业务数据。
     */
    private void touchReportedState() {
        updatedAt = System.currentTimeMillis();
        version.incrementAndGet();
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record PointInfo(String pointId, String pointCode, String pointName) {
    }
}
