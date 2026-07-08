package com.wangbin.collector.core.report.downlink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 处理统一 MQTT 上报连接收到的平台下行消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqttDownlinkService {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final List<String> CONFIG_PUSH_TYPES = List.of(
            "device", "points", "connection", "collection", "all"
    );
    private static final List<String> RESERVED_KEYS = List.of(
            "id", "messageId", "version", "method", "deviceId", "deviceName",
            "productKey", "timestamp", "shadowVersion", "expectedVersion", "source"
    );

    private final ObjectMapper objectMapper;
    private final ConfigManager configManager;
    private final ConfigSyncService configSyncService;
    private final ReportProperties reportProperties;
    private final CollectionManager collectionManager;
    private final ShadowManager shadowManager;

    public MqttDownlinkResult handle(String topic, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return MqttDownlinkResult.of(null, null, null, 400, "empty payload", Map.of("topic", topic));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            return MqttDownlinkResult.of(null, null, null, 400, "invalid json payload", Map.of("topic", topic));
        }

        String method = resolveMethod(root, topic);
        String messageId = resolveMessageId(root);
        if (!StringUtils.hasText(method)) {
            return MqttDownlinkResult.of(messageId, null, null, 400, "missing method", Map.of("topic", topic));
        }

        return switch (method) {
            case MessageConstant.MESSAGE_TYPE_PROPERTY_SET -> handlePropertySet(topic, root, messageId, method);
            case MessageConstant.MESSAGE_TYPE_SERVICE_INVOKE -> handleServiceInvoke(topic, root, messageId, method);
            case MessageConstant.MESSAGE_TYPE_CONFIG_PUSH -> handleConfigPush(topic, root, messageId, method);
            case MessageConstant.MESSAGE_TYPE_OTA_UPGRADE ->
                    MqttDownlinkResult.of(messageId, method, resolveDeviceId(root, topic),
                            501, method + " not implemented", Map.of("topic", topic));
            default -> {
                log.debug("忽略非下行业务 MQTT 消息 method={} topic={}", method, topic);
                yield MqttDownlinkResult.ignored(method);
            }
        };
    }

    private MqttDownlinkResult handlePropertySet(String topic, JsonNode root, String messageId, String method) {
        String deviceId = resolveDeviceId(root, topic);
        if (!StringUtils.hasText(deviceId)) {
            return MqttDownlinkResult.of(messageId, method, null, 400, "missing deviceId", Map.of("topic", topic));
        }

        Map<String, Object> desired = extractBusinessParams(root);
        if (desired.isEmpty()) {
            return MqttDownlinkResult.of(messageId, method, deviceId, 400, "empty params", Map.of());
        }

        Long expectedVersion = resolveExpectedVersion(root);
        try {
            shadowManager.updateDesired(deviceId, desired, "mqtt", expectedVersion);
        } catch (IllegalStateException e) {
            return MqttDownlinkResult.of(messageId, method, deviceId, 409, e.getMessage(), Map.of());
        }

        WritePlan plan = buildWritePlan(deviceId, desired);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("desired", desired);
        data.put("fields", plan.fieldResults);

        if (!plan.pointsToWrite.isEmpty()) {
            try {
                Map<String, Boolean> writeResults = collectionManager.writePoints(deviceId, plan.pointsToWrite);
                applyWriteResults(plan, writeResults);
            } catch (Exception e) {
                plan.fieldResults.forEach((field, fieldResult) -> {
                    if (Boolean.TRUE.equals(fieldResult.get("mapped"))) {
                        fieldResult.put("success", false);
                        fieldResult.put("error", e.getMessage());
                    }
                });
            }
        }

        data.put("fields", plan.fieldResults);
        int code = plan.responseCode();
        String message = code == 0 ? "success" : code == 207 ? "partial success" : "write failed";
        return MqttDownlinkResult.of(messageId, method, deviceId, code, message, data);
    }

    private MqttDownlinkResult handleServiceInvoke(String topic, JsonNode root, String messageId, String method) {
        String deviceId = resolveDeviceId(root, topic);
        if (!StringUtils.hasText(deviceId)) {
            return MqttDownlinkResult.of(messageId, method, null, 400, "missing deviceId", Map.of("topic", topic));
        }

        Map<String, Object> params = extractBusinessParams(root);
        String directCommand = firstText(root, "command");
        if (!StringUtils.hasText(directCommand)) {
            directCommand = firstString(params, "command");
        }

        String requestedCommand = directCommand;
        String command = directCommand;
        if (!StringUtils.hasText(command)) {
            requestedCommand = firstText(root, "service", "identifier", "serviceId");
            if (!StringUtils.hasText(requestedCommand)) {
                requestedCommand = firstString(params, "service", "identifier", "serviceId");
            }
            command = resolveServiceCommand(requestedCommand);
        }
        if (!StringUtils.hasText(command)) {
            return MqttDownlinkResult.of(messageId, method, deviceId, 400, "missing command", Map.of("params", params));
        }

        try {
            Object result = collectionManager.executeCommand(deviceId, command, params);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("command", command);
            if (StringUtils.hasText(requestedCommand) && !Objects.equals(requestedCommand, command)) {
                data.put("requestedCommand", requestedCommand);
                data.put("mappingApplied", true);
            }
            data.put("result", result);
            return MqttDownlinkResult.success(messageId, method, deviceId, data);
        } catch (Exception e) {
            return MqttDownlinkResult.of(messageId, method, deviceId, 500,
                    e.getMessage(), Map.of("command", command));
        }
    }
    private MqttDownlinkResult handleConfigPush(String topic, JsonNode root, String messageId, String method) {
        String hintedDeviceId = resolveDeviceId(root, topic);
        String explicitDeviceId = resolveExplicitDeviceId(root);
        Map<String, Object> params = extractBusinessParams(root);
        String configType = resolveConfigPushType(root, params, hintedDeviceId);
        String deviceId = StringUtils.hasText(explicitDeviceId)
                ? explicitDeviceId
                : "all".equals(configType) ? null : hintedDeviceId;
        if (!CONFIG_PUSH_TYPES.contains(configType)) {
            return MqttDownlinkResult.of(messageId, method, deviceId, 400,
                    "unsupported configType", Map.of("configType", configType, "supportedTypes", CONFIG_PUSH_TYPES));
        }
        if (!"all".equals(configType) && !StringUtils.hasText(deviceId)) {
            return MqttDownlinkResult.of(messageId, method, null, 400,
                    "missing deviceId", Map.of("configType", configType));
        }

        log.info("收到 MQTT 配置推送，deviceId={}, configType={}", deviceId, configType);
        try {
            Map<String, Object> data = executeConfigPush(configType, deviceId);
            data.put("topic", topic);
            return MqttDownlinkResult.success(messageId, method, deviceId, data);
        } catch (Exception e) {
            return MqttDownlinkResult.of(messageId, method, deviceId, 500,
                    e.getMessage(), Map.of("configType", configType));
        }
    }

    private Map<String, Object> executeConfigPush(String configType, String deviceId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configType", configType);
        if (StringUtils.hasText(deviceId)) {
            data.put("deviceId", deviceId);
        }

        if ("all".equals(configType)) {
            if (StringUtils.hasText(deviceId)) {
                configSyncService.notifyConfigUpdate("device", deviceId);
                configSyncService.notifyConfigUpdate("points", deviceId);
                configSyncService.notifyConfigUpdate("connection", deviceId);
                data.put("mode", "device-bundle");
                data.put("triggeredTypes", List.of("device", "points", "connection"));
            } else {
                configSyncService.triggerManualSync();
                data.put("mode", "global-sync");
                data.put("triggeredTypes", List.of("all"));
            }
            return data;
        }

        configSyncService.notifyConfigUpdate(configType, deviceId);
        data.put("mode", "incremental");
        data.put("triggeredTypes", List.of(configType));
        return data;
    }

    private String resolveServiceCommand(String requestedCommand) {
        if (!StringUtils.hasText(requestedCommand)) {
            return null;
        }
        Map<String, String> mappings = reportProperties.getMqtt().getServiceCommandMappings();
        if (mappings == null || mappings.isEmpty()) {
            return requestedCommand;
        }

        String direct = mappings.get(requestedCommand);
        if (StringUtils.hasText(direct)) {
            return direct;
        }

        String normalizedRequested = normalize(requestedCommand).replace('-', '_');
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (normalizedRequested.equals(normalize(entry.getKey()).replace('-', '_'))
                    && StringUtils.hasText(entry.getValue())) {
                return entry.getValue();
            }
        }
        return requestedCommand;
    }

    private String resolveConfigPushType(JsonNode root, Map<String, Object> params, String deviceId) {
        String configType = firstText(root, "configType", "type", "scope", "syncType");
        if (!StringUtils.hasText(configType)) {
            configType = firstString(params, "configType", "type", "scope", "syncType");
        }
        if (!StringUtils.hasText(configType)) {
            return StringUtils.hasText(deviceId) ? "collection" : "all";
        }
        return normalizeConfigPushType(configType);
    }

    private String normalizeConfigPushType(String configType) {
        String normalized = normalize(configType).replace('-', '_');
        return switch (normalized) {
            case "point" -> "points";
            case "full", "global", "full_sync", "global_sync" -> "all";
            default -> normalized;
        };
    }

    private WritePlan buildWritePlan(String deviceId, Map<String, Object> values) {
        WritePlan plan = new WritePlan();
        List<DataPoint> points = configManager.getDataPoints(deviceId);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String field = entry.getKey();
            Map<String, Object> fieldResult = new LinkedHashMap<>();
            plan.fieldResults.put(field, fieldResult);

            DataPoint point = resolvePoint(points, field);
            if (point == null) {
                fieldResult.put("mapped", false);
                fieldResult.put("success", false);
                fieldResult.put("error", "point not found");
                continue;
            }

            fieldResult.put("mapped", true);
            fieldResult.put("pointId", point.getPointId());
            fieldResult.put("pointCode", point.getPointCode());
            if (!point.isWritable()) {
                fieldResult.put("success", false);
                fieldResult.put("error", "point is not writable");
                continue;
            }

            plan.pointsToWrite.put(point, entry.getValue());
            fieldResult.put("success", false);
            fieldResult.put("error", "pending");
        }
        return plan;
    }

    private void applyWriteResults(WritePlan plan, Map<String, Boolean> writeResults) {
        for (Map.Entry<DataPoint, Object> entry : plan.pointsToWrite.entrySet()) {
            DataPoint point = entry.getKey();
            String field = plan.resolveField(point);
            Map<String, Object> fieldResult = plan.fieldResults.get(field);
            if (fieldResult == null) {
                continue;
            }
            Boolean success = resolveWriteSuccess(writeResults, point);
            fieldResult.put("success", Boolean.TRUE.equals(success));
            if (Boolean.TRUE.equals(success)) {
                fieldResult.remove("error");
            } else {
                fieldResult.put("error", "protocol write returned false");
            }
        }
    }

    private Boolean resolveWriteSuccess(Map<String, Boolean> writeResults, DataPoint point) {
        if (writeResults == null || writeResults.isEmpty() || point == null) {
            return false;
        }
        return Optional.ofNullable(writeResults.get(point.getPointId()))
                .or(() -> Optional.ofNullable(writeResults.get(point.getPointCode())))
                .or(() -> Optional.ofNullable(writeResults.get(point.getReportField())))
                .orElse(false);
    }

    private DataPoint resolvePoint(List<DataPoint> points, String field) {
        if (points == null || points.isEmpty() || !StringUtils.hasText(field)) {
            return null;
        }
        String normalized = normalize(field);
        return points.stream()
                .filter(point -> matches(point, normalized))
                .findFirst()
                .orElse(null);
    }

    private boolean matches(DataPoint point, String normalizedField) {
        return normalizedField.equals(normalize(point.getReportField()))
                || normalizedField.equals(normalize(point.getPointAlias()))
                || normalizedField.equals(normalize(point.getPointCode()))
                || normalizedField.equals(normalize(point.getPointId()))
                || normalizedField.equals(normalize(point.getPointName()));
    }

    private String resolveExplicitDeviceId(JsonNode root) {
        String direct = firstText(root, "deviceId", "deviceName");
        if (StringUtils.hasText(direct)) {
            return mapDeviceIdentifier(direct);
        }

        JsonNode params = root.get(MessageConstant.FIELD_PARAMS);
        if (params != null && params.isObject()) {
            String fromParams = firstText(params, "deviceId", "deviceName");
            if (StringUtils.hasText(fromParams)) {
                return mapDeviceIdentifier(fromParams);
            }
        }
        return null;
    }

    private String resolveDeviceId(JsonNode root, String topic) {
        String direct = firstText(root, "deviceId", "deviceName");
        if (StringUtils.hasText(direct)) {
            String mapped = mapDeviceIdentifier(direct);
            if (StringUtils.hasText(mapped)) {
                return mapped;
            }
        }

        JsonNode params = root.get(MessageConstant.FIELD_PARAMS);
        if (params != null && params.isObject()) {
            String fromParams = firstText(params, "deviceId", "deviceName");
            if (StringUtils.hasText(fromParams)) {
                String mapped = mapDeviceIdentifier(fromParams);
                if (StringUtils.hasText(mapped)) {
                    return mapped;
                }
            }
        }

        String fromTopic = resolveDeviceFromTopic(topic);
        return StringUtils.hasText(fromTopic) ? mapDeviceIdentifier(fromTopic) : null;
    }

    private String mapDeviceIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        DeviceInfo direct = configManager.getDevice(value);
        if (direct != null) {
            return direct.getDeviceId();
        }
        List<DeviceInfo> devices = configManager.getAllDevices();
        if (devices == null) {
            return value;
        }
        for (DeviceInfo device : devices) {
            if (device == null) {
                continue;
            }
            if (Objects.equals(value, device.getDeviceName())
                    || Objects.equals(value, device.getDeviceAlias())
                    || Objects.equals(value, device.getDeviceId())) {
                return device.getDeviceId();
            }
        }
        return value;
    }

    private String resolveDeviceFromTopic(String topic) {
        if (!StringUtils.hasText(topic)) {
            return null;
        }
        String[] segments = topic.split("/");
        for (int i = 0; i < segments.length - 2; i++) {
            if ("thing".equals(segments[i])) {
                return i > 0 ? segments[i - 1] : null;
            }
        }
        return null;
    }

    private String resolveMethod(JsonNode root, String topic) {
        String method = firstText(root, MessageConstant.FIELD_METHOD);
        if (StringUtils.hasText(method)) {
            return method;
        }
        if (!StringUtils.hasText(topic)) {
            return null;
        }
        String normalizedTopic = topic.replace('\\', '/');
        if (normalizedTopic.contains("thing/property/set")) {
            return MessageConstant.MESSAGE_TYPE_PROPERTY_SET;
        }
        if (normalizedTopic.contains("thing/service/invoke")) {
            return MessageConstant.MESSAGE_TYPE_SERVICE_INVOKE;
        }
        if (normalizedTopic.contains("thing/config/push")) {
            return MessageConstant.MESSAGE_TYPE_CONFIG_PUSH;
        }
        if (normalizedTopic.contains("thing/ota/upgrade")) {
            return MessageConstant.MESSAGE_TYPE_OTA_UPGRADE;
        }
        return null;
    }

    private String resolveMessageId(JsonNode root) {
        return firstText(root, "id", MessageConstant.FIELD_MESSAGE_ID);
    }

    private Long resolveExpectedVersion(JsonNode root) {
        JsonNode node = root.get("shadowVersion");
        if (node == null || node.isNull()) {
            node = root.get("expectedVersion");
        }
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        try {
            return Long.parseLong(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> extractBusinessParams(JsonNode root) {
        JsonNode params = root.get(MessageConstant.FIELD_PARAMS);
        if (params != null && params.isObject()) {
            Map<String, Object> values = objectMapper.convertValue(params, MAP_TYPE);
            RESERVED_KEYS.forEach(values::remove);
            return values;
        }
        JsonNode properties = root.get("properties");
        if (properties != null && properties.isObject()) {
            Map<String, Object> values = objectMapper.convertValue(properties, MAP_TYPE);
            RESERVED_KEYS.forEach(values::remove);
            return values;
        }
        JsonNode desired = root.path("state").path("desired");
        if (desired.isObject()) {
            Map<String, Object> values = objectMapper.convertValue(desired, MAP_TYPE);
            RESERVED_KEYS.forEach(values::remove);
            return values;
        }

        Map<String, Object> direct = objectMapper.convertValue(root, MAP_TYPE);
        RESERVED_KEYS.forEach(direct::remove);
        return direct;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                String text = value.asText(null);
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private String firstString(Map<String, Object> map, String... fields) {
        if (map == null) {
            return null;
        }
        for (String field : fields) {
            Object value = map.get(field);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public byte[] buildResponsePayload(MqttDownlinkResult result) {
        try {
            return objectMapper.writeValueAsBytes(result.toResponseBody());
        } catch (Exception e) {
            String fallback = "{\"code\":500,\"msg\":\"build response failed\"}";
            return fallback.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static class WritePlan {
        private final Map<DataPoint, Object> pointsToWrite = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> fieldResults = new LinkedHashMap<>();

        private String resolveField(DataPoint point) {
            for (Map.Entry<String, Map<String, Object>> entry : fieldResults.entrySet()) {
                Object pointId = entry.getValue().get("pointId");
                Object pointCode = entry.getValue().get("pointCode");
                if (Objects.equals(point.getPointId(), pointId) || Objects.equals(point.getPointCode(), pointCode)) {
                    return entry.getKey();
                }
            }
            return point.getPointCode();
        }

        private int responseCode() {
            if (fieldResults.isEmpty()) {
                return 400;
            }
            long success = fieldResults.values().stream()
                    .filter(field -> Boolean.TRUE.equals(field.get("success")))
                    .count();
            if (success == fieldResults.size()) {
                return 0;
            }
            return success > 0 ? 207 : 500;
        }
    }
}
