package com.wangbin.collector.core.config.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.model.LocalDeviceConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地设备配置的磁盘快照存储。
 */
@Slf4j
@Component
public class LocalDeviceConfigStore {

    private static final TypeReference<List<LocalDeviceConfigSnapshot>> SNAPSHOT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path snapshotFile;

    public LocalDeviceConfigStore(ObjectMapper objectMapper, CollectorProperties collectorProperties) {
        this.objectMapper = objectMapper;
        this.snapshotFile = resolveSnapshotFile(collectorProperties);
    }

    /**
     * 读取上一次成功保存的本地设备配置。
     */
    public List<DeviceContext> load() {
        if (!Files.isRegularFile(snapshotFile)) {
            return Collections.emptyList();
        }
        try {
            List<LocalDeviceConfigSnapshot> snapshots = objectMapper.readValue(snapshotFile.toFile(), SNAPSHOT_LIST);
            if (snapshots == null || snapshots.isEmpty()) {
                return Collections.emptyList();
            }
            List<DeviceContext> contexts = new ArrayList<>(snapshots.size());
            for (LocalDeviceConfigSnapshot snapshot : snapshots) {
                if (snapshot != null && snapshot.device() != null) {
                    contexts.add(snapshot.toContext());
                }
            }
            log.info("已加载本地设备配置快照，路径={}，设备数量={}", snapshotFile, contexts.size());
            return contexts;
        } catch (IOException exception) {
            throw new IllegalStateException("读取本地设备配置快照失败: " + snapshotFile, exception);
        }
    }

    /**
     * 原子保存全部本地设备配置。
     */
    public void save(List<DeviceContext> contexts) {
        List<LocalDeviceConfigSnapshot> snapshots = contexts == null
                ? Collections.emptyList()
                : contexts.stream().map(LocalDeviceConfigSnapshot::from).toList();
        Path parent = snapshotFile.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = Files.createTempFile(parent, snapshotFile.getFileName().toString(), ".tmp");
            try {
                objectMapper.writeValue(tempFile.toFile(), snapshots);
                moveAtomically(tempFile, snapshotFile);
            } finally {
                Files.deleteIfExists(tempFile);
            }
            log.info("本地设备配置快照已保存，路径={}，设备数量={}", snapshotFile, snapshots.size());
        } catch (IOException exception) {
            throw new IllegalStateException("保存本地设备配置快照失败: " + snapshotFile, exception);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolveSnapshotFile(CollectorProperties collectorProperties) {
        String configured = collectorProperties.getConfig().getLocalDeviceSnapshotFile();
        if (StringUtils.hasText(configured)) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".data-collection-service", "local-device-configs.json")
                .toAbsolutePath().normalize();
    }
}
