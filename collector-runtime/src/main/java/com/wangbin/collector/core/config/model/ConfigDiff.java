package com.wangbin.collector.core.config.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Diff between two 配置 snapshots.
 */
public record ConfigDiff(
        Set<String> addedDevices,
        Set<String> removedDevices,
        Set<String> changedDevices,
        Set<String> changedPoints,
        Set<String> changedConnections) {

    public ConfigDiff {
        addedDevices = immutableSet(addedDevices);
        removedDevices = immutableSet(removedDevices);
        changedDevices = immutableSet(changedDevices);
        changedPoints = immutableSet(changedPoints);
        changedConnections = immutableSet(changedConnections);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static ConfigDiff between(ConfigSnapshot previousSnapshot, ConfigSnapshot currentSnapshot) {
        ConfigSnapshot previous = previousSnapshot != null ? previousSnapshot : ConfigSnapshot.empty();
        ConfigSnapshot current = currentSnapshot != null ? currentSnapshot : ConfigSnapshot.empty();

        LinkedHashSet<String> addedDevices = new LinkedHashSet<>();
        LinkedHashSet<String> removedDevices = new LinkedHashSet<>();
        LinkedHashSet<String> changedDevices = new LinkedHashSet<>();
        LinkedHashSet<String> changedPoints = new LinkedHashSet<>();
        LinkedHashSet<String> changedConnections = new LinkedHashSet<>();

        LinkedHashSet<String> deviceIds = new LinkedHashSet<>(previous.deviceIds());
        deviceIds.addAll(current.deviceIds());

        for (String deviceId : deviceIds) {
            boolean previousExists = previous.hasDevice(deviceId);
            boolean currentExists = current.hasDevice(deviceId);

            if (!previousExists && currentExists) {
                addedDevices.add(deviceId);
            } else if (previousExists && !currentExists) {
                removedDevices.add(deviceId);
            } else if (!Objects.equals(previous.device(deviceId), current.device(deviceId))) {
                changedDevices.add(deviceId);
            }

            if (!Objects.equals(previous.points(deviceId), current.points(deviceId))) {
                changedPoints.add(deviceId);
            }

            if (!Objects.equals(previous.connection(deviceId), current.connection(deviceId))) {
                changedConnections.add(deviceId);
            }
        }

        return new ConfigDiff(addedDevices, removedDevices, changedDevices, changedPoints, changedConnections);
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean hasChanges() {
        return !addedDevices.isEmpty()
                || !removedDevices.isEmpty()
                || !changedDevices.isEmpty()
                || !changedPoints.isEmpty()
                || !changedConnections.isEmpty();
    }

    /**
     * 执行当前业务逻辑。
     */
    public Set<String> deviceEventIds() {
        LinkedHashSet<String> deviceIds = new LinkedHashSet<>(addedDevices);
        deviceIds.addAll(changedDevices);
        deviceIds.addAll(removedDevices);
        return Collections.unmodifiableSet(deviceIds);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static Set<String> immutableSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
