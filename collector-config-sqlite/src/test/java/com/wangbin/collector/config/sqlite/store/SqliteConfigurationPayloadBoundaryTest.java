package com.wangbin.collector.config.sqlite.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** wangbin 的模板、设置和远端快照也不能作为整实体运行态的旁路入口。 */
class SqliteConfigurationPayloadBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void rejectsKnownRuntimeFieldsInAllOpaqueConfigurationWrites() {
        try (var store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 100)) {
            for (String json : List.of("{\"status\":\"ONLINE\"}", "{\"lastValue\":4}", "{\"connectionStats\":{}}",
                    "{\"devices\":[{\"deviceId\":\"d\",\"retryCount\":2}]}",
                    "{\"points\":[{\"status\":1,\"currentCollectionInterval\":25}]}")) {
                assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveProtocolProfile("p", "TCP", json, 0)));
                assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveGatewaySetting("k", json, 0)));
                assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveRemoteSnapshot("remote", "s", "v", json, 0)));
            }
            assertEquals(0, store.currentRevision());
            assertTrue(store.listProtocolProfiles().isEmpty());
            assertTrue(store.listGatewaySettings().isEmpty());
            assertTrue(store.listRemoteSnapshots("remote").isEmpty());
        }
    }

    @Test
    void preservesConfigurationStatusAlarmDurationAndUnknownProtocolExtensionKeys() {
        String json = """
                {"status":1,"extJson":{"vendor":{"lastValue":"配置扩展原值","status":"ONLINE"}},
                 "additionalConfig":{"unknown":[null,1.2300]},"alarmRule":[{"duration":10,"unknown":true}]}
                """;
        try (var store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 100)) {
            store.transaction(w -> {
                w.saveProtocolProfile("p", "CUSTOM", json, 0);
                w.saveGatewaySetting("k", json, 0);
                w.saveRemoteSnapshot("remote", "s", "v", json, 0);
                return null;
            });
            assertEquals(json, store.findProtocolProfile("p").orElseThrow().configJson());
            assertEquals(json, store.findGatewaySetting("k").orElseThrow().configJson());
            assertEquals(json, store.findRemoteSnapshot("remote", "s", "v").orElseThrow().configJson());
        }
    }
}
