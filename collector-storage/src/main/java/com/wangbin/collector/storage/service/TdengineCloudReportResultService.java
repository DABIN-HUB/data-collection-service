package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.report.outbox.CloudReportResult;
import com.wangbin.collector.core.report.outbox.CloudReportResultPage;
import com.wangbin.collector.core.report.outbox.CloudReportResultQuery;
import com.wangbin.collector.core.report.outbox.CloudReportResultRecorder;
import com.wangbin.collector.core.report.outbox.CloudReportResultStatus;
import com.wangbin.collector.storage.config.CloudResultStorageProperties;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.CloudReportResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * TDengine 云上报终态表及 Redis 有界临时补写端口；仅存储非敏感元数据。
 * 查询完整性是全局保守标记：任一已知缺口都使所有查询窗口不完整。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class TdengineCloudReportResultService implements CloudReportResultRecorder, CloudReportResultQuery {

    private static final String TABLE = "cloud_report_results_v1";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_PAGE = 10000;
    private static final long MAX_WINDOW_MS = 31L * 24 * 60 * 60 * 1000;
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final String SAFE_FAILURE = "已达到重试上限";
    private static final Object[] MESSAGE_LOCKS = createMessageLocks();

    /** 原子有界准入并按消息 ID 覆盖待补写的最新终态；不依赖 Redis 键 TTL。 */
    private static final DefaultRedisScript<Long> ENQUEUE = new DefaultRedisScript<>(
            "if redis.call('HEXISTS',KEYS[1],ARGV[1])==0 and redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[3]) then return 0 end; "
                    + "redis.call('HSET',KEYS[1],ARGV[1],ARGV[2]); "
                    + "redis.call('ZADD',KEYS[2],ARGV[4],ARGV[1]); return 1",
            Long.class);
    /** 只删除成功写入时观察到的值，避免清理后来更新的同 ID 状态。 */
    private static final DefaultRedisScript<Long> ACK_PENDING = new DefaultRedisScript<>(
            "if redis.call('HGET',KEYS[1],ARGV[1])~=ARGV[2] then return 0 end; "
                    + "redis.call('HDEL',KEYS[1],ARGV[1]); redis.call('ZREM',KEYS[2],ARGV[1]); "
                    + "redis.call('SREM',KEYS[3],ARGV[1]); return 1",
            Long.class);
    /** 补写队列满时仅保存有界缺口 ID，溢出后保留不可逆的未知缺口标记。 */
    private static final DefaultRedisScript<Long> MARK_MISSING = new DefaultRedisScript<>(
            "if redis.call('SISMEMBER',KEYS[1],ARGV[1])==1 then return 1 end; "
                    + "if redis.call('SCARD',KEYS[1])>=tonumber(ARGV[2]) then "
                    + "redis.call('SET',KEYS[2],'1'); return 2 end; "
                    + "redis.call('SADD',KEYS[1],ARGV[1]); return 1",
            Long.class);
    /** 直写成功时清除同消息的已知缺口，不改变其他消息的缺口。 */
    private static final DefaultRedisScript<Long> CLEAR_MISSING = new DefaultRedisScript<>(
            "return redis.call('SREM',KEYS[1],ARGV[1])", Long.class);

    private final CloudReportResultRepository repository;
    private final TdengineProperties tdengineProperties;
    private final CloudResultStorageProperties resultProperties;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean();
    private final AtomicLong processMissing = new AtomicLong();

    /**
     * 先将非敏感元数据准入 Redis，再写 TDengine；双写失败时仅标记缺口，绝不回抛到 ACK 状态机。
     */
    @Override
    public void record(CloudReportResult result) {
        String id = result == null ? null : result.messageId();
        if (id == null) {
            markMissing(null);
            return;
        }
        // 隔离消息重放后可能获得新终态；同一进程的补写不得用旧终态覆盖新终态。
        synchronized (messageLock(id)) {
            recordLocked(result, id);
        }
    }

    private void recordLocked(CloudReportResult result, String id) {
        try {
            CloudReportResult safe = validateAndSanitize(result);
            String payload = mapper.writeValueAsString(safe);
            boolean queued = false;
            try {
                queued = Long.valueOf(1).equals(redis.execute(ENQUEUE,
                        List.of(payloadKey(), pendingKey()), safe.messageId(), payload,
                        String.valueOf(resultProperties.getCapacity()), String.valueOf(safe.completedAt())));
            } catch (RuntimeException exception) {
                log.warn("云上报结果 Redis 暂存不可用；继续尝试 TDengine，消息={}", safeLogId(safe.messageId()));
            }
            try {
                persist(safe);
                if (queued) {
                    try {
                        clearPending(safe.messageId(), payload);
                    } catch (RuntimeException exception) {
                        log.warn("云上报结果已持久化，但 Redis 待补写标记尚未清理，消息={}", safeLogId(safe.messageId()));
                    }
                } else {
                    try {
                        redis.execute(CLEAR_MISSING, List.of(missingKey()), safe.messageId());
                    } catch (RuntimeException exception) {
                        log.warn("云上报结果缺口标记清理失败，消息={}", safeLogId(safe.messageId()));
                    }
                }
            } catch (RuntimeException | JsonProcessingException exception) {
                log.warn("云上报结果 TDengine 写入不可用，消息={}，暂存={}", safeLogId(safe.messageId()), queued);
                if (!queued) {
                    markMissing(safe.messageId());
                }
            }
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("云上报结果元数据无效或不可序列化，消息={}，已标记缺口", safeLogId(id));
            markMissing(id);
        }
    }

    /**
     * 以完成时间闭区间查询；页码从一开始，默认及硬上限均为五十，窗口不超过三十一天。
     * 数据库不可用时计数为 null；Redis 无法读取时结果虽可见但 complete 为 false。
     */
    @Override
    public CloudReportResultPage query(String deviceId, long startTs, long endTs, int page, int limit) {
        if (startTs <= 0 || endTs < startTs || endTs - startTs > MAX_WINDOW_MS) {
            throw new IllegalArgumentException("云上报结果查询时间范围无效或超过三十一天");
        }
        int safePage = page <= 0 ? 1 : page;
        if (safePage > MAX_PAGE) {
            throw new IllegalArgumentException("云上报结果查询页码超出上限");
        }
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String pattern = deviceId == null || deviceId.isBlank() ? null : devicePattern(deviceId);
        long pending = 0;
        long missing = processMissing.get();
        boolean redisReady = false;
        try {
            Long pendingValue = redis.opsForZSet().zCard(pendingKey());
            Long missingValue = redis.opsForSet().size(missingKey());
            String overflow = redis.opsForValue().get(overflowKey());
            if (pendingValue != null && missingValue != null) {
                pending = pendingValue;
                missing += missingValue + (overflow == null ? 0 : 1);
                redisReady = true;
            }
        } catch (RuntimeException exception) {
            log.warn("云上报结果 Redis 完整性状态不可用");
        }
        try {
            String db = ensureSchema();
            long offset = (long) (safePage - 1) * safeLimit;
            List<CloudReportResult> rows = new ArrayList<>();
            for (Map<String, Object> row : repository.query(db, pattern, startTs, endTs, safeLimit, offset)) {
                rows.add(fromRow(row));
            }
            Long total = repository.count(db, pattern, startTs, endTs, false);
            Long ackCount = repository.count(db, pattern, startTs, endTs, true);
            if (total == null || ackCount == null) {
                throw new IllegalStateException("TDengine 未返回有效计数");
            }
            // 双存储故障时没有跨进程落盘的缺口证据；重启后无法证明过去无丢失，绝不声称历史完整。
            // Redis 当前不可用或有已知待补写/缺口时，累计计数也不能代表全部终态。
            boolean countsKnown = redisReady && pending == 0 && missing == 0;
            return new CloudReportResultPage(rows, countsKnown ? total : null,
                    countsKnown ? ackCount : null, false,
                    redisReady ? pending : null, redisReady ? missing : null);
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("云上报结果 TDengine 查询不可用或记录格式不兼容");
            return new CloudReportResultPage(List.of(), null, null, false,
                    redisReady ? pending : null, redisReady ? missing : null);
        }
    }

    /** 每轮有限补写，失败项留在队首等待下轮；不会重发云消息。 */
    @Scheduled(fixedDelay = 5000L)
    public void replay() {
        try {
            var ids = redis.opsForZSet().range(pendingKey(), 0, resultProperties.getReplayBatchSize() - 1L);
            if (ids == null) {
                return;
            }
            for (String id : ids) {
                synchronized (messageLock(id)) {
                    if (!replayOne(id)) {
                        break;
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("云上报结果 Redis 补写暂不可用");
        }
    }

    private boolean replayOne(String id) {
        try {
            String payload = redis.opsForHash().get(payloadKey(), id) instanceof String value ? value : null;
            if (payload == null) {
                log.warn("云上报结果补写索引缺少正文，消息={}", safeLogId(id));
                markMissing(id);
                return false;
            }
            CloudReportResult item = validateAndSanitize(mapper.readValue(payload, CloudReportResult.class));
            if (!id.equals(item.messageId())) {
                throw new IllegalArgumentException("补写消息身份不一致");
            }
            persist(item);
            clearPending(id, payload);
            return true;
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("云上报结果补写未完成，消息={}", safeLogId(id));
            return false;
        }
    }

    private static Object[] createMessageLocks() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private Object messageLock(String id) {
        return MESSAGE_LOCKS[Math.floorMod(id.hashCode(), MESSAGE_LOCKS.length)];
    }

    private void persist(CloudReportResult result) throws JsonProcessingException {
        String db = ensureSchema();
        String idsJson = mapper.writeValueAsString(result.localDeviceIds());
        List<String> hashes = result.localDeviceIds().stream().map(this::hashDevice).toList();
        repository.insert(db, new Timestamp(result.createdAt()), result.messageId(), idsJson,
                mapper.writeValueAsString(hashes), result.cloudDeviceId(), result.gatewayDeviceId(),
                result.status().name(), result.publishedAt(), result.ackAt(), result.createdAt(),
                result.completedAt(), result.attempts());
    }

    /** 不建数据库、不改 KEEP；已有同名表不符合复合主键和列契约时必须失败关闭。 */
    private String ensureSchema() {
        String db = tdengineProperties.getDatabase();
        if (db == null || !DATABASE_NAME.matcher(db).matches()) {
            throw new IllegalStateException("TDengine 数据库标识符非法");
        }
        if (schemaReady.get()) {
            return db;
        }
        synchronized (schemaReady) {
            if (!schemaReady.get()) {
                if (tdengineProperties.isAutoCreate()) {
                    repository.createTable(db);
                }
                List<Map<String, Object>> ddlRows = repository.showCreateTable(db);
                String ddl = ddlRows == null ? "" : ddlRows.stream()
                        .flatMap(row -> row.values().stream())
                        .filter(String.class::isInstance).map(String.class::cast)
                        .filter(value -> value.toUpperCase(Locale.ROOT).contains("CREATE TABLE"))
                        .findFirst().orElse("").toUpperCase(Locale.ROOT)
                        .replace("`", "").replaceAll("\\s+", " ");
                if (!ddl.contains(TABLE.toUpperCase(Locale.ROOT))
                        || !Pattern.compile("\\(\\s*TS\\s+TIMESTAMP\\s*,").matcher(ddl).find()
                        || !Pattern.compile("MESSAGE_ID\\s+VARCHAR\\s*\\(128\\)\\s+COMPOSITE\\s+KEY").matcher(ddl).find()
                        || !ddl.contains("DEVICE_HASHES_JSON") || !ddl.contains("CREATED_AT")
                        || !ddl.contains("COMPLETED_AT")) {
                    throw new IllegalStateException("云上报结果表 Schema 不兼容，需人工核验");
                }
                schemaReady.set(true);
            }
        }
        return db;
    }

    private CloudReportResult validateAndSanitize(CloudReportResult result) throws JsonProcessingException {
        if (result == null || !validMessageId(result.messageId())
                || result.status() == null || result.createdAt() <= 0 || result.completedAt() <= 0
                || result.attempts() < 0 || result.localDeviceIds().size() > 64
                || !validIdentity(result.cloudDeviceId()) || !validIdentity(result.gatewayDeviceId())) {
            throw new IllegalArgumentException("云上报结果元数据无效");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : result.localDeviceIds()) {
            if (id == null || id.isBlank() || id.length() > 128) {
                throw new IllegalArgumentException("本地设备标识无效");
            }
            ids.add(id);
        }
        if (mapper.writeValueAsString(ids).getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new IllegalArgumentException("设备批次元数据超出容量");
        }
        return new CloudReportResult(result.messageId(), List.copyOf(ids), result.cloudDeviceId(),
                result.gatewayDeviceId(), result.status(), result.publishedAt(), result.ackAt(),
                result.createdAt(), result.completedAt(), result.attempts(),
                result.status() == CloudReportResultStatus.FAILED ? SAFE_FAILURE : null);
    }

    private boolean validIdentity(String value) {
        return value == null || value.length() <= 256;
    }

    private boolean validMessageId(String value) {
        return value != null && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length <= 128
                && value.chars().noneMatch(Character::isISOControl);
    }

    private String devicePattern(String deviceId) {
        if (deviceId.length() > 128) {
            throw new IllegalArgumentException("本地设备标识超出上限");
        }
        // 哈希化后只匹配带 JSON 引号的完整元素，既不模糊匹配原始设备 ID，也避免 LIKE 长度上限。
        return "%\"" + hashDevice(deviceId) + "\"%";
    }

    private String hashDevice(String deviceId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(deviceId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private CloudReportResult fromRow(Map<String, Object> row) throws JsonProcessingException {
        List<String> ids = mapper.readValue(text(row, "local_device_ids_json"), new TypeReference<>() {});
        CloudReportResultStatus status = CloudReportResultStatus.valueOf(text(row, "status"));
        return new CloudReportResult(text(row, "message_id"), ids, text(row, "cloud_device_id"),
                text(row, "gateway_device_id"), status, number(row, "published_at"),
                number(row, "ack_at"), number(row, "created_at"), number(row, "completed_at"),
                Math.toIntExact(number(row, "attempts")),
                status == CloudReportResultStatus.FAILED ? SAFE_FAILURE : null);
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("云上报结果数字字段无效");
        }
        return number.longValue();
    }

    private void clearPending(String id, String payload) {
        redis.execute(ACK_PENDING, List.of(payloadKey(), pendingKey(), missingKey()), id, payload);
    }

    private void markMissing(String id) {
        if (!validMessageId(id)) {
            processMissing.incrementAndGet();
            return;
        }
        try {
            Long marked = redis.execute(MARK_MISSING, List.of(missingKey(), overflowKey()),
                    id, String.valueOf(resultProperties.getCapacity()));
            if (marked == null) {
                processMissing.incrementAndGet();
            }
        } catch (RuntimeException exception) {
            // 双存储均不可用时仅保留本进程缺口；重启后无法证明完整性，运维须另行核对。
            processMissing.incrementAndGet();
            log.warn("云上报结果双存储不可用，缺口仅在本进程可见，消息={}", safeLogId(id));
        }
    }

    private String safeLogId(String id) {
        return validMessageId(id) ? hashDevice(id).substring(0, 12) : "<invalid>";
    }

    private String prefix() {
        return "collector:cloud:result:v1:" + tdengineProperties.getDatabase() + ":";
    }

    private String payloadKey() {
        return prefix() + "payload";
    }

    private String pendingKey() {
        return prefix() + "pending";
    }

    private String missingKey() {
        return prefix() + "missing";
    }

    private String overflowKey() {
        return prefix() + "missing-overflow";
    }
}
