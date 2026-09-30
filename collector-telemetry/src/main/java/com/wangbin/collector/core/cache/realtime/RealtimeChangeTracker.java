package com.wangbin.collector.core.cache.realtime;

import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时紧凑快照变更跟踪器。
 *
 * <p>仅保存点位语义指纹和最近修订号，不保存完整负载或按客户端维度的历史快照。</p>
 */
@Slf4j
@Component
public class RealtimeChangeTracker {

    private static final long INITIAL_CONFIG_EPOCH = 1L;
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final String snapshotId;
    private final AtomicLong revision = new AtomicLong(0L);
    private final AtomicLong configEpoch = new AtomicLong(INITIAL_CONFIG_EPOCH);
    private final Map<PointKey, PointState> pointStates = new ConcurrentHashMap<>();

    /**
     * 创建当前进程的实时变更跟踪器。
     */
    public RealtimeChangeTracker() {
        this(UUID.randomUUID().toString());
    }

    /**
     * 创建指定快照标识的实时变更跟踪器，供测试固定进程代次使用。
     *
     * @param snapshotId 当前进程快照标识
     */
    public RealtimeChangeTracker(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    /**
     * 记录缓存写入后的点位语义状态。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointId 稳定点位唯一标识
     * @param cacheValue 已成功写入缓存的值
     * @return true 表示实时表格可见语义发生变化
     */
    public boolean record(String deviceId, String pointId, Object cacheValue) {
        PointKey key = new PointKey(deviceId, pointId);
        long fingerprint = semanticFingerprint(cacheValue);
        AtomicBoolean changed = new AtomicBoolean(false);
        pointStates.compute(key, (ignored, previous) -> {
            if (previous != null && previous.fingerprint() == fingerprint) {
                return previous;
            }
            changed.set(true);
            return new PointState(fingerprint, revision.incrementAndGet());
        });
        return changed.get();
    }

    /**
     * 捕获当前服务端游标标记。
     *
     * @return 当前快照游标
     */
    public SnapshotCursor capture() {
        return new SnapshotCursor(snapshotId, configEpoch.get(), revision.get());
    }

    /**
     * 当前进程快照标识。
     *
     * @return 快照标识
     */
    public String snapshotId() {
        return snapshotId;
    }

    /**
     * 当前配置纪元。
     *
     * @return 配置纪元
     */
    public long configEpoch() {
        return configEpoch.get();
    }

    /**
     * 当前全局修订号。
     *
     * @return 修订号
     */
    public long currentRevision() {
        return revision.get();
    }

    /**
     * 配置变更后提升纪元并清空点位状态。
     */
    public void invalidateConfiguration() {
        configEpoch.incrementAndGet();
        pointStates.clear();
    }

    /**
     * 安全降级到新的完整同步边界。
     */
    public void invalidateSnapshot() {
        invalidateConfiguration();
    }

    /**
     * 校验客户端游标是否可用于增量查询。
     *
     * @param clientSnapshotId 客户端快照标识
     * @param clientConfigEpoch 客户端配置纪元
     * @param sinceRevision 客户端已应用修订号
     * @return 校验结果
     */
    public CursorValidation validateCursor(String clientSnapshotId, long clientConfigEpoch, long sinceRevision) {
        return validateCursor(capture(), clientSnapshotId, clientConfigEpoch, sinceRevision);
    }

    /**
     * 校验客户端游标是否可用于指定边界内的增量查询。
     *
     * @param boundary 固定快照边界
     * @param clientSnapshotId 客户端快照标识
     * @param clientConfigEpoch 客户端配置纪元
     * @param sinceRevision 客户端已应用修订号
     * @return 校验结果
     */
    public CursorValidation validateCursor(SnapshotCursor boundary,
                                           String clientSnapshotId,
                                           long clientConfigEpoch,
                                           long sinceRevision) {
        if (boundary == null) {
            return CursorValidation.reset("SNAPSHOT_MISMATCH", 0L);
        }
        long upperRevision = boundary.revision();
        if (!boundary.snapshotId().equals(clientSnapshotId)) {
            return CursorValidation.reset("SNAPSHOT_MISMATCH", upperRevision);
        }
        if (clientConfigEpoch != boundary.configEpoch()) {
            return CursorValidation.reset("CONFIG_CHANGED", upperRevision);
        }
        if (sinceRevision < 0 || sinceRevision > upperRevision) {
            return CursorValidation.reset("CURSOR_INVALID", upperRevision);
        }
        return CursorValidation.valid(upperRevision);
    }

    /**
     * 校验固定边界在当前 tracker 中是否仍然有效，并返回失效原因。
     *
     * @param boundary 固定快照边界
     * @return 边界校验结果
     */
    public BoundaryValidation validateBoundary(SnapshotCursor boundary) {
        if (boundary == null) {
            return BoundaryValidation.reset("SNAPSHOT_MISMATCH");
        }
        if (!snapshotId.equals(boundary.snapshotId())) {
            return BoundaryValidation.reset("SNAPSHOT_MISMATCH");
        }
        if (configEpoch.get() != boundary.configEpoch()) {
            return BoundaryValidation.reset("CONFIG_CHANGED");
        }
        return BoundaryValidation.current();
    }

    /**
     * 校验固定边界在当前 tracker 中是否仍然有效。
     *
     * @param boundary 固定快照边界
     * @return true 表示 snapshotId 和 configEpoch 仍然匹配
     */
    public boolean isBoundaryCurrent(SnapshotCursor boundary) {
        if (boundary == null) {
            return false;
        }
        return snapshotId.equals(boundary.snapshotId())
                && configEpoch.get() == boundary.configEpoch();
    }

    /**
     * 查找指定修订区间内发生变化的点位键。
     *
     * @param sinceRevision 起始修订号，不包含
     * @param upperRevision 查询上界修订号，包含
     * @param deviceId 可选设备过滤
     * @param limit 最多返回数量，用于 limit + 1 判断过大增量
     * @return 发生变化的点位键
     */
    public List<PointKey> findChangedKeys(long sinceRevision, long upperRevision, String deviceId, int limit) {
        List<ChangedPoint> changedPoints = new ArrayList<>();
        for (Map.Entry<PointKey, PointState> entry : pointStates.entrySet()) {
            PointKey key = entry.getKey();
            long latestRevision = entry.getValue().latestRevision();
            if (latestRevision > sinceRevision
                    && latestRevision <= upperRevision
                    && (deviceId == null || deviceId.isBlank() || deviceId.equals(key.deviceId()))) {
                changedPoints.add(new ChangedPoint(key, latestRevision));
            }
        }
        changedPoints.sort(Comparator.comparingLong(ChangedPoint::revision)
                .thenComparing(point -> point.key().deviceId())
                .thenComparing(point -> point.key().pointId()));
        int end = Math.min(Math.max(limit, 0), changedPoints.size());
        return changedPoints.subList(0, end).stream().map(ChangedPoint::key).toList();
    }

    /**
     * 当前已跟踪点位数量。
     *
     * @return 点位状态数量
     */
    public int trackedPointCount() {
        return pointStates.size();
    }

    private long semanticFingerprint(Object value) {
        long hash = FNV_OFFSET_BASIS;
        return appendValue(hash, value);
    }

    private long appendValue(long hash, Object value) {
        if (value == null) {
            return appendToken(hash, "null");
        }
        if (value instanceof ProcessResult processResult) {
            long resultHash = appendToken(hash, "ProcessResult");
            resultHash = appendValue(resultHash, processResult.getFinalValue());
            resultHash = appendValue(resultHash, processResult.getQuality());
            resultHash = appendValue(resultHash, processResult.getQualityDescription());
            resultHash = appendValue(resultHash, processResult.getQualityLevel());
            resultHash = appendValue(resultHash, processResult.isQualityAcceptable());
            resultHash = appendValue(resultHash, processResult.isSuccess());
            resultHash = appendValue(resultHash, processResult.getProcessingTime());
            return appendValue(resultHash, processResult.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME));
        }
        if (value instanceof Map<?, ?> map) {
            long mapHash = appendToken(hash, "Map:" + map.size());
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            for (Map.Entry<?, ?> entry : entries) {
                mapHash = appendValue(mapHash, entry.getKey());
                mapHash = appendValue(mapHash, entry.getValue());
            }
            return mapHash;
        }
        if (value instanceof Collection<?> collection) {
            long collectionHash = appendToken(hash, "Collection:" + collection.size());
            for (Object item : collection) {
                collectionHash = appendValue(collectionHash, item);
            }
            return collectionHash;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            long arrayHash = appendToken(hash, "Array:" + value.getClass().getComponentType().getName() + ":" + length);
            for (int index = 0; index < length; index += 1) {
                arrayHash = appendValue(arrayHash, Array.get(value, index));
            }
            return arrayHash;
        }
        return appendToken(hash, value.getClass().getName() + ":" + String.valueOf(value));
    }

    private long appendToken(long hash, String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        long next = hash;
        for (byte data : bytes) {
            next ^= data & 0xffL;
            next *= FNV_PRIME;
        }
        next ^= 0xffL;
        next *= FNV_PRIME;
        return next;
    }

    /**
     * 点位身份。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointId 稳定点位唯一标识
     */
    public record PointKey(String deviceId, String pointId) {
    }

    /**
     * 服务端快照游标。
     *
     * @param snapshotId 当前服务进程快照标识
     * @param configEpoch 当前配置纪元
     * @param revision 当前全局修订号
     */
    public record SnapshotCursor(String snapshotId, long configEpoch, long revision) {
    }

    /**
     * 游标校验结果。
     *
     * @param valid 是否可执行增量
     * @param resetReason 需要完整同步的原因
     * @param currentRevision 当前修订号
     */
    public record CursorValidation(boolean valid, String resetReason, long currentRevision) {
        public static CursorValidation valid(long currentRevision) {
            return new CursorValidation(true, null, currentRevision);
        }

        public static CursorValidation reset(String resetReason, long currentRevision) {
            return new CursorValidation(false, resetReason, currentRevision);
        }
    }

    /**
     * 边界校验结果。
     *
     * @param valid 边界是否仍然有效
     * @param resetReason 边界失效时的回退原因
     */
    public record BoundaryValidation(boolean valid, String resetReason) {
        public static BoundaryValidation current() {
            return new BoundaryValidation(true, null);
        }

        public static BoundaryValidation reset(String resetReason) {
            return new BoundaryValidation(false, resetReason);
        }
    }

    private record PointState(long fingerprint, long latestRevision) {
    }

    private record ChangedPoint(PointKey key, long revision) {
    }
}
