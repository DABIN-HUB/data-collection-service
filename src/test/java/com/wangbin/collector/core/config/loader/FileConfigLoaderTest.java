package com.wangbin.collector.core.config.loader;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.model.ConfigSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class FileConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void fileConfigLoaderShouldLoadPerDevicePointsAndConnection() throws IOException {
        Path devicesFile = tempDir.resolve("devices.json");
        Path pointsDir = Files.createDirectories(tempDir.resolve("points"));
        Path connectionsDir = Files.createDirectories(tempDir.resolve("connections"));

        Files.writeString(devicesFile, """
                [
                  {
                    "id": "dev-1",
                    "deviceName": "device-1",
                    "protocolType": "MODBUS_TCP"
                  }
                ]
                """);
        Files.writeString(pointsDir.resolve("dev-1.json"), """
                [
                  {
                    "deviceId": "dev-1",
                    "pointCode": "temperature",
                    "address": "40001",
                    "dataType": "FLOAT"
                  }
                ]
                """);
        Files.writeString(connectionsDir.resolve("dev-1.json"), """
                {
                  "deviceId": "dev-1",
                  "connectionType": "MODBUS_TCP",
                  "host": "127.0.0.1",
                  "port": 502
                }
                """);

        CollectorProperties collectorProperties = new CollectorProperties();
        collectorProperties.getConfig().getFile().setDevices(devicesFile.toString());
        collectorProperties.getConfig().getFile().setPointsDir(pointsDir.toString());
        collectorProperties.getConfig().getFile().setConnectionsDir(connectionsDir.toString());
        FileConfigLoader loader = new FileConfigLoader(collectorProperties);

        List<DeviceInfo> devices = loader.loadAllDevices();
        List<DataPoint> points = loader.loadDataPoints("dev-1");
        DeviceConnection connection = loader.loadConnectionConfig("dev-1");
        ConfigSnapshot snapshot = loader.loadSnapshot();

        assertEquals(1, devices.size());
        assertEquals("dev-1", devices.get(0).getDeviceId());
        assertEquals(1, points.size());
        assertEquals("temperature", points.get(0).getPointCode());
        assertNotNull(connection);
        assertEquals("127.0.0.1", connection.getHost());
        assertEquals(502, connection.getPort());
        assertEquals("dev-1", snapshot.device("dev-1").getDeviceId());
        assertEquals(1, snapshot.points("dev-1").size());
        assertEquals("127.0.0.1", snapshot.connection("dev-1").getHost());
    }
}
