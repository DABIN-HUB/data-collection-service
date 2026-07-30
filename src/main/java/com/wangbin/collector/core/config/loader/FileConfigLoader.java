package com.wangbin.collector.core.config.loader;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "collector.config", name = "loader", havingValue = "file")
public class FileConfigLoader implements ConfigLoader {

    @Value("${collector.config.file.devices:}")
    private String devicesPath;

    @Value("${collector.config.file.points-dir:}")
    private String pointsDir;

    @Value("${collector.config.file.connections-dir:}")
    private String connectionsDir;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public List<DeviceInfo> loadAllDevices() {
        return readList(devicesPath, DeviceInfo.class);
    }

    @Override
    public DeviceInfo loadDevice(String deviceId) {
        return loadAllDevices().stream()
                .filter(device -> device != null && deviceId.equals(device.getDeviceId()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<DataPoint> loadDataPoints(String deviceId) {
        return readList(resolveChildFile(pointsDir, deviceId), DataPoint.class);
    }

    @Override
    public DeviceConnection loadConnectionConfig(String deviceId) {
        String file = resolveChildFile(connectionsDir, deviceId);
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(file);
            if (!Files.exists(path)) {
                return null;
            }
            return objectMapper.readValue(path.toFile(), DeviceConnection.class);
        } catch (Exception e) {
            log.error("load file connection failed, deviceId={}, file={}", deviceId, file, e);
            return null;
        }
    }

    private String resolveChildFile(String directory, String deviceId) {
        if (directory == null || directory.isBlank() || deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return Path.of(directory, deviceId + ".json").toString();
    }

    private <T> List<T> readList(String file, Class<T> elementType) {
        if (file == null || file.isBlank()) {
            return Collections.emptyList();
        }
        try {
            Path path = Path.of(file);
            if (!Files.exists(path)) {
                return Collections.emptyList();
            }
            return objectMapper.readerForListOf(elementType).readValue(path.toFile());
        } catch (Exception e) {
            log.error("load file config list failed, file={}", file, e);
            return Collections.emptyList();
        }
    }
}
