package com.wangbin.collector.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.Application;
import com.wangbin.collector.common.config.RedisConfig;
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
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import org.springframework.test.context.junit.jupiter.SpringExtension;
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
import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
@ExtendWith(Phase06ContextCloseExtension.class)
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
        verifyRedisConfigContextClose();
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

    private void verifyRedisConfigContextClose() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                "127.0.0.1", requiredInteger(REDIS_PORT_PROPERTY));
        Set<Long> existingListenerThreadIds = liveThreadIds("redis-listener-");
        Set<Long> threadIdsBeforeClose = Set.of();
        ThreadPoolTaskExecutor listenerExecutor = null;
        try {
            context.registerBean(LettuceConnectionFactory.class, () -> connectionFactory);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(RedisConfig.class);
            context.refresh();
            listenerExecutor = context.getBean("redisListenerTaskExecutor", ThreadPoolTaskExecutor.class);

            String topic = REDIS_NAMESPACE + ":context-close";
            CountDownLatch received = new CountDownLatch(1);
            RedisMessageListenerContainer container = context.getBean(RedisMessageListenerContainer.class);
            container.addMessageListener((message, pattern) -> received.countDown(), new ChannelTopic(topic));
            container.start();
            awaitCondition("独立 Redis listener container 启动", container::isRunning);
            context.getBean(StringRedisTemplate.class).convertAndSend(topic, "phase6-context-close");
            assertTrue(received.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "独立 Redis listener 必须收到真实 Pub/Sub 消息");
            threadIdsBeforeClose = liveThreadIds("redis-listener-").stream()
                    .filter(threadId -> !existingListenerThreadIds.contains(threadId))
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(!threadIdsBeforeClose.isEmpty(), "独立 Context 必须创建 Redis listener 线程");
        } finally {
            context.close();
        }
        assertNotNull(listenerExecutor, "独立 Context 必须装配 listener executor");
        assertTrue(listenerExecutor.getThreadPoolExecutor().isShutdown(),
                "独立 Context 关闭后 listener executor 必须 shutdown");
        assertFalse(connectionFactory.isRunning(),
                "独立 Context 关闭后 Lettuce factory 必须停止");
        Set<Long> observedThreadIds = threadIdsBeforeClose;
        awaitCondition("独立 Spring Context 关闭后 Redis listener 线程释放",
                () -> observedThreadIds.stream()
                        .noneMatch(Phase06RealRedisModbusHttpIntegrationTest::isLiveThread));
        System.out.printf("PHASE6_CONTEXT_CLOSE_STANDALONE threadIdsBefore=%s threadNamesAfter=%s%n",
                threadIdsBeforeClose, liveThreadNames("redis-listener-"));
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

/**
 * 在 Spring TestContext 完成 afterAll 回调后关闭真实业务 Context，避免测试框架在关闭后再次读取已失效 Context。
 */
final class Phase06ContextCloseExtension implements AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(Phase06ContextCloseExtension.class);

    @Override
    public void afterAll(ExtensionContext context) {
        ConfigurableApplicationContext applicationContext =
                (ConfigurableApplicationContext) SpringExtension.getApplicationContext(context);
        context.getStore(NAMESPACE).put("business-context-close", new BusinessContextCloseResource(applicationContext));
    }

    private static final class BusinessContextCloseResource implements ExtensionContext.Store.CloseableResource {
        private final ConfigurableApplicationContext applicationContext;

        private BusinessContextCloseResource(ConfigurableApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        @Override
        public void close() {
            ThreadPoolTaskExecutor listenerExecutor = applicationContext
                    .getBean("redisListenerTaskExecutor", ThreadPoolTaskExecutor.class);
            RedisMessageListenerContainer listenerContainer = applicationContext
                    .getBean(RedisMessageListenerContainer.class);
            LettuceConnectionFactory connectionFactory = applicationContext
                    .getBean(LettuceConnectionFactory.class);
            StreamWriteBuffer streamBuffer = applicationContext.getBean(StreamWriteBuffer.class);
            RedisTelemetryIngressBuffer ingressBuffer = applicationContext.getBean(RedisTelemetryIngressBuffer.class);
            HikariDataSource sqliteDataSource = applicationContext
                    .getBean("wangbinSqliteDataSource", HikariDataSource.class);
            Executor streamWriteExecutor = applicationContext.getBean(TelemetryExecutorNames.STREAM_WRITE, Executor.class);

            applicationContext.close();

            assertFalse(applicationContext.isActive(), "业务 Web Context 必须已关闭");
            assertFalse(listenerContainer.isRunning(), "Redis listener container 必须已停止");
            assertTrue(listenerExecutor.getThreadPoolExecutor().isShutdown(),
                    "业务 Context 关闭后 Redis listener executor 必须 shutdown");
            assertTrue(listenerExecutor.getThreadPoolExecutor().isTerminated(),
                    "业务 Context 关闭后 Redis listener executor 必须 terminated");
            assertFalse(connectionFactory.isRunning(), "业务 Context 关闭后 Lettuce factory 必须停止");
            assertFalse(streamBuffer.isRunning(), "StreamWriteBuffer 必须停止");
            assertTrue(sqliteDataSource.isClosed(), "SQLite HikariDataSource 必须 closed");
            assertTrue(streamWriteExecutor instanceof ThreadPoolTaskExecutor,
                    "Stream writer 必须使用可关联的 Spring 执行器");
            ThreadPoolTaskExecutor streamExecutor = (ThreadPoolTaskExecutor) streamWriteExecutor;
            assertTrue(streamExecutor.getThreadPoolExecutor().isTerminated(),
                    "Stream writer 执行器必须 terminated");
            System.out.printf("PHASE6_CONTEXT_CLOSE businessContextActive=%s listenerRunning=%s "
                            + "listenerExecutorTerminated=%s lettuceRunning=%s streamRunning=%s "
                            + "ingressMetrics=%s sqliteClosed=%s streamExecutorTerminated=%s%n",
                    applicationContext.isActive(), listenerContainer.isRunning(),
                    listenerExecutor.getThreadPoolExecutor().isTerminated(), connectionFactory.isRunning(),
                    streamBuffer.isRunning(), ingressBuffer.metrics(), sqliteDataSource.isClosed(),
                    streamExecutor.getThreadPoolExecutor().isTerminated());
        }
    }
}
