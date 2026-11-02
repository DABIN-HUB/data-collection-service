package com.wangbin.collector.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.Application;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.cache.config.CacheProperties;
import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.ingress.RedisTelemetryIngressBuffer;
import com.wangbin.collector.core.cache.manager.RedisCacheManager;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.service.StreamWriteBuffer;
import com.wangbin.collector.core.cache.service.TelemetryStreamServiceImpl;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.junit.jupiter.api.TestInstance;
import com.zaxxer.hikari.HikariDataSource;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 06 真实 Redis、Modbus TCP loopback、SQLite 配置、正式 HTTP 启停和实时查询闭环。
 *
 * <p>Redis 地址、SQLite 路径和空文件路径由隔离运行命令通过 JVM 属性注入，
 * 不读取或覆盖受保护的 application.yml。</p>
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=",
                "spring.jmx.enabled=false",
                "spring.main.banner-mode=off",
                "collector.config.loader=file",
                "collector.cache.type=multi-level",
                "collector.cache.redis.default-expire=60",
                "collector.cache.redis.connection-timeout=1000",
                "collector.auth.allow-ip-authentication=true",
                "collector.auth.ip-allow-list=127.0.0.1",
                "collector.report.enabled=false",
                "collector.report.mqtt.enabled=false",
                "collector.cloud.enabled=false",
                "spring.data.redis.password=",
                "spring.data.redis.database=15",
                "spring.data.redis.timeout=1000ms",
                "spring.data.redis.lettuce.shutdown-timeout=100ms"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "phase6.redis.port", matches = "\\d+")
class Phase06RealRedisModbusHttpIntegrationTest {

    private static final String DEVICE_A = "phase6-real-a";
    private static final String DEVICE_B = "phase6-real-b";
    private static final String POINT_A = "phase6-point-a";
    private static final String POINT_B = "phase6-point-b";
    private static final String REDIS_PORT_PROPERTY = "phase6.redis.port";
    private static final String SQLITE_PATH_PROPERTY = "phase6.sqlite.path";
    private static final String EMPTY_CONFIG_PROPERTY = "phase6.empty.config";
    private static final String REDIS_CONTAINER = "wangbin-phase06-real-integration";

    private static final String REDIS_NAMESPACE = "phase06:real:" + UUID.randomUUID().toString().replace("-", "");
    private static final long WAIT_TIMEOUT_SECONDS = 20L;

    private static LoopbackModbusServer serverA;
    private static LoopbackModbusServer serverB;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private CacheProperties cacheProperties;

    @Autowired
    private RedisCacheManager redisCacheManager;

    @Autowired
    private TelemetryStreamProperties streamProperties;

    @Autowired
    private TelemetryStreamServiceImpl streamService;

    @Autowired
    private MultiLevelCacheManager multiLevelCacheManager;

    @Autowired
    private RedisMessageListenerContainer redisMessageListenerContainer;


    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CollectionTaskGuard collectionTaskGuard;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpClient httpClient;
    private URI baseUri;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> requiredInteger(REDIS_PORT_PROPERTY));
        registry.add("telemetry.tdengine.enabled", () -> Boolean.parseBoolean(
                System.getProperty("phase6.tdengine.enabled", "false")));
        registry.add("spring.data.redis.stream.key", () -> REDIS_NAMESPACE + ":stream");
        registry.add("collector.cache.redis.key-prefix", () -> REDIS_NAMESPACE + ":cache:");
        registry.add("wangbin.config.sqlite.database-path", () -> requiredProperty(SQLITE_PATH_PROPERTY));
        registry.add("collector.config.file.devices", () -> requiredProperty(EMPTY_CONFIG_PROPERTY));
        registry.add("collector.config.local-device-snapshot-file", () -> requiredProperty(EMPTY_CONFIG_PROPERTY));
    }

    @BeforeAll
    void startLoopbackServers() throws Exception {
        serverA = new LoopbackModbusServer(101);
        serverB = new LoopbackModbusServer(303);
        serverA.start();
        serverB.start();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        baseUri = URI.create("http://127.0.0.1:" + serverPort);
        saveDevice(configuration(DEVICE_A, POINT_A, serverA.port()));
        saveDevice(configuration(DEVICE_B, POINT_B, serverB.port()));
    }

    @AfterAll
    void stopLoopbackServers() throws Exception {
        stopIfRunning(DEVICE_A);
        stopIfRunning(DEVICE_B);
        if (serverA != null) serverA.close();
        if (serverB != null) serverB.close();
    }

    @Test
    void shouldReadAThenBThroughRealModbusRedisStreamAndHttpWithGenerationIsolation() throws Exception {
        long firstGeneration;
        long firstCollectedAt;

        post("/api/device/" + DEVICE_A + "/start");
        JsonNode firstA = awaitPoint(DEVICE_A, POINT_A, 101);
        firstCollectedAt = firstA.path("data").path("lastSuccessfulCollectionAt").asLong();
        firstGeneration = awaitRuntimeGeneration(DEVICE_A);
        assertTrue(firstCollectedAt > 0L);
        assertEquals(100, firstA.path("data").path("quality").asInt());

        assertTrue(redisValue(DEVICE_A, POINT_A).isPresent());
        assertEquals(101, redisNumericValue(DEVICE_A, POINT_A));
        assertTrue(redisTtlMillis(DEVICE_A, POINT_A) > 0L);
        assertEquals(0L, serverB.readCount(), "B 在正式 START 前不得产生 Modbus 读取");

        post("/api/device/" + DEVICE_B + "/start");
        JsonNode firstB = awaitPoint(DEVICE_B, POINT_B, 303);
        assertEquals(303, firstB.path("data").path("value").asInt());
        assertEquals(100, firstB.path("data").path("quality").asInt());
        assertTrue(serverB.readCount() > 0L);
        assertEquals(101, redisNumericValue(DEVICE_A, POINT_A));
        assertEquals(303, redisNumericValue(DEVICE_B, POINT_B));

        AtomicReferenceValue<List<MapRecord<String, Object, Object>>> streamRowsRef = new AtomicReferenceValue<>();
        awaitCondition("Redis Stream 同时包含 A/B 真实采集结果", () -> {
            List<MapRecord<String, Object, Object>> rows = stringRedisTemplate.opsForStream()
                    .range(streamProperties.getKey(), Range.unbounded());
            streamRowsRef.set(rows);
            return rows.stream().anyMatch(row -> streamContainsValue(row, DEVICE_A, POINT_A, 101))
                    && rows.stream().anyMatch(row -> streamContainsValue(row, DEVICE_B, POINT_B, 303));
        });
        List<MapRecord<String, Object, Object>> streamRows = streamRowsRef.get();

        post("/api/device/" + DEVICE_A + "/stop");
        awaitRuntimeStopped(DEVICE_A);
        serverA.setValue(202);
        post("/api/device/" + DEVICE_A + "/start");

        JsonNode secondA = awaitPoint(DEVICE_A, POINT_A, 202);
        long secondGeneration = awaitRuntimeGeneration(DEVICE_A);
        long secondCollectedAt = secondA.path("data").path("lastSuccessfulCollectionAt").asLong();
        assertTrue(secondGeneration > firstGeneration,
                "重新 START 必须取得新的 generation");
        assertTrue(secondCollectedAt > firstCollectedAt,
                "新 generation 的采集时间必须晚于旧 generation");
        assertEquals(100, secondA.path("data").path("quality").asInt());
        assertEquals(202, redisNumericValue(DEVICE_A, POINT_A));
        assertTrue(redisTtlMillis(DEVICE_A, POINT_A) > 0L);
        assertTrue(!collectionTaskGuard.isCurrent(DEVICE_A, firstGeneration),
                "STOP 后旧 generation 必须失效");

        JsonNode bAfterRestart = get("/api/data/device/" + DEVICE_B + "/point/" + POINT_B);
        assertEquals("success", bAfterRestart.path("status").asText());
        assertEquals(303, bAfterRestart.path("data").path("value").asInt());
        assertEquals(100, bAfterRestart.path("data").path("quality").asInt());

        long readsBeforeRedisOutage = serverA.readCount();
        stopIsolatedRedis();
        JsonNode unhealthyHealth = awaitCacheHealth("UNHEALTHY", "DEGRADED");
        configManager.loadLocalStartConfiguration(DEVICE_A);
        serverA.setValue(404);
        JsonNode localFallback = awaitPoint(DEVICE_A, POINT_A, 404);
        assertTrue(serverA.readCount() > readsBeforeRedisOutage,
                "Redis 不可用时本地协议采集仍必须继续");

        long recoveryStart = System.nanoTime();
        startIsolatedRedis();
        awaitCondition("同一业务 JVM 恢复 Redis PING", this::redisPing);
        serverA.setValue(505);
        JsonNode recoveredHttp = awaitPoint(DEVICE_A, POINT_A, 505);
        awaitCondition("恢复后 Redis 直接读回最新值", () -> redisNumericValue(DEVICE_A, POINT_A) == 505);
        JsonNode healthyHealth = awaitCacheHealth("HEALTHY", "HEALTHY");
        long recoveryMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recoveryStart);
        assertEquals(505, redisNumericValue(DEVICE_A, POINT_A));

        System.out.printf("PHASE6_REAL_MODBUS_REDIS_HTTP namespace=%s aFirstGeneration=%d aFirstHttp=%s "
                        + "aSecondGeneration=%d aSecondHttp=%s bHttp=%s aReads=%d bReads=%d streamRows=%d "
                        + "redisKeyA=%s redisTtlMillisA=%d streamKey=%s streamIds=%s "
                        + "redisOutageHealth=%s localFallbackHttp=%s recoveredHealth=%s recoveredHttp=%s "
                        + "recoveryMillis=%d redisStats=%s%n",
                REDIS_NAMESPACE, firstGeneration, firstA,
                secondGeneration, secondA, bAfterRestart,
                serverA.readCount(), serverB.readCount(), streamRows.size(),
                cacheProperties.getRedis().getKeyPrefix() + CacheKey.dataKey(DEVICE_A, POINT_A).getFullKey(),
                redisTtlMillis(DEVICE_A, POINT_A), streamProperties.getKey(),
                streamRows.stream().map(row -> String.valueOf(row.getId())).toList(),
                unhealthyHealth, localFallback, healthyHealth, recoveredHttp,
                recoveryMillis, multiLevelCacheManager.getStatistics());

        String lifecycleTopic = REDIS_NAMESPACE + ":lifecycle";
        redisMessageListenerContainer.addMessageListener((message, pattern) -> {
        }, new ChannelTopic(lifecycleTopic));
        redisMessageListenerContainer.start();
        awaitCondition("Redis listener container 启动", redisMessageListenerContainer::isRunning);
        stringRedisTemplate.convertAndSend(lifecycleTopic, "phase6-listener-probe");
        Set<String> listenerThreadsBeforeClose = liveThreadNames("redis-listener-");
        awaitCondition("Redis listener executor 创建线程",
                () -> !liveThreadNames("redis-listener-").isEmpty());
        listenerThreadsBeforeClose = liveThreadNames("redis-listener-");
        assertTrue(!listenerThreadsBeforeClose.isEmpty(), "必须实际创建 Redis listener executor 线程");
        verifyOwnedBusinessContextCloseMatrix();
        System.out.printf("PHASE6_CONTEXT_CLOSE listenerThreadsBefore=%s "
                        + "streamShutdownDroppedRows=%d streamWriterLoopFailures=%d%n",
                listenerThreadsBeforeClose,
                streamService.metrics().shutdownDroppedRows(), streamService.metrics().writerLoopFailures());
    }


    private JsonNode awaitPoint(String deviceId, String pointId, int expectedValue) throws Exception {
        AtomicReferenceValue<JsonNode> last = new AtomicReferenceValue<>();
        awaitCondition("HTTP 实时值达到 " + expectedValue, () -> {
            JsonNode response = get("/api/data/device/" + deviceId + "/point/" + pointId);
            last.set(response);
            return "success".equals(response.path("status").asText())
                    && response.path("data").path("value").asInt(Integer.MIN_VALUE) == expectedValue
                    && response.path("data").path("quality").asInt(Integer.MIN_VALUE) == 100
                    && response.path("data").path("lastSuccessfulCollectionAt").asLong(0L) > 0L;
        });
        return last.get();
    }

    private long awaitRuntimeGeneration(String deviceId) throws Exception {
        AtomicLong generation = new AtomicLong();
        awaitCondition("运行代次生成 " + deviceId, () -> {
            JsonNode response = get("/api/device/" + deviceId + "/runtime");
            generation.set(response.path("data").path("generation").asLong(0L));
            return generation.get() > 0L;
        });
        return generation.get();
    }

    private void awaitRuntimeStopped(String deviceId) throws Exception {
        awaitCondition("设备停止 " + deviceId, () -> {
            JsonNode response = get("/api/device/" + deviceId + "/runtime");
            return !response.path("data").path("running").asBoolean(true)
                    && !response.path("data").path("starting").asBoolean(true);
        });
    }

    private void awaitStreamRows(long expectedRows) throws Exception {
        awaitCondition("Redis Stream XADD 行数达到 " + expectedRows,
                () -> streamService.metrics().redisXaddRows() >= expectedRows);
    }

    private JsonNode awaitCacheHealth(String redisStatus, String overallStatus) throws Exception {
        AtomicReferenceValue<JsonNode> last = new AtomicReferenceValue<>();
        awaitCondition("缓存健康状态 Redis=" + redisStatus + ", overall=" + overallStatus, () -> {
            JsonNode response = get("/api/cache/health");
            last.set(response);
            boolean redisMatches = false;
            for (JsonNode level : response.path("levels")) {
                if ("REDIS".equals(level.path("type").asText())) {
                    redisMatches = redisStatus.equals(level.path("status").asText());
                }
            }
            return redisMatches && overallStatus.equals(response.path("overallStatus").asText());
        });
        return last.get();
    }

    private boolean redisPing() {
        try {
            return "PONG".equals(redisCacheManager.getRedisTemplate().getConnectionFactory()
                    .getConnection().ping());
        } catch (Exception exception) {
            return false;
        }
    }

    private void stopIsolatedRedis() throws Exception {
        runDocker("stop", "--time", "1", REDIS_CONTAINER);
    }

    private void startIsolatedRedis() throws Exception {
        runDocker("start", REDIS_CONTAINER);
    }

    private void runDocker(String... arguments) throws Exception {
        Process process = new ProcessBuilder(concat(new String[]{"docker"}, arguments))
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(15L, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("隔离 Redis 容器操作失败: " + arguments[0]);
        }
    }

    private static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private java.util.Optional<ProcessResult> redisValue(String deviceId, String pointId) {
        String key = cacheProperties.getRedis().getKeyPrefix() + CacheKey.dataKey(deviceId, pointId).getFullKey();
        Object value = redisCacheManager.getRedisTemplate().opsForValue().get(key);
        return value instanceof ProcessResult result ? java.util.Optional.of(result) : java.util.Optional.empty();
    }

    private long redisTtlMillis(String deviceId, String pointId) {
        String key = cacheProperties.getRedis().getKeyPrefix() + CacheKey.dataKey(deviceId, pointId).getFullKey();
        Long ttl = redisCacheManager.getRedisTemplate().getExpire(key, TimeUnit.MILLISECONDS);
        return ttl == null ? -1L : ttl;
    }

    private int redisNumericValue(String deviceId, String pointId) {
        Object value = redisValue(deviceId, pointId).orElseThrow().getProcessedValue();
        assertTrue(value instanceof Number, "Redis 实际值必须是数值: " + value);
        return ((Number) value).intValue();
    }

    private boolean streamContainsValue(MapRecord<String, Object, Object> row,
                                        String deviceId, String pointId, int expectedValue) {
        if (!deviceId.equals(String.valueOf(row.getValue().get("deviceId")))
                || !pointId.equals(String.valueOf(row.getValue().get("pointId")))) {
            return false;
        }
        try {
            JsonNode processResult = objectMapper.readTree(String.valueOf(row.getValue().get("processResult")));
            return processResult.path("processedValue").asDouble(Double.NaN) == expectedValue
                    && processResult.path("quality").asInt(Integer.MIN_VALUE) == 100;
        } catch (Exception exception) {
            return false;
        }
    }

    private void saveDevice(DeviceContext context) {
        configManager.saveLocalDeviceConfigWithResult(
                context.getDeviceInfo(), context.copyConnectionConfig(), context.copyDataPoints(), false);
    }

    private DeviceContext configuration(String deviceId, String pointId, int port) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName("阶段06真实 Modbus 设备 " + deviceId);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setConfigSource("local");
        device.setTemporaryConfig(true);
        device.setCollectionInterval(1000);

        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setReadTimeout(1000);
        connection.setTimeout(1000);
        connection.setExtJson(Map.of("slaveId", 1, "byteOrder", "BIG_ENDIAN"));

        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName("阶段06真实保持寄存器 " + pointId);
        point.setDeviceId(deviceId);
        point.setDeviceName(device.getDeviceName());
        point.setAddress("44001");
        point.setDataType("UINT16");
        point.setUnitId(1);
        point.setStatus(1);
        point.setReadWrite("R");
        point.setCacheEnabled(1);
        point.setCacheDuration(60);
        point.setAlarmEnabled(0);
        point.getAdditionalConfig().put("streamEnabled", true);
        return DeviceContext.of(device, connection, List.of(point));
    }

    private JsonNode post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private void stopIfRunning(String deviceId) {
        if (httpClient == null || baseUri == null) return;
        try {
            post("/api/device/" + deviceId + "/stop");
        } catch (Exception ignored) {
            // 测试关闭阶段只处理已创建的隔离设备。
        }
    }

    private static void awaitCondition(String description, CheckedBoolean condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS);
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.get()) return;
            } catch (Throwable failure) {
                lastFailure = failure;
            }
            Thread.sleep(25L);
        }
        AssertionError timeout = new AssertionError("等待条件超时: " + description);
        if (lastFailure != null) timeout.initCause(lastFailure);
        throw timeout;
    }

    private static Set<String> liveThreadNames(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(prefix))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<Long> liveThreadIds(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith(prefix))
                .map(Thread::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void verifyOwnedBusinessContextCloseMatrix() throws Exception {
        verifyOwnedBusinessContextClose(true);
        stopIsolatedRedis();
        try {
            verifyOwnedBusinessContextClose(false);
        } finally {
            startIsolatedRedis();
            awaitCondition("关闭验收后恢复隔离 Redis", this::redisPing);
        }
    }

    /**
     * 使用本测试自己创建并拥有的完整 Web Context，验证正常和 Redis 不可用时的实例级关闭。
     */
    private void verifyOwnedBusinessContextClose(boolean redisAvailable) throws Exception {
        String namespace = REDIS_NAMESPACE + ":owned-close:" + UUID.randomUUID().toString().replace("-", "");
        Path configuredPath = Path.of(requiredProperty(SQLITE_PATH_PROPERTY)).toAbsolutePath();
        Path sqliteParent = configuredPath.getParent().resolve(
                "phase6-owned-close-" + UUID.randomUUID().toString().replace("-", ""));
        Path sqlitePath = sqliteParent.resolve("collector.db");
        Set<Long> listenerIdsBefore = liveThreadIds("redis-listener-");
        ConfigurableApplicationContext context = null;
        RedisMessageListenerContainer listenerContainer = null;
        ThreadPoolTaskExecutor listenerExecutor = null;
        LettuceConnectionFactory connectionFactory = null;
        StreamWriteBuffer streamBuffer = null;
        RedisTelemetryIngressBuffer ingressBuffer = null;
        HikariDataSource sqliteDataSource = null;
        ThreadPoolTaskExecutor streamExecutor = null;
        Set<Long> ownedListenerIds = Set.of();
        HttpClient previousClient = httpClient;
        URI previousBaseUri = baseUri;
        try {
            SpringApplication application = new SpringApplication(Application.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);
            application.setRegisterShutdownHook(false);
            context = application.run(
                    "--server.address=127.0.0.1",
                    "--server.port=0",
                    "--server.servlet.context-path=",
                    "--spring.jmx.enabled=false",
                    "--spring.main.banner-mode=off",
                    "--collector.config.loader=file",
                    "--collector.cache.type=multi-level",
                    "--collector.cache.redis.default-expire=60",
                    "--collector.cache.redis.connection-timeout=1000",
                    "--collector.auth.allow-ip-authentication=true",
                    "--collector.auth.ip-allow-list=127.0.0.1",
                    "--collector.report.enabled=false",
                    "--collector.report.mqtt.enabled=false",
                    "--collector.cloud.enabled=false",
                    "--telemetry.tdengine.enabled=false",
                    "--spring.data.redis.host=127.0.0.1",
                    "--spring.data.redis.port=" + requiredProperty(REDIS_PORT_PROPERTY),
                    "--spring.data.redis.password=",
                    "--spring.data.redis.database=15",
                    "--spring.data.redis.timeout=1000ms",
                    "--spring.data.redis.lettuce.shutdown-timeout=100ms",
                    "--spring.data.redis.stream.key=" + namespace + ":stream",
                    "--collector.cache.redis.key-prefix=" + namespace + ":cache:",
                    "--wangbin.config.sqlite.database-path=" + sqlitePath,
                    "--collector.config.file.devices=" + requiredProperty(EMPTY_CONFIG_PROPERTY),
                    "--collector.config.local-device-snapshot-file=" + requiredProperty(EMPTY_CONFIG_PROPERTY));

            assertTrue(context instanceof ServletWebServerApplicationContext,
                    "关闭验收必须使用真实 Servlet Web Context");
            assertTrue(((ServletWebServerApplicationContext) context).getWebServer().getPort() > 0,
                    "真实 Web Context 必须绑定随机端口");
            listenerContainer = context.getBean(RedisMessageListenerContainer.class);
            listenerExecutor = context.getBean("redisListenerTaskExecutor", ThreadPoolTaskExecutor.class);
            connectionFactory = context.getBean(LettuceConnectionFactory.class);
            streamBuffer = context.getBean(StreamWriteBuffer.class);
            ingressBuffer = context.getBean(RedisTelemetryIngressBuffer.class);
            sqliteDataSource = context.getBean("wangbinSqliteDataSource", HikariDataSource.class);
            Executor streamWriter = context.getBean(TelemetryExecutorNames.STREAM_WRITE, Executor.class);
            assertTrue(streamWriter instanceof ThreadPoolTaskExecutor,
                    "Stream writer 必须使用可关联的 Spring 执行器");
            streamExecutor = (ThreadPoolTaskExecutor) streamWriter;
            assertTrue(streamBuffer.isRunning(), "业务 Context 启动后 StreamWriteBuffer 必须运行");

            if (redisAvailable) {
                String topic = namespace + ":lifecycle";
                CountDownLatch received = new CountDownLatch(1);
                listenerContainer.addMessageListener((message, pattern) -> received.countDown(),
                        new ChannelTopic(topic));
                listenerContainer.start();
                awaitCondition("完整 Web Context 的 Redis listener 启动", listenerContainer::isRunning);
                context.getBean(StringRedisTemplate.class).convertAndSend(topic, "phase6-owned-context-close");
                assertTrue(received.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "完整 Web Context 必须收到真实 Redis Pub/Sub 消息");

                ConfigManager ownedConfigManager = context.getBean(ConfigManager.class);
                ownedConfigManager.saveLocalDeviceConfigWithResult(
                        configuration(DEVICE_A, POINT_A, serverA.port()).getDeviceInfo(),
                        configuration(DEVICE_A, POINT_A, serverA.port()).copyConnectionConfig(),
                        configuration(DEVICE_A, POINT_A, serverA.port()).copyDataPoints(), false);
                ownedConfigManager.saveLocalDeviceConfigWithResult(
                        configuration(DEVICE_B, POINT_B, serverB.port()).getDeviceInfo(),
                        configuration(DEVICE_B, POINT_B, serverB.port()).copyConnectionConfig(),
                        configuration(DEVICE_B, POINT_B, serverB.port()).copyDataPoints(), false);
                int ownedPort = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
                baseUri = URI.create("http://127.0.0.1:" + ownedPort);
                post("/api/device/" + DEVICE_A + "/start");
                awaitPoint(DEVICE_A, POINT_A, 505);
                post("/api/device/" + DEVICE_B + "/start");
                awaitPoint(DEVICE_B, POINT_B, 303);
                post("/api/device/" + DEVICE_A + "/stop");
                awaitRuntimeStopped(DEVICE_A);
                post("/api/device/" + DEVICE_B + "/stop");
                awaitRuntimeStopped(DEVICE_B);
                ConfigurableApplicationContext ownedContext = context;
                RedisCacheManager ownedCache = ownedContext.getBean(RedisCacheManager.class);
                CacheProperties ownedCacheProperties = ownedContext.getBean(CacheProperties.class);
                String cacheKey = ownedCacheProperties.getRedis().getKeyPrefix()
                        + CacheKey.dataKey(DEVICE_B, POINT_B).getFullKey();
                awaitCondition("完整 Web Context 的 Redis Cache 写入", () -> {
                    Object value = ownedCache.getRedisTemplate().opsForValue().get(cacheKey);
                    return value instanceof ProcessResult result && result.getProcessedValue() instanceof Number;
                });
                awaitCondition("完整 Web Context 的 Redis Stream 写入",
                        () -> ownedContext.getBean(StreamWriteBuffer.class).metrics().redisXaddRows() > 0L);
            } else {
                assertFalse(ping(connectionFactory), "Redis 不可用关闭场景不得误报可用");
            }
            ownedListenerIds = liveThreadIds("redis-listener-").stream()
                    .filter(threadId -> !listenerIdsBefore.contains(threadId))
                    .collect(java.util.stream.Collectors.toSet());
        } finally {
            httpClient = previousClient;
            baseUri = previousBaseUri;
            if (context != null) {
                closeOwnedContextWithinTimeout(context);
            }
        }

        assertFalse(context.isActive(), "完整 Web Context 必须已关闭");
        assertFalse(listenerContainer.isRunning(), "RedisMessageListenerContainer 必须已停止");
        assertTrue(listenerExecutor.getThreadPoolExecutor().isShutdown(),
                "Redis listener executor 必须 shutdown");
        awaitCondition("Redis listener executor terminated", listenerExecutor.getThreadPoolExecutor()::isTerminated);
        assertFalse(connectionFactory.isRunning(), "Lettuce RedisConnectionFactory 必须停止");
        assertFalse(streamBuffer.isRunning(), "StreamWriteBuffer 必须停止");
        assertTrue(sqliteDataSource.isClosed(), "SQLite HikariDataSource 必须 closed");
        awaitCondition("Stream writer executor terminated", streamExecutor.getThreadPoolExecutor()::isTerminated);
        Set<Long> observedOwnedListenerIds = ownedListenerIds;
        awaitCondition("本次 Web Context 创建的 listener 线程退出",
                () -> observedOwnedListenerIds.stream()
                        .noneMatch(Phase06RealRedisModbusHttpIntegrationTest::isLiveThread));
        System.out.printf("PHASE6_CONTEXT_CLOSE ownedRedisAvailable=%s listenerIds=%s listenerTerminated=%s "
                        + "lettuceRunning=%s streamRunning=%s streamExecutorTerminated=%s sqliteClosed=%s "
                        + "ingressBeanDestroyedByContext=%s%n",
                redisAvailable, ownedListenerIds, listenerExecutor.getThreadPoolExecutor().isTerminated(),
                connectionFactory.isRunning(), streamBuffer.isRunning(),
                streamExecutor.getThreadPoolExecutor().isTerminated(), sqliteDataSource.isClosed(), ingressBuffer != null);
    }

    private static boolean ping(LettuceConnectionFactory connectionFactory) {
        try {
            return "PONG".equals(connectionFactory.getConnection().ping());
        } catch (Exception exception) {
            return false;
        }
    }

    private static void closeOwnedContextWithinTimeout(ConfigurableApplicationContext context) throws Exception {
        ExecutorService closer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "phase6-context-close");
            thread.setDaemon(true);
            return thread;
        });
        try {
            closer.submit(context::close).get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            closer.shutdownNow();
            closer.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }


    private static boolean isLiveThread(long threadId) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getId() == threadId);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少隔离运行属性: " + name);
        }
        return value;
    }

    private static int requiredInteger(String name) {
        return Integer.parseInt(requiredProperty(name));
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }

    private static final class AtomicReferenceValue<T> {
        private volatile T value;

        T get() {
            return value;
        }

        void set(T value) {
            this.value = value;
        }
    }

    private static final class LoopbackModbusServer implements AutoCloseable {
        private final AtomicInteger value;
        private final AtomicLong readCount = new AtomicLong();
        private final AtomicBoolean running = new AtomicBoolean();
        private final ExecutorService clients = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "phase06-modbus-client");
            thread.setDaemon(true);
            return thread;
        });
        private ServerSocket serverSocket;
        private Thread acceptThread;

        private LoopbackModbusServer(int initialValue) {
            this.value = new AtomicInteger(initialValue);
        }

        void start() throws IOException {
            serverSocket = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "phase06-modbus-accept-" + serverSocket.getLocalPort());
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        long readCount() {
            return readCount.get();
        }

        void setValue(int nextValue) {
            value.set(nextValue);
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    clients.execute(() -> serve(socket));
                } catch (IOException exception) {
                    if (running.get()) throw new IllegalStateException("Modbus loopback accept 失败", exception);
                }
            }
        }

        private void serve(Socket socket) {
            try (Socket ignored = socket;
                 DataInputStream input = new DataInputStream(socket.getInputStream());
                 DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                while (running.get() && !socket.isClosed()) {
                    int transaction = input.readUnsignedShort();
                    int protocol = input.readUnsignedShort();
                    int length = input.readUnsignedShort();
                    int unit = input.readUnsignedByte();
                    byte[] pdu = input.readNBytes(Math.max(0, length - 1));
                    if (pdu.length < 5) continue;
                    int function = pdu[0] & 0xff;
                    int quantity = ((pdu[3] & 0xff) << 8) | (pdu[4] & 0xff);
                    if (function != 3 && function != 4) continue;
                    int byteCount = quantity * 2;
                    ByteArrayOutputStream body = new ByteArrayOutputStream(2 + byteCount);
                    DataOutputStream responsePdu = new DataOutputStream(body);
                    responsePdu.writeByte(function);
                    responsePdu.writeByte(byteCount);
                    for (int index = 0; index < quantity; index++) {
                        responsePdu.writeShort(value.get());
                    }
                    responsePdu.flush();
                    output.writeShort(transaction);
                    output.writeShort(protocol);
                    output.writeShort(body.size() + 1);
                    output.writeByte(unit);
                    output.write(body.toByteArray());
                    output.flush();
                    readCount.incrementAndGet();
                }
            } catch (IOException ignored) {
                // PLC4X 断开连接时由客户端主动关闭 socket。
            }
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            if (serverSocket != null) serverSocket.close();
            if (acceptThread != null) acceptThread.join(2000L);
            clients.shutdownNow();
            clients.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }
}
