package com.wangbin.collector.config.sqlite.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.core.config.persistence.ConfigurationReader;
import com.wangbin.collector.core.config.persistence.ConfigurationStore;
import com.wangbin.collector.core.config.persistence.DeviceConfiguration;
import com.wangbin.collector.core.config.persistence.MigrationRecord;
import com.wangbin.collector.core.config.persistence.StoredDeviceConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * wangbin 旧本地 JSON 的显式一次性迁移工具，不注册启动钩子、不调用 ConfigManager。
 * 使用原始 JSON 白名单保留协议扩展，成功标记、回读校验和数据在同一事务中完成。
 */
public final class LegacyJsonMigration {
    public static final String MIGRATION_ID = "wangbin-local-device-json-v1";
    private static final long MAX_SOURCE_BYTES = 64L * 1024 * 1024;
    private final ConfigurationStore store;
    private final ConfigurationJsonCodec codec;

    public LegacyJsonMigration(ConfigurationStore store, ConfigurationJsonCodec codec) {
        this.store = Objects.requireNonNull(store, "配置仓储不能为空");
        this.codec = Objects.requireNonNull(codec, "配置编解码器不能为空");
    }

    /** 兼容旧路径仅作为读取来源，绝不改名、删除或回写。 */
    public static Path defaultSource() {
        return Path.of(System.getProperty("user.home"), ".data-collection-service", "local-device-configs.json")
                .toAbsolutePath().normalize();
    }

    /** 完整扫描身份缺失与重复情况；损坏文件不会被伪装为空配置。 */
    public static MigrationInspection inspect(Path source) {
        return readSource(source, new ConfigurationJsonCodec()).inspection();
    }

    /**
     * 仅向空目标导入；成功记录存在时直接跳过，不重读、不覆盖旧来源或当前配置。
     * 并发迁移在写事务内再次检查标记，身份冲突或任一写入失败均回滚。
     */
    public MigrationResult migrate(Path source) {
        var completed = store.findMigration(MIGRATION_ID);
        if (completed.isPresent()) {
            return new MigrationResult(MigrationResult.Status.ALREADY_MIGRATED, completed.get(), false);
        }
        SourceSnapshot snapshot = readSource(source, codec);
        MigrationInspection inspection = snapshot.inspection();
        if (!inspection.canMigrate()) {
            throw new IllegalArgumentException("旧 JSON 预检失败：" + String.join("；", inspection.problems()));
        }
        List<DeviceConfiguration> devices = codec.decodeLocalDevices(snapshot.document());
        return store.transaction(writer -> {
            var previous = writer.findMigration(MIGRATION_ID);
            if (previous.isPresent()) {
                return new MigrationResult(MigrationResult.Status.ALREADY_MIGRATED, previous.get(), false);
            }
            if (!writer.listDevices().isEmpty()) {
                throw new IllegalStateException("旧 JSON 迁移要求空的本地设备目标，禁止合并或覆盖已有配置");
            }
            for (DeviceConfiguration device : devices) writer.saveDevice(device, 0);
            verifyImported(writer, devices, inspection);
            if (!inspection.sourceSha256().equals(sha256(readBytes(source)))) {
                throw new IllegalStateException("旧 JSON 在迁移期间发生变化，本次事务已拒绝提交");
            }
            MigrationRecord record = new MigrationRecord(MIGRATION_ID, source.toAbsolutePath().normalize().toString(),
                    inspection.sourceSha256(), inspection.deviceCount(), inspection.connectionCount(),
                    inspection.pointCount(), Instant.now());
            writer.recordMigration(record);
            return new MigrationResult(MigrationResult.Status.IMPORTED, record, true);
        });
    }

    private void verifyImported(ConfigurationReader reader, List<DeviceConfiguration> expected,
                                MigrationInspection inspection) {
        List<StoredDeviceConfiguration> stored = reader.listDevices();
        int connections = (int) stored.stream().filter(item -> item.configuration().connection() != null).count();
        int points = stored.stream().mapToInt(item -> item.configuration().points().size()).sum();
        if (stored.size() != inspection.deviceCount() || connections != inspection.connectionCount()
                || points != inspection.pointCount()) {
            throw new IllegalStateException("迁移提交前设备、连接或点位数量校验失败");
        }
        Map<String, DeviceConfiguration> byId = expected.stream()
                .collect(Collectors.toMap(DeviceConfiguration::deviceId, Function.identity()));
        for (StoredDeviceConfiguration item : stored) {
            DeviceConfiguration original = byId.get(item.configuration().deviceId());
            if (original == null || !codec.encodeLocalDevice(original).equals(codec.encodeLocalDevice(item.configuration()))) {
                throw new IllegalStateException("迁移提交前稳定身份或配置内容校验失败");
            }
        }
    }

    private static SourceSnapshot readSource(Path source, ConfigurationJsonCodec codec) {
        Objects.requireNonNull(source, "旧 JSON 路径不能为空");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return new SourceSnapshot(new MigrationInspection(false, "", 0, 0, 0, 0, 0, 0, 0,
                    List.of("旧 JSON 文件不存在")), null);
        }
        byte[] bytes = readBytes(source);
        String digest = sha256(bytes);
        JsonNode document;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            document = codec.readJson(text);
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            return new SourceSnapshot(new MigrationInspection(true, digest, 0, 0, 0, 0, 0, 0, 0,
                    List.of("旧 JSON 格式无效，必须是完整 UTF-8 配置数组")), null);
        }
        if (!document.isArray()) {
            return new SourceSnapshot(new MigrationInspection(true, digest, 0, 0, 0, 0, 0, 0, 0,
                    List.of("旧 JSON 根节点必须为数组")), document);
        }
        int devices = document.size();
        int connections = 0;
        int points = 0;
        int missingDevices = 0;
        int duplicateDevices = 0;
        int missingPoints = 0;
        int duplicatePoints = 0;
        Set<String> deviceIds = new HashSet<>();
        Set<String> pointIds = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (JsonNode entry : document) {
            JsonNode device = entry.path("device");
            JsonNode id = device.has("deviceId") ? device.get("deviceId") : device.get("id");
            if (!validIdentity(id)) missingDevices++;
            else if (!deviceIds.add(id.textValue())) duplicateDevices++;
            JsonNode connection = entry.get("connection");
            if (connection != null && !connection.isNull()) connections++;
            JsonNode pointArray = entry.path("points");
            if (pointArray.isArray()) {
                points += pointArray.size();
                for (JsonNode point : pointArray) {
                    JsonNode pointId = point.get("pointId");
                    if (!validIdentity(pointId)) missingPoints++;
                    else if (!pointIds.add(pointId.textValue())) duplicatePoints++;
                }
            }
        }
        if (missingDevices != 0) problems.add("存在缺失的 deviceId，禁止生成替代身份");
        if (duplicateDevices != 0) problems.add("存在重复的 deviceId");
        if (missingPoints != 0) problems.add("存在缺失的 pointId，禁止生成替代身份");
        if (duplicatePoints != 0) problems.add("存在重复的 pointId");
        try {
            codec.decodeLocalDevices(document);
        } catch (IllegalArgumentException exception) {
            // codec 仅抛固定字段级说明，不把解析器消息、输入值或异常链带入报告。
            problems.add(exception.getMessage());
        }
        return new SourceSnapshot(new MigrationInspection(true, digest, devices, connections, points,
                missingDevices, duplicateDevices, missingPoints, duplicatePoints, problems), document);
    }

    private static boolean validIdentity(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().isBlank();
    }

    private static byte[] readBytes(Path source) {
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("旧 JSON 必须为可读取的普通文件，不接受符号链接");
            }
            if (Files.size(source) > MAX_SOURCE_BYTES) throw new IllegalStateException("旧 JSON 超过 64 MiB 安全读取上限");
            // 有界读取也覆盖检查文件大小后仍被其他进程扩大的情况。
            try (var stream = Files.newInputStream(source)) {
                byte[] bytes = stream.readNBytes((int) MAX_SOURCE_BYTES + 1);
                if (bytes.length > MAX_SOURCE_BYTES) throw new IllegalStateException("旧 JSON 超过 64 MiB 安全读取上限");
                return bytes;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("旧 JSON 无法读取，请检查路径和访问权限");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境缺少 SHA-256");
        }
    }

    private record SourceSnapshot(MigrationInspection inspection, JsonNode document) {
        @Override
        public String toString() {
            return "wangbin 旧 JSON 只读快照 [REDACTED]";
        }
    }
}
