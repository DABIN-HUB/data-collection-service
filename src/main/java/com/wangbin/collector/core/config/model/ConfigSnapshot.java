package com.wangbin.collector.core.config.model;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of remote-loaded config state.
 */
public record ConfigSnapshot(
        Map<String, DeviceInfo> devices,
        Map<String, List<DataPoint>> points,
        Map<String, DeviceConnection> connections) {

    private static final ConfigSnapshot EMPTY =
            new ConfigSnapshot(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

    public ConfigSnapshot {
        devices = immutableDevices(devices);
        points = immutablePoints(points);
        connections = immutableConnections(connections);
    }

    public static ConfigSnapshot empty() {
        return EMPTY;
    }

    public boolean hasDevice(String deviceId) {
        return devices.containsKey(deviceId);
    }

    public DeviceInfo device(String deviceId) {
        return devices.get(deviceId);
    }

    public List<DataPoint> points(String deviceId) {
        return points.getOrDefault(deviceId, List.of());
    }

    public DeviceConnection connection(String deviceId) {
        return connections.get(deviceId);
    }

    public Set<String> deviceIds() {
        LinkedHashSet<String> deviceIds = new LinkedHashSet<>(devices.keySet());
        deviceIds.addAll(points.keySet());
        deviceIds.addAll(connections.keySet());
        return Collections.unmodifiableSet(deviceIds);
    }

    private static Map<String, DeviceInfo> immutableDevices(Map<String, DeviceInfo> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, DeviceInfo> copy = new LinkedHashMap<>();
        source.forEach((deviceId, device) -> {
            if (hasText(deviceId) && device != null) {
                copy.put(deviceId, device);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<DataPoint>> immutablePoints(Map<String, List<DataPoint>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, List<DataPoint>> copy = new LinkedHashMap<>();
        source.forEach((deviceId, devicePoints) -> {
            if (!hasText(deviceId)) {
                return;
            }
            List<DataPoint> safePoints = new ArrayList<>();
            if (devicePoints != null) {
                for (DataPoint point : devicePoints) {
                    if (point != null) {
                        safePoints.add(point);
                    }
                }
            }
            copy.put(deviceId, Collections.unmodifiableList(safePoints));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, DeviceConnection> immutableConnections(Map<String, DeviceConnection> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, DeviceConnection> copy = new LinkedHashMap<>();
        source.forEach((deviceId, connection) -> {
            if (hasText(deviceId) && connection != null) {
                copy.put(deviceId, connection);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
