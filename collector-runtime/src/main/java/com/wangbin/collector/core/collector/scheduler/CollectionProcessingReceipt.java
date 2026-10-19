package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单次读取的核心处理回执；运行模块只依赖回执，不反向依赖遥测实现。 */
public final class CollectionProcessingReceipt {
    private final Map<String, CompletableFuture<PointCompletion>> points;
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private boolean sealed;

    public CollectionProcessingReceipt(List<DataPoint> requestedPoints) {
        Map<String, CompletableFuture<PointCompletion>> futures = new LinkedHashMap<>();
        for (DataPoint point : requestedPoints) {
            if (point != null && point.getPointId() != null) {
                futures.putIfAbsent(point.getPointId(), new CompletableFuture<>());
            }
        }
        points = Map.copyOf(futures);
    }

    /** 入口在异步切换之前声明所有权，读取返回后即可准确结算未返回点位。 */
    public synchronized boolean claim(String pointId) {
        return !sealed && !cancelled.get() && points.containsKey(pointId) && claimed.add(pointId);
    }

    public void complete(String pointId, boolean valid, String reason, long sampleAt) {
        CompletableFuture<PointCompletion> future = points.get(pointId);
        if (future != null) future.complete(new PointCompletion(valid, reason, sampleAt));
    }

    public void seal() {
        synchronized (this) {
            sealed = true;
        }
        points.keySet().stream().filter(id -> !claimed.contains(id))
                .forEach(id -> complete(id, false, "NO_VALUE", 0L));
    }

    public void cancel(String reason) {
        synchronized (this) {
            cancelled.set(true);
        }
        // 完成回调可能再次取得设备提交门，不能在回执锁内触发回调。
        points.keySet().forEach(id -> complete(id, false, reason, 0L));
    }

    /** 取消与最终实时副作用线性化；调用者先取得设备提交门，再进入此回执门。 */
    public synchronized boolean commitIfOpen(Runnable action) {
        if (cancelled.get()) return false;
        action.run();
        return true;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public CompletableFuture<Map<String, PointCompletion>> completion() {
        return CompletableFuture.allOf(points.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<String, PointCompletion> result = new LinkedHashMap<>();
            points.forEach((id, future) -> result.put(id, future.getNow(null)));
            return Map.copyOf(result);
        });
    }

    public record PointCompletion(boolean valid, String reason, long sampleAt) {
    }
}
