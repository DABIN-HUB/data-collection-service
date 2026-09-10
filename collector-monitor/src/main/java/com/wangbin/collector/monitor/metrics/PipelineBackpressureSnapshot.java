package com.wangbin.collector.monitor.metrics;

import java.util.List;
import java.util.Map;

/**
 * 遥测 pipeline 队列、积压和线程池压力统一快照。
 */
public record PipelineBackpressureSnapshot(PipelineStatus status,
                                           long generatedAt,
                                           IngressSnapshot ingress,
                                           StreamSnapshot stream,
                                           HistorySnapshot history,
                                           CloudSnapshot cloud,
                                           Map<String, ExecutorSnapshot> executors,
                                           List<String> risks) {

    public record IngressSnapshot(boolean enabled,
                                  PipelineStatus status,
                                  long redisPending,
                                  long redisProcessing,
                                  long redisDeadLetter,
                                  int localPending,
                                  int localCapacity,
                                  double localUtilization,
                                  long rejectedTasks,
                                  long rejectedItems,
                                  long redisBufferedItems,
                                  long localBufferedItems,
                                  long droppedItems,
                                  long replayCompletedItems,
                                  long pendingRemoveFailures,
                                  long poisonDeadLetterItems,
                                  long staleSameRuntimeDroppedItems,
                                  long crossRuntimeRecoveredItems,
                                  long legacyEnvelopeRecoveredItems) {
    }

    public record StreamSnapshot(boolean enabled,
                                 PipelineStatus status,
                                 int bufferSize,
                                 int bufferPeak,
                                 int bufferCapacity,
                                 double bufferUtilization,
                                 long admissionAccepted,
                                 long admissionRejected,
                                 long admissionDropped,
                                 long writerBatchCount,
                                 long writerRows,
                                 long redisPipelineCalls,
                                 long redisXaddRows,
                                 long redisXaddFailures,
                                 long shutdownDroppedRows,
                                 long writerLoopFailures) {
    }

    public record HistorySnapshot(boolean enabled,
                                  PipelineStatus status,
                                  long redisPending,
                                  long redisProcessing,
                                  long redisDeadLetter,
                                  int localPending,
                                  int localCapacity,
                                  double localUtilization,
                                  long writeFailureRedisBuffered,
                                  long rejectedRedisBuffered,
                                  long writeFailureLocalBuffered,
                                  long rejectedLocalBuffered,
                                  long writeFailureDropped,
                                  long rejectedDropped,
                                  long replayFailedRows,
                                  int replayProcessingRows,
                                  long batchFallbackDroppedRows,
                                  double liveFlushQueueUtilization) {
    }

    public record CloudSnapshot(boolean enabled,
                                PipelineStatus status,
                                long pending,
                                long isolated,
                                long oldestMessageAgeMillis) {
    }

    public record ExecutorSnapshot(String beanName,
                                   PipelineStatus status,
                                   int corePoolSize,
                                   int maxPoolSize,
                                   int activeCount,
                                   int queueSize,
                                   int queueCapacity,
                                   double queueUtilization,
                                   long completedTaskCount,
                                   long rejectedCount) {
    }
}
