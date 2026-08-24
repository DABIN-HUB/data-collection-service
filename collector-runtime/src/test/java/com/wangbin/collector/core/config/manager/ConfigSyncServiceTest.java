package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.loader.ConfigLoader;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigSyncServiceTest {

    @Test
    void configSyncServiceShouldSkipConcurrentSyncAndNotifyOnce() throws Exception {
        StubConfigLoader loader = new StubConfigLoader();
        loader.setSnapshot(List.of(device("dev-1", "device-1", "MODBUS_TCP", 2000)), Map.of(), Map.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        loader.blockLoadAllDevices(entered, release);

        ConfigSyncService service = new ConfigSyncService(loader, Runnable::run, new CollectorProperties());
        List<ConfigUpdateEvent> events = new CopyOnWriteArrayList<>();
        service.registerConfigListener(events::add);

        Thread syncThread = new Thread(service::syncAllConfig);
        syncThread.start();

        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.syncAllConfig();
        assertEquals(1, loader.loadAllDevicesCalls());

        release.countDown();
        syncThread.join(2000);

        assertFalse(syncThread.isAlive());
        assertEquals(1, events.size());
        assertEquals("device", events.get(0).getConfigType());
        assertEquals("dev-1", events.get(0).getDeviceId());
    }

    @Test
    void configSyncServiceShouldEmitIncrementalEventsForChangedDevice() {
        StubConfigLoader loader = new StubConfigLoader();
        loader.setSnapshot(
                List.of(device("dev-1", "device-1", "MODBUS_TCP", 2000)),
                Map.of("dev-1", List.of(point("dev-1", "temperature", "40001"))),
                Map.of("dev-1", connection("dev-1", "127.0.0.1", 502))
        );

        ConfigSyncService service = new ConfigSyncService(loader, Runnable::run, new CollectorProperties());
        service.loadAllDevices();
        service.loadDataPoints("dev-1");
        service.loadConnectionConfig("dev-1");

        loader.setSnapshot(
                List.of(device("dev-1", "device-1-renamed", "MODBUS_TCP", 5000)),
                Map.of("dev-1", List.of(
                        point("dev-1", "temperature", "40002"),
                        point("dev-1", "pressure", "40003"))),
                Map.of("dev-1", connection("dev-1", "192.168.1.20", 1502))
        );

        List<ConfigUpdateEvent> events = new CopyOnWriteArrayList<>();
        service.registerConfigListener(events::add);

        service.syncAllConfig();

        assertEquals(List.of("device", "connection", "points"),
                events.stream().map(ConfigUpdateEvent::getConfigType).toList());
        assertEquals(List.of("dev-1", "dev-1", "dev-1"),
                events.stream().map(ConfigUpdateEvent::getDeviceId).toList());
        assertTrue(Boolean.TRUE.equals(events.get(0).getConnectionChanged()));
        assertEquals(1, events.get(2).getPointCountChange());
        assertEquals("device-1-renamed", service.getDeviceConfigs().get("dev-1").getDeviceName());
        assertEquals("192.168.1.20", service.getConnectionConfigs().get("dev-1").getHost());
        assertEquals(2, service.getPointConfigs().get("dev-1").size());
        assertTrue(service.getLastSyncTime() > 0);
    }

    @Test
    void configSyncServiceShouldNotBroadcastWhenSnapshotUnchanged() {
        StubConfigLoader loader = new StubConfigLoader();
        loader.setSnapshot(
                List.of(device("dev-1", "device-1", "MODBUS_TCP", 2000)),
                Map.of("dev-1", List.of(point("dev-1", "temperature", "40001"))),
                Map.of("dev-1", connection("dev-1", "127.0.0.1", 502))
        );

        ConfigSyncService service = new ConfigSyncService(loader, Runnable::run, new CollectorProperties());
        service.loadAllDevices();
        service.loadDataPoints("dev-1");
        service.loadConnectionConfig("dev-1");

        List<ConfigUpdateEvent> events = new CopyOnWriteArrayList<>();
        service.registerConfigListener(events::add);

        service.syncAllConfig();

        assertTrue(events.isEmpty());
        assertTrue(service.getLastSyncTime() > 0);
    }

    @Test
    void configSyncServiceShouldKeepLastSnapshotWhenRemoteLoadFails() {
        StubConfigLoader loader = new StubConfigLoader();
        loader.setSnapshot(
                List.of(device("dev-1", "device-1", "MODBUS_TCP", 2000)),
                Map.of("dev-1", List.of(point("dev-1", "temperature", "40001"))),
                Map.of("dev-1", connection("dev-1", "127.0.0.1", 502))
        );
        ConfigSyncService service = new ConfigSyncService(loader, Runnable::run, new CollectorProperties());
        service.loadAllDevices();
        service.loadDataPoints("dev-1");
        service.loadConnectionConfig("dev-1");
        List<ConfigUpdateEvent> events = new CopyOnWriteArrayList<>();
        service.registerConfigListener(events::add);

        loader.failSnapshotLoad();
        service.syncAllConfig();

        assertEquals("device-1", service.getDeviceConfigs().get("dev-1").getDeviceName());
        assertEquals(1, service.getPointConfigs().get("dev-1").size());
        assertTrue(events.isEmpty());
        assertEquals(1, service.getConsecutiveFailures());
        assertTrue(service.getLastFailureTime() > 0);
    }

    private static DeviceInfo device(String deviceId, String deviceName, String protocolType, int collectionInterval) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceName);
        device.setProtocolType(protocolType);
        device.setCollectionInterval(collectionInterval);
        return device;
    }

    private static DeviceConnection connection(String deviceId, String host, int port) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost(host);
        connection.setPort(port);
        return connection;
    }

    private static DataPoint point(String deviceId, String pointCode, String address) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointCode(pointCode);
        point.setAddress(address);
        point.setDataType("FLOAT");
        return point;
    }

    private static final class StubConfigLoader implements ConfigLoader {

        private final AtomicInteger loadAllDevicesCalls = new AtomicInteger();
        private volatile List<DeviceInfo> devices = List.of();
        private volatile Map<String, List<DataPoint>> points = Map.of();
        private volatile Map<String, DeviceConnection> connections = Map.of();
        private volatile CountDownLatch enteredLoadAllDevices;
        private volatile CountDownLatch releaseLoadAllDevices;
        private volatile boolean failSnapshotLoad;

        void setSnapshot(List<DeviceInfo> devices,
                         Map<String, List<DataPoint>> points,
                         Map<String, DeviceConnection> connections) {
            this.devices = List.copyOf(devices);
            this.points = new LinkedHashMap<>(points);
            this.connections = new LinkedHashMap<>(connections);
        }

        void blockLoadAllDevices(CountDownLatch entered, CountDownLatch release) {
            this.enteredLoadAllDevices = entered;
            this.releaseLoadAllDevices = release;
        }

        int loadAllDevicesCalls() {
            return loadAllDevicesCalls.get();
        }

        void failSnapshotLoad() {
            failSnapshotLoad = true;
        }

        @Override
        public List<DeviceInfo> loadAllDevices() {
            loadAllDevicesCalls.incrementAndGet();
            if (failSnapshotLoad) {
                throw new IllegalStateException("模拟远程配置加载失败");
            }
            CountDownLatch entered = enteredLoadAllDevices;
            CountDownLatch release = releaseLoadAllDevices;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return devices;
        }

        @Override
        public DeviceInfo loadDevice(String deviceId) {
            return devices.stream()
                    .filter(device -> deviceId.equals(device.getDeviceId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<DataPoint> loadDataPoints(String deviceId) {
            return new ArrayList<>(points.getOrDefault(deviceId, List.of()));
        }

        @Override
        public DeviceConnection loadConnectionConfig(String deviceId) {
            return connections.get(deviceId);
        }
    }
}
