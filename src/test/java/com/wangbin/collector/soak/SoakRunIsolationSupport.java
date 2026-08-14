package com.wangbin.collector.soak;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Soak 容量测试的运行隔离辅助逻辑，只服务测试环境，不改变生产默认 Redis key 语义。
 */
final class SoakRunIsolationSupport {

    private static final String REDIS_PREFIX = "collector:soak:";
    private static final String LOCK_FILE = ".real-environment-soak.lock";

    private SoakRunIsolationSupport() {
    }

    static String safeSegment(String value, int maxLength) {
        String normalized = value == null ? "run" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalized.isBlank()) {
            normalized = "run";
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    static RedisNamespace redisNamespace(String scenario, String runId) {
        String namespace = REDIS_PREFIX + safeSegment(scenario, 24) + ':' + safeSegment(runId, 64);
        return new RedisNamespace(
                namespace,
                namespace + ":history:pending:v1",
                namespace + ":history:processing:v1",
                namespace + ":history:dead:v1",
                namespace + ":entry:pending:v1",
                namespace + ":entry:processing:v1",
                namespace + ":entry:dead:v1",
                namespace + ":stream");
    }

    static QuiescenceSnapshot quiescence(Map<String, Long> observed) {
        Map<String, Long> blockers = new LinkedHashMap<>();
        if (observed != null) {
            observed.forEach((name, value) -> {
                long current = value != null ? value : 0L;
                if (current > 0L) {
                    blockers.put(name, current);
                }
            });
        }
        return new QuiescenceSnapshot(blockers.isEmpty(), blockers);
    }

    static void requireQuiescent(QuiescenceSnapshot snapshot) {
        if (snapshot == null || !snapshot.quiescent()) {
            throw new InvalidWarmupException(snapshot);
        }
    }

    static SoakRunLock acquireRunLock(Path root, String runId) throws IOException {
        Files.createDirectories(root);
        Path lockPath = root.resolve(LOCK_FILE);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            lock = null;
        }
        if (lock == null) {
            String owner = readOwner(lockPath);
            channel.close();
            throw new SoakRunLockException("Soak 测试环境已被其他运行占用，owner=" + owner);
        }
        long pid = ProcessHandle.current().pid();
        String owner = "runId=" + runId + ",pid=" + pid + ",startTime=" + Instant.now();
        channel.truncate(0L);
        channel.write(ByteBuffer.wrap(owner.getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
        return new SoakRunLock(lockPath, channel, lock, owner);
    }

    private static String readOwner(Path lockPath) {
        try {
            if (!Files.exists(lockPath)) {
                return "unknown";
            }
            String owner = Files.readString(lockPath, StandardCharsets.UTF_8);
            return owner == null || owner.isBlank() ? "unknown" : owner.trim();
        } catch (IOException exception) {
            return "unknown";
        }
    }

    record RedisNamespace(String namespace,
                          String historyPendingKey,
                          String historyProcessingKey,
                          String historyDeadLetterKey,
                          String entryPendingKey,
                          String entryProcessingKey,
                          String entryDeadLetterKey,
                          String streamKey) {

        List<String> keys() {
            return List.of(historyPendingKey, historyProcessingKey, historyDeadLetterKey,
                    entryPendingKey, entryProcessingKey, entryDeadLetterKey, streamKey);
        }

        boolean owns(String key) {
            return key != null && key.startsWith(namespace + ':');
        }

        boolean ownsAll(Collection<String> keys) {
            return keys != null && keys.stream().allMatch(this::owns);
        }

        Map<String, String> asMap() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("namespace", namespace);
            result.put("historyPendingKey", historyPendingKey);
            result.put("historyProcessingKey", historyProcessingKey);
            result.put("historyDeadLetterKey", historyDeadLetterKey);
            result.put("entryPendingKey", entryPendingKey);
            result.put("entryProcessingKey", entryProcessingKey);
            result.put("entryDeadLetterKey", entryDeadLetterKey);
            result.put("streamKey", streamKey);
            return result;
        }
    }

    record QuiescenceSnapshot(boolean quiescent, Map<String, Long> blockers) {

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("quiescent", quiescent);
            result.put("blockers", blockers);
            return result;
        }
    }

    static final class InvalidWarmupException extends IllegalStateException {

        InvalidWarmupException(QuiescenceSnapshot snapshot) {
            super("INVALID_WARMUP: " + (snapshot != null ? snapshot.blockers() : Map.of()));
        }
    }

    static final class SoakRunLock implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final FileLock lock;
        private final String owner;

        private SoakRunLock(Path path, FileChannel channel, FileLock lock, String owner) {
            this.path = path;
            this.channel = channel;
            this.lock = lock;
            this.owner = owner;
        }

        Path path() {
            return path;
        }

        String owner() {
            return owner;
        }

        @Override
        public void close() throws IOException {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } finally {
                channel.close();
            }
        }
    }

    static final class SoakRunLockException extends IllegalStateException {

        private SoakRunLockException(String message) {
            super(message);
        }
    }
}
