package com.wangbin.collector.soak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakRunIsolationSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void differentSoakRunsMustUseDifferentRedisNamespaces() {
        SoakRunIsolationSupport.RedisNamespace first =
                SoakRunIsolationSupport.redisNamespace("R1", "run-a");
        SoakRunIsolationSupport.RedisNamespace second =
                SoakRunIsolationSupport.redisNamespace("R1", "run-b");

        assertNotEquals(first.namespace(), second.namespace());
        assertFalse(first.owns(second.historyPendingKey()));
        assertFalse(second.owns(first.entryPendingKey()));
    }

    @Test
    void previousRunBacklogMustNotPolluteNextRun() {
        SoakRunIsolationSupport.RedisNamespace previous =
                SoakRunIsolationSupport.redisNamespace("R1", "previous");
        SoakRunIsolationSupport.RedisNamespace current =
                SoakRunIsolationSupport.redisNamespace("R1", "current");

        assertTrue(current.ownsAll(current.keys()));
        assertFalse(current.owns(previous.historyPendingKey()));
        assertFalse(current.owns(previous.entryProcessingKey()));
        assertFalse(current.owns(previous.streamKey()));
    }

    @Test
    void measurementMustNotStartBeforeWarmupQuiescent() {
        SoakRunIsolationSupport.QuiescenceSnapshot snapshot =
                SoakRunIsolationSupport.quiescence(Map.of("history.redisPending", 1L));

        assertFalse(snapshot.quiescent());
        assertThrows(SoakRunIsolationSupport.InvalidWarmupException.class,
                () -> SoakRunIsolationSupport.requireQuiescent(snapshot));
    }

    @Test
    void warmupNotCleanMustAbortMeasurement() {
        SoakRunIsolationSupport.QuiescenceSnapshot snapshot =
                SoakRunIsolationSupport.quiescence(Map.of("telemetryStreamStageExecutor.queue", 3L));

        SoakRunIsolationSupport.InvalidWarmupException exception = assertThrows(
                SoakRunIsolationSupport.InvalidWarmupException.class,
                () -> SoakRunIsolationSupport.requireQuiescent(snapshot));
        assertTrue(exception.getMessage().contains("INVALID_WARMUP"));
        assertTrue(exception.getMessage().contains("telemetryStreamStageExecutor.queue"));
    }

    @Test
    void quiescenceMustIncludeEntryStreamHistoryAndCacheExecutors() {
        SoakRunIsolationSupport.QuiescenceSnapshot snapshot = SoakRunIsolationSupport.quiescence(Map.of(
                "entry.redisPending", 1L,
                "history.redisProcessing", 2L,
                "cacheAsyncExecutor.active", 1L,
                "telemetryStreamStageExecutor.queue", 4L,
                "telemetryHistoryStageExecutor.active", 1L));

        assertFalse(snapshot.quiescent());
        assertTrue(snapshot.blockers().containsKey("entry.redisPending"));
        assertTrue(snapshot.blockers().containsKey("history.redisProcessing"));
        assertTrue(snapshot.blockers().containsKey("cacheAsyncExecutor.active"));
        assertTrue(snapshot.blockers().containsKey("telemetryStreamStageExecutor.queue"));
        assertTrue(snapshot.blockers().containsKey("telemetryHistoryStageExecutor.active"));
    }

    @Test
    void soakRunLockMustRejectConcurrentRun() throws Exception {
        try (SoakRunIsolationSupport.SoakRunLock ignored =
                     SoakRunIsolationSupport.acquireRunLock(tempDir, "owner")) {
            assertThrows(SoakRunIsolationSupport.SoakRunLockException.class,
                    () -> SoakRunIsolationSupport.acquireRunLock(tempDir, "contender"));
        }
    }

    @Test
    void staleRunLockMustBeRecoverable() throws Exception {
        Files.writeString(tempDir.resolve(".real-environment-soak.lock"), "runId=stale,pid=0");

        try (SoakRunIsolationSupport.SoakRunLock lock =
                     SoakRunIsolationSupport.acquireRunLock(tempDir, "new-owner")) {
            assertTrue(lock.owner().contains("new-owner"));
        }
    }

    @Test
    void shutdownMustReleaseOwnedRunLock() throws Exception {
        SoakRunIsolationSupport.SoakRunLock first =
                SoakRunIsolationSupport.acquireRunLock(tempDir, "first");
        first.close();

        assertDoesNotThrow(() -> {
            try (SoakRunIsolationSupport.SoakRunLock ignored =
                         SoakRunIsolationSupport.acquireRunLock(tempDir, "second")) {
                assertTrue(ignored.owner().contains("second"));
            }
        });
    }

    @Test
    void cleanupMustOnlyDeleteCurrentRunNamespace() {
        SoakRunIsolationSupport.RedisNamespace current =
                SoakRunIsolationSupport.redisNamespace("R1", "current");
        SoakRunIsolationSupport.RedisNamespace other =
                SoakRunIsolationSupport.redisNamespace("R1", "other");

        assertTrue(current.ownsAll(current.keys()));
        assertFalse(current.owns(other.historyDeadLetterKey()));
        assertFalse(current.owns("collector:default:history:pending:v1"));
    }
}
