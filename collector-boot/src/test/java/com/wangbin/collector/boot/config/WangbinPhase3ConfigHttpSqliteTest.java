package com.wangbin.collector.boot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.api.application.*;
import com.wangbin.collector.api.controller.ConfigController;
import com.wangbin.collector.api.controller.dto.*;
import com.wangbin.collector.api.exception.ConfigApiExceptionHandler;
import com.wangbin.collector.common.domain.entity.*;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.database.SqliteDatabase;
import com.wangbin.collector.config.sqlite.service.impl.SqliteConfigServiceImpl;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.scheduler.DeviceLifecycleCoordinator;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.loader.FileConfigLoader;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence;
import com.wangbin.collector.core.config.persistence.SqliteConfigService;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实 SQLite 与配置 MVC 路由在隔离目录中联动，不启动现役采集服务。 */
class WangbinPhase3ConfigHttpSqliteTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();
    private final AtomicBoolean failEvent = new AtomicBoolean();

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, SecurityAutoConfiguration.class})
    @Import({ConfigController.class, ConfigApiExceptionHandler.class})
    static class IsolatedHttpApp { }

    @Test
    void actualLoopbackHttpDeliversCommittedReceiptAndStaleRevisionConflict() throws Exception {
        try (var store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000)) {
            ConfigManager manager = manager(store);
            var application = service(manager);
            try (var server = new SpringApplicationBuilder(IsolatedHttpApp.class)
                    .web(WebApplicationType.SERVLET)
                    .properties("server.address=127.0.0.1", "server.port=0", "spring.jmx.enabled=false",
                            "spring.main.banner-mode=off", "spring.config.location=optional:classpath:/stage3-isolated-no-config.yml")
                    .initializers(context -> context.getBeanFactory().registerSingleton("configConsoleApplicationService", application))
                    .run()) {
                int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
                String contextPath = ((ServletWebServerApplicationContext) server).getServletContext().getContextPath();
                URI base = URI.create("http://127.0.0.1:" + port + contextPath + "/api/config/");
                HttpClient client = HttpClient.newHttpClient();
                var initial = configuration("a", "p-a");
                var body = json.writeValueAsString(new LocalDeviceConfigRequest(initial.getDeviceInfo(),
                        initial.copyConnectionConfig(), initial.copyDataPoints(), false, false, null, 0L));
                JsonNode created = http(client, base.resolve("local/devices"), "POST", body, 200);
                long revision = created.path("data").path("persistedRevision").asLong();
                assertEquals(revision, http(client, base.resolve("local/device/a"), "GET", null, 200)
                        .path("data").path("persistedRevision").asLong());
                var edited = manager.getConfigurationDeviceSnapshot("a").context();
                edited.getDeviceInfo().setDeviceName("network-committed");
                body = json.writeValueAsString(new LocalDeviceConfigRequest(edited.getDeviceInfo(),
                        edited.copyConnectionConfig(), edited.copyDataPoints(), true, false, null, revision));
                failEvent.set(true);
                JsonNode committed = http(client, base.resolve("local/device/a"), "PUT", body, 200);
                assertEquals("CONFIG_COMMITTED_APPLY_FAILED", committed.path("machineCode").asText());
                assertTrue(committed.path("data").path("committed").asBoolean());
                assertEquals("network-committed", codec.toRuntime(store.findDevice("a").orElseThrow()
                        .configuration()).getDeviceInfo().getDeviceName());
                failEvent.set(false);
                assertTrue(manager.refreshDeviceConfig("a"));
                JsonNode conflict = http(client, base.resolve("local/device/a"), "PUT", body, 409);
                assertEquals("CONFIG_PERSISTENCE_CONFLICT", conflict.path("machineCode").asText());
                assertFalse(conflict.path("data").path("committed").asBoolean());
                assertEquals(store.findDevice("a").orElseThrow().revision(),
                        http(client, base.resolve("local/device/a"), "GET", null, 200)
                                .path("data").path("persistedRevision").asLong());
            }
        }
    }

    private JsonNode http(HttpClient client, URI uri, String method, String body, int status) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    @Test
    void precommitAndAmbiguousCommitFaultsKeepHttpReceiptHonest() throws Exception {
        Path path = directory.resolve("private/collector.db");
        try (var actual = SqliteConfigurationStore.createNew(path, 1000)) {
            SqliteDatabase backing = SqliteDatabase.openExisting(path, 1000);
            SqliteDatabase injected = mock(SqliteDatabase.class);
            AtomicInteger failCommit = new AtomicInteger();
            AtomicBoolean failBeforeCommit = new AtomicBoolean();
            AtomicBoolean writing = new AtomicBoolean();
            when(injected.openConnection()).thenAnswer(invocation -> commitFault(backing.openConnection(), failCommit, writing));
            doAnswer(invocation -> {
                if (writing.get() && failBeforeCommit.getAndSet(false)) {
                    throw new IllegalStateException("test-only-precommit-failure");
                }
                return null;
            }).when(injected).secureFiles();
            try (var service = new SqliteConfigServiceImpl(injected)) {
                ConfigManager manager = manager(service);
                MockMvc mvc = mvc(manager);
                for (String id : List.of("a", "b")) {
                    var context = configuration(id, "p-" + id);
                    call(mvc, post("/api/config/local/devices").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(new LocalDeviceConfigRequest(context.getDeviceInfo(),
                                    context.copyConnectionConfig(), context.copyDataPoints(), false, false, null, 0L))), 200);
                }
                long initialRevision = actual.findDevice("a").orElseThrow().revision();
                long bRevision = actual.findDevice("b").orElseThrow().revision();
                var edited = manager.getConfigurationDeviceSnapshot("a").context().getDeviceInfo();
                edited.setDeviceName("first-attempt");
                failBeforeCommit.set(true);
                JsonNode before = call(mvc, put("/api/config/device/a").param("baseRevision", String.valueOf(initialRevision))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(edited)), 503);
                assertEquals("CONFIG_PERSISTENCE_UNAVAILABLE", before.path("machineCode").asText());
                assertFalse(before.path("data").path("committed").asBoolean());
                assertEquals(initialRevision, actual.findDevice("a").orElseThrow().revision());

                failCommit.set(1); // JDBC COMMIT 调用直接抛出，数据库回滚，但调用方不能假定已回滚。
                JsonNode unknownRollback = call(mvc, put("/api/config/device/a")
                        .param("baseRevision", String.valueOf(initialRevision)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(edited)), 503);
                assertEquals("CONFIG_COMMIT_UNCONFIRMED", unknownRollback.path("machineCode").asText());
                assertEquals("UNKNOWN", unknownRollback.path("data").path("committed").asText());
                assertEquals(initialRevision, actual.findDevice("a").orElseThrow().revision());
                assertTrue(manager.refreshDeviceConfig("a"));

                failCommit.set(2); // SQLite 实际 COMMIT，模拟 JDBC 确认丢失，绝不可自动补写。
                edited.setDeviceName("committed-without-ack");
                JsonNode unknownCommit = call(mvc, put("/api/config/device/a")
                        .param("baseRevision", String.valueOf(initialRevision)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(edited)), 503);
                assertEquals("CONFIG_COMMIT_UNCONFIRMED", unknownCommit.path("machineCode").asText());
                assertEquals("UNKNOWN", unknownCommit.path("data").path("committed").asText());
                assertTrue(unknownCommit.path("data").path("reloadRequired").asBoolean());
                assertEquals(bRevision + 1, actual.findDevice("a").orElseThrow().revision());
                assertEquals("committed-without-ack", codec.toRuntime(actual.findDevice("a").orElseThrow()
                        .configuration()).getDeviceInfo().getDeviceName());
                assertEquals(bRevision, actual.findDevice("b").orElseThrow().revision());
                assertTrue(manager.refreshDeviceConfig("a"));
                assertEquals("committed-without-ack", manager.getConfigurationDevice("a").getDeviceName());
            }
        }
    }

    private Connection commitFault(Connection real, AtomicInteger fault, AtomicBoolean writing) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) writing.set(false);
                    if (!"createStatement".equals(method.getName())) return invoke(real, method, args);
                    Statement statement = (Statement) invoke(real, method, args);
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                            (wrapped, operation, parameters) -> {
                                if ("execute".equals(operation.getName()) && parameters != null
                                        && parameters.length > 0 && "BEGIN IMMEDIATE".equals(parameters[0])) {
                                    writing.set(true);
                                }
                                if (writing.get() && "execute".equals(operation.getName()) && parameters != null
                                        && parameters.length > 0 && "COMMIT".equals(parameters[0])) {
                                    int mode = fault.getAndSet(0);
                                    if (mode == 1) throw new SQLException("test-only-commit-before-ack");
                                    Object result = invoke(statement, operation, parameters);
                                    if (mode == 2) throw new SQLException("test-only-commit-after-ack");
                                    return result;
                                }
                                return invoke(statement, operation, parameters);
                            });
                });
    }

    private Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Test
    void cacheFaultsAfterCommitReturnCommittedHttpResultsAndIsolateOtherDevices() throws Exception {
        try (var store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000)) {
            ConfigManager manager = manager(store);
            MockMvc mvc = mvc(manager);
            for (String id : List.of("a", "b")) {
                DeviceContext context = configuration(id, "p-" + id);
                call(mvc, post("/api/config/local/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new LocalDeviceConfigRequest(context.getDeviceInfo(),
                                context.copyConnectionConfig(), context.copyDataPoints(), false, false, null, 0L))), 200);
            }
            long bRevision = store.findDevice("b").orElseThrow().revision();
            long aRevision = store.findDevice("a").orElseThrow().revision();
            long globalRevision = store.currentRevision();
            installFailingCache(manager, 1);
            var edit = manager.getConfigurationDeviceSnapshot("a").context().getDeviceInfo();
            edit.setDeviceName("reconciled-after-commit");
            JsonNode reconciled = call(mvc, put("/api/config/device/a")
                    .param("baseRevision", String.valueOf(aRevision)).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(edit)), 200);
            assertEquals("CONFIG_COMMITTED_RECONCILED", reconciled.path("machineCode").asText());
            assertTrue(reconciled.path("data").path("committed").asBoolean());
            assertTrue(reconciled.path("data").path("reconciled").asBoolean());
            long reconciledRevision = store.findDevice("a").orElseThrow().revision();
            assertEquals(globalRevision + 1, reconciledRevision);
            assertEquals("reconciled-after-commit", manager.getConfigurationDevice("a").getDeviceName());

            installFailingCache(manager, 2);
            edit = manager.getConfigurationDeviceSnapshot("a").context().getDeviceInfo();
            edit.setDeviceName("recovery-required");
            JsonNode recovery = call(mvc, put("/api/config/device/a")
                    .param("baseRevision", String.valueOf(reconciledRevision)).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(edit)), 200);
            assertEquals("CONFIG_COMMITTED_RECOVERY_REQUIRED", recovery.path("machineCode").asText());
            assertTrue(recovery.path("data").path("committed").asBoolean());
            assertFalse(recovery.path("data").path("reconciled").asBoolean());
            assertTrue(recovery.path("data").path("reloadRequired").asBoolean());
            assertEquals(reconciledRevision + 1, store.findDevice("a").orElseThrow().revision());
            assertEquals(bRevision, store.findDevice("b").orElseThrow().revision());
            assertEquals("b", call(mvc, get("/api/config/local/device/b"), 200)
                    .path("data").path("bundle").path("device").path("deviceName").asText());
            assertEquals(503, mvc.perform(get("/api/config/local/device/a")).andReturn().getResponse().getStatus());
            assertTrue(manager.refreshDeviceConfig("a"));
            assertEquals("recovery-required", manager.getConfigurationDevice("a").getDeviceName());
        }
    }

    private void installFailingCache(ConfigManager manager, int failures) throws Exception {
        var field = ConfigManager.class.getDeclaredField("deviceContextCache");
        field.setAccessible(true);
        var replacement = new FailingContextMap(failures);
        replacement.putAll((Map<String, DeviceContext>) field.get(manager));
        field.set(manager, replacement);
    }

    private static final class FailingContextMap extends HashMap<String, DeviceContext> {
        private int remaining;
        private FailingContextMap(int remaining) { this.remaining = remaining; }
        @Override public DeviceContext put(String key, DeviceContext value) {
            if ("a".equals(key) && remaining > 0) {
                remaining--;
                throw new IllegalStateException("test-only-cache-failure");
            }
            return super.put(key, value);
        }
    }

    @Test
    void crudCasExternalWriteRestartAndCommitOutcomesAreObservedThroughHttp() throws Exception {
        Path path = directory.resolve("private/collector.db");
        try (var store = SqliteConfigurationStore.createNew(path, 1000)) {
            ConfigManager manager = manager(store);
            MockMvc mvc = mvc(manager);
            DeviceContext initial = configuration("phase3", "p1");
            JsonNode created = call(mvc, post("/api/config/local/devices").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(new LocalDeviceConfigRequest(initial.getDeviceInfo(), initial.copyConnectionConfig(),
                            initial.copyDataPoints(), false, false, null, 0L))), 200);
            long revision = created.path("data").path("persistedRevision").asLong();
            assertEquals(store.findDevice("phase3").orElseThrow().revision(), revision);
            assertEquals(revision, call(mvc, get("/api/config/local/device/phase3"), 200).path("data").path("persistedRevision").asLong());
            JsonNode bundle = call(mvc, get("/api/config/device/phase3/bundle"), 200);
            assertEquals(revision, bundle.path("data").path("persistedRevision").asLong());
            long runtimeVersion = bundle.path("data").path("configVersion").asLong();
            assertNotEquals(revision, runtimeVersion);

            var invalid = manager.getConfigurationDeviceSnapshot("phase3").context().getDeviceInfo();
            invalid.setDeviceName("rejected-before-sql");
            JsonNode rejected = call(mvc, put("/api/config/device/phase3").param("baseRevision", "-1")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(invalid)), 400);
            assertEquals("CONFIG_OPERATION_FAILED", rejected.path("machineCode").asText());
            assertFalse(rejected.path("data").path("committed").asBoolean());
            assertFalse(rejected.path("data").path("reconciled").asBoolean());
            assertFalse(rejected.path("data").path("reloadRequired").asBoolean());
            assertEquals(revision, store.findDevice("phase3").orElseThrow().revision());

            var edited = manager.getConfigurationDeviceSnapshot("phase3").context();
            edited.getDeviceInfo().setDeviceName("winner");
            JsonNode saved = call(mvc, put("/api/config/local/device/phase3").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(new LocalDeviceConfigRequest(edited.getDeviceInfo(), edited.copyConnectionConfig(),
                            edited.copyDataPoints(), true, false, runtimeVersion, revision))), 200);
            long winnerRevision = saved.path("data").path("persistedRevision").asLong();
            assertTrue(winnerRevision > revision);
            edited.getDeviceInfo().setDeviceName("loser");
            JsonNode conflict = call(mvc, put("/api/config/local/device/phase3").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(new LocalDeviceConfigRequest(edited.getDeviceInfo(), edited.copyConnectionConfig(),
                            edited.copyDataPoints(), true, false, runtimeVersion, revision))), 409);
            assertEquals("CONFIG_PERSISTENCE_CONFLICT", conflict.path("machineCode").asText());
            assertFalse(conflict.path("data").path("reconciled").asBoolean());
            assertEquals("winner", manager.getConfigurationDevice("phase3").getDeviceName());
            assertEquals(winnerRevision, store.findDevice("phase3").orElseThrow().revision());

            var external = manager.getConfigurationDeviceSnapshot("phase3").context();
            external.getDeviceInfo().setDeviceName("external");
            try (var other = SqliteConfigurationStore.openExisting(path, 1000)) {
                other.transaction(writer -> writer.saveDevice(codec.fromRuntime(external), winnerRevision));
            }
            long externalRevision = store.findDevice("phase3").orElseThrow().revision();
            JsonNode externalConflict = call(mvc, put("/api/config/device/phase3").param("baseRevision", String.valueOf(winnerRevision))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(edited.getDeviceInfo())), 409);
            assertEquals(externalRevision, externalConflict.path("data").path("currentRevision").asLong());
            assertEquals("external", manager.getConfigurationDevice("phase3").getDeviceName());
            assertEquals(externalRevision, store.currentRevision());

            ConfigManager restarted = manager(store);
            mvc = mvc(restarted);
            JsonNode afterRestart = call(mvc, get("/api/config/device/phase3/bundle"), 200);
            assertEquals(externalRevision, afterRestart.path("data").path("persistedRevision").asLong());
            var connection = restarted.getConfigurationDeviceSnapshot("phase3").context().copyConnectionConfig();
            connection.setPort(2502);
            call(mvc, put("/api/config/device/phase3/connection").param("baseRevision", String.valueOf(externalRevision))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(connection)), 200);
            long connectionRevision = store.findDevice("phase3").orElseThrow().revision();
            var points = restarted.getConfigurationDeviceSnapshot("phase3").context().copyDataPoints();
            points.get(0).setAddress("40002");
            call(mvc, put("/api/config/device/phase3/points").param("baseRevision", String.valueOf(connectionRevision))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(points)), 200);
            long pointsRevision = store.findDevice("phase3").orElseThrow().revision();
            assertEquals(2502, restarted.getConfigurationDeviceSnapshot("phase3").context().copyConnectionConfig().getPort());
            var next = restarted.getConfigurationDeviceSnapshot("phase3");
            next.context().getDeviceInfo().setDeviceName("bundle");
            JsonNode bundleResult = call(mvc, put("/api/config/device/phase3/bundle").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(new DeviceConfigBundleRequest(next.configVersion(), pointsRevision,
                            next.context().getDeviceInfo(), next.context().copyConnectionConfig(), next.context().copyDataPoints()))), 200);
            assertEquals("bundle", restarted.getConfigurationDevice("phase3").getDeviceName());
            assertEquals("40002", restarted.getConfigurationPoints("phase3").copyDataPoints().get(0).getAddress());
            assertEquals(store.findDevice("phase3").orElseThrow().revision(), bundleResult.path("data").path("persistedRevision").asLong());

            var beforeFailure = store.findDevice("phase3").orElseThrow();
            try (var connectionSql = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())) {
                try (var statement = connectionSql.createStatement()) {
                    statement.execute("CREATE TRIGGER phase3_fail BEFORE UPDATE ON dc_device BEGIN SELECT RAISE(ABORT, 'test-only-failure'); END");
                }
            }
            try {
                var blocked = restarted.getConfigurationDeviceSnapshot("phase3").context().getDeviceInfo();
                blocked.setDeviceName("not-committed");
                JsonNode failed = call(mvc, put("/api/config/device/phase3").param("baseRevision", String.valueOf(beforeFailure.revision()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(blocked)), 503);
                assertEquals(false, failed.path("data").path("committed").asBoolean());
                assertEquals(beforeFailure.revision(), store.findDevice("phase3").orElseThrow().revision());
                assertEquals("bundle", restarted.getConfigurationDevice("phase3").getDeviceName());
            } finally {
                try (var connectionSql = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
                     var statement = connectionSql.createStatement()) { statement.execute("DROP TRIGGER phase3_fail"); }
            }
            failEvent.set(true);
            var postCommit = restarted.getConfigurationDeviceSnapshot("phase3").context().getDeviceInfo();
            postCommit.setDeviceName("committed-event-failed");
            JsonNode eventFailure = call(mvc, put("/api/config/device/phase3")
                    .param("baseRevision", String.valueOf(beforeFailure.revision()))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(postCommit)), 200);
            assertEquals("CONFIG_COMMITTED_APPLY_FAILED", eventFailure.path("machineCode").asText());
            assertTrue(eventFailure.path("data").path("committed").asBoolean());
            assertFalse(eventFailure.path("data").path("reconciled").asBoolean());
            assertTrue(eventFailure.path("data").path("reloadRequired").asBoolean());
            assertEquals(beforeFailure.revision() + 1, store.findDevice("phase3").orElseThrow().revision());
            assertEquals("committed-event-failed", codec.toRuntime(store.findDevice("phase3").orElseThrow().configuration()).getDeviceInfo().getDeviceName());
            failEvent.set(false);
            assertTrue(restarted.refreshDeviceConfig("phase3"));
            long finalRevision = store.findDevice("phase3").orElseThrow().revision();
            call(mvc, delete("/api/config/local/device/phase3").param("baseRevision", String.valueOf(finalRevision)), 200);
            assertTrue(store.findDevice("phase3").isEmpty());
            assertEquals(0, call(mvc, get("/api/config/device/phase3/bundle"), 404).path("data").size());
        }
    }

    private MockMvc mvc(ConfigManager manager) {
        return MockMvcBuilders.standaloneSetup(new ConfigController(service(manager)))
                .setControllerAdvice(new ConfigApiExceptionHandler()).build();
    }

    private ConfigConsoleApplicationService service(ConfigManager manager) {
        var sanitizer = new SensitiveConfigSanitizer();
        var collection = mock(CollectionService.class);
        var local = new LocalDeviceConfigApplicationService(manager, collection, mock(DeviceLifecycleCoordinator.class));
        var exchange = new ConfigImportExportApplicationService(manager, collection, sanitizer);
        return new ConfigConsoleApplicationService(manager, mock(ConfigSyncService.class), sanitizer,
                mock(PointRuntimeStateService.class), local, exchange, new ConfigDiffCalculator());
    }

    private ConfigManager manager(SqliteConfigService store) throws Exception {
        Path remote = directory.resolve("remote.json");
        Files.writeString(remote, "[]");
        var properties = new CollectorProperties();
        properties.getConfig().getFile().setDevices(remote.toString());
        properties.getConfig().setLocalDeviceSnapshotFile(directory.resolve("unused.json").toString());
        var sync = new ConfigSyncService(new FileConfigLoader(properties), Runnable::run, properties);
        var bridge = new com.wangbin.collector.config.sqlite.runtime.WangbinSqliteLocalConfigurationPersistence(store);
        var beans = new StaticListableBeanFactory(Map.of("persistence", bridge));
        var manager = new ConfigManager(sync, event -> { if (failEvent.get()) throw new IllegalStateException("test-only-event-failure"); },
                new FieldUniquenessValidator(), new LocalDeviceConfigStore(json, properties), null, null,
                beans.getBeanProvider(WangbinLocalConfigurationPersistence.class));
        manager.init();
        return manager;
    }

    private DeviceContext configuration(String id, String pointId) {
        var device = new DeviceInfo();
        device.setDeviceId(id); device.setDeviceName(id); device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP"); device.setConfigSource("local"); device.setTemporaryConfig(true);
        var connection = new DeviceConnection();
        connection.setDeviceId(id); connection.setConnectionType("MODBUS_TCP"); connection.setHost("127.0.0.1"); connection.setPort(1502);
        var point = new DataPoint();
        point.setDeviceId(id); point.setPointId(pointId); point.setPointCode(pointId);
        point.setAddress("40001"); point.setDataType("FLOAT"); point.setStatus(1);
        return DeviceContext.of(device, connection, List.of(point));
    }

    private JsonNode call(MockMvc mvc, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
