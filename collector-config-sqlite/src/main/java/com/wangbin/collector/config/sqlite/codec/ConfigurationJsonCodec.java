package com.wangbin.collector.config.sqlite.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.collector.common.domain.cloud.CloudTargetConfig;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.scheduler.AdaptiveCollectionUtil;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.ConnectionConfiguration;
import com.wangbin.collector.core.config.persistence.DeviceConfiguration;
import com.wangbin.collector.core.config.persistence.PointConfiguration;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * wangbin 配置文档编解码器。仅导出显式字段白名单，不调用运行态实体 getter。
 * 扩展对象及其 JSON 字符串形态原样保留，解析时使用精确十进制和大整数。
 * 不记录负载，不向异常传播可能包含秘密的 Jackson 消息或原始异常。
 */
public final class ConfigurationJsonCodec {
    private static final List<String> DEVICE_FIELDS = List.of(
            "deviceId", "id", "deviceName", "deviceAlias", "productKey", "productName", "groupId", "groupName",
            "protocolType", "connectionType", "ipAddress", "port", "authConfig", "collectionInterval", "reportInterval",
            "cloudTarget", "maxRetryCount", "createTime", "updateTime", "remark", "configSource", "temporaryConfig");
    private static final List<String> CONNECTION_FIELDS = List.of(
            "id", "connectionType", "connectionKey", "host", "port", "url", "connectTimeoutMs", "readTimeoutMs",
            "writeTimeoutMs", "retries", "extJson", "remark", "connectionId", "deviceId", "deviceName", "timeout",
            "heartbeatInterval", "heartbeatTimeout", "subscriptionInterval", "maxFrameLength", "reconnectDelay",
            "maxReconnectTimes", "maxGroupConnections", "username", "password", "clientId", "productKey", "deviceSecret",
            "authToken", "securityPolicy", "authParams", "sslEnabled", "sslCertPath", "sslKeyPath", "charset", "keepAlive",
            "bufferSize", "autoReconnect", "initialReconnectDelay", "maxReconnectDelay", "maxReconnectAttempts",
            "reconnectBackoffMultiplier", "maxPendingMessages", "dispatchBatchSize", "dispatchFlushInterval",
            "overflowStrategy", "createTime", "updateTime");
    private static final List<String> POINT_FIELDS = List.of(
            "id", "unitId", "commonAddress", "pointId", "pointCode", "pointName", "pointAlias", "deviceId", "deviceName",
            "groupId", "address", "dataType", "readWrite", "scalingFactor", "offset", "deadband", "unit", "minValue",
            "maxValue", "collectionMode", "priority", "cacheEnabled", "cacheDuration", "alarmEnabled", "alarmRule",
            "status", "createTime", "updateTime", "precision", "remark", "additionalConfig", "baseCollectionInterval",
            "minCollectionInterval", "maxCollectionInterval", "pointChangeThreshold");
    private static final List<String> CLOUD_FIELDS = List.of(
            "enabled", "deviceType", "productKey", "deviceName", "topologyEnabled");
    private static final Set<String> RUNTIME_FIELDS = Set.of(
            "lastOnlineTime", "lastOfflineTime", "lastError", "retryCount", "connectTime", "disconnectTime",
            "lastHeartbeatTime", "lastDataTime", "connectionStats", "stats", "currentCollectionInterval",
            "stableCount", "lastValue", "changeRate", "lastAdjustTime", "reportFieldConflict", "reportConfigParsed",
            "configuredReportEnabled", "configuredReportField", "configuredChangeThreshold", "configuredChangeMinIntervalMs",
            "configuredEventEnabled", "configuredEventMinIntervalMs", "parsedAdditionalConfigRef", "parsedAdditionalConfigHash");
    private static final Set<String> CONFIGURATION_CONTAINERS = Set.of(
            "extJson", "authParams", "additionalConfig", "authConfig", "alarmRule");
    private static final Set<String> RUNTIME_STATUSES = Set.of(
            "ONLINE", "OFFLINE", "ERROR", "CONNECTED", "DISCONNECTED", "CONNECTING", "RECONNECTING");

    private final ObjectMapper mapper;
    private final ZoneId legacyTimeZone;
    private static final DateTimeFormatter LEGACY_DATE_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter(java.util.Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    public ConfigurationJsonCodec() {
        this(ZoneId.of("Asia/Shanghai"));
    }

    /** 无偏移旧日期采用明确来源时区，默认兼容已有JSON写出配置，不依赖部署机默认时区。 */
    public ConfigurationJsonCodec(ZoneId legacyTimeZone) {
        this.legacyTimeZone = Objects.requireNonNull(legacyTimeZone, "旧配置日期时区不能为空");
        mapper = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
    }

    /** 读取完整且无重复键的 JSON；调用方若先用其他解析器丢失精度，本方法无法恢复。 */
    public JsonNode readJson(String json) {
        if (json == null || json.isBlank()) throw invalid("配置 JSON 不能为空");
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null) throw invalid("配置 JSON 不能为空");
            return node;
        } catch (JsonProcessingException exception) {
            throw invalid("配置 JSON 格式不合法");
        }
    }

    /**
     * 模板、设置及远端快照的补充边界：拒绝已知运行态，防止借任意 JSON 入口偷渡整实体。
     * 明确的协议扩展和告警容器保留未知键，数字 status 等配置状态不受影响。
     */
    public JsonNode validateConfigurationPayload(String json) {
        JsonNode document = readJson(json);
        java.util.ArrayDeque<JsonNode> pending = new java.util.ArrayDeque<>();
        pending.push(document);
        while (!pending.isEmpty()) {
            JsonNode current = pending.pop();
            if (current.isArray()) {
                current.forEach(pending::push);
            } else if (current.isObject()) {
                for (var field : current.properties()) {
                    if (RUNTIME_FIELDS.contains(field.getKey()) || ("status".equals(field.getKey())
                            && field.getValue().isTextual()
                            && RUNTIME_STATUSES.contains(field.getValue().textValue().toUpperCase(java.util.Locale.ROOT)))) {
                        throw invalid("配置文档包含运行态字段，拒绝持久化");
                    }
                    if (!CONFIGURATION_CONTAINERS.contains(field.getKey())) pending.push(field.getValue());
                }
            }
        }
        return document;
    }

    /** 直接从旧文件单个 device/connection/points 聚合读取，禁止先反序列化运行态实体。 */
    public DeviceConfiguration decodeLocalDevice(String json) {
        return decodeLocalDevice(readJson(json));
    }

    /** 从精度未损失的原始树提取白名单；不修改输入树或稳定身份。 */
    public DeviceConfiguration decodeLocalDevice(JsonNode document) {
        ObjectNode root = object(document);
        ObjectNode device = select(object(root.get("device")), DEVICE_FIELDS);
        String deviceId = resolveDeviceId(device);
        if (!"local".equals(device.path("configSource").textValue())
                || !device.path("temporaryConfig").isBoolean() || !device.path("temporaryConfig").booleanValue()) {
            throw invalid("仅允许持久化 local 且 temporaryConfig=true 的本地设备");
        }
        validateObjectContainer(device.get("authConfig"), false);
        if (device.hasNonNull("cloudTarget")) {
            device.set("cloudTarget", select(object(device.get("cloudTarget")), CLOUD_FIELDS));
        }
        ConnectionConfiguration connection = null;
        JsonNode rawConnection = root.get("connection");
        if (rawConnection != null && !rawConnection.isNull()) {
            ObjectNode config = select(object(rawConnection), CONNECTION_FIELDS);
            requireOwner(config, deviceId);
            validateObjectContainer(config.get("extJson"), false);
            validateObjectContainer(config.get("authParams"), true);
            connection = new ConnectionConfiguration(deviceId, writeJson(config));
        }
        JsonNode rawPoints = root.get("points");
        if (rawPoints == null || !rawPoints.isArray()) throw invalid("点位列表必须为数组");
        List<PointConfiguration> points = new ArrayList<>();
        Set<String> pointIds = new HashSet<>();
        for (JsonNode rawPoint : rawPoints) {
            ObjectNode point = select(object(rawPoint), POINT_FIELDS);
            requireOwner(point, deviceId);
            String pointId = identity(point.get("pointId"));
            if (!pointIds.add(pointId)) throw invalid("点位稳定身份重复");
            validateObjectContainer(point.get("additionalConfig"), false);
            JsonNode rule = point.get("alarmRule");
            if (rule != null && !rule.isNull() && !rule.isTextual() && !rule.isArray() && !rule.isObject()) {
                throw invalid("告警规则必须为字符串、对象或数组");
            }
            points.add(new PointConfiguration(deviceId, pointId, points.size(), pointStatus(point), writeJson(point)));
        }
        return new DeviceConfiguration(deviceId, writeJson(device), connection, points);
    }

    /** 对完整导入数组预检设备和跨设备点位身份，全部合法后才返回不可变结果。 */
    public List<DeviceConfiguration> decodeLocalDevices(JsonNode documents) {
        if (documents == null || !documents.isArray()) throw invalid("设备集合必须为数组");
        List<DeviceConfiguration> result = new ArrayList<>();
        Set<String> devices = new HashSet<>();
        Set<String> points = new HashSet<>();
        for (JsonNode document : documents) {
            DeviceConfiguration config = decodeLocalDevice(document);
            if (!devices.add(config.deviceId())) throw invalid("设备稳定身份重复");
            for (PointConfiguration point : config.points()) {
                if (!points.add(point.pointId())) throw invalid("跨设备点位稳定身份重复");
            }
            result.add(config);
        }
        return List.copyOf(result);
    }

    /** 运行态转配置仅按声明字段读取；alarmRule 读取底层字符串，绝不走 typed getter。 */
    public DeviceConfiguration fromRuntime(DeviceInfo device, DeviceConnection connection, List<DataPoint> points) {
        if (device == null || points == null) throw invalid("设备及点位列表不能为空");
        ObjectNode root = mapper.createObjectNode();
        root.set("device", entityFields(device, DeviceInfo.class, DEVICE_FIELDS));
        root.set("connection", connection == null ? mapper.nullNode()
                : entityFields(connection, DeviceConnection.class, CONNECTION_FIELDS));
        ArrayNode pointNodes = root.putArray("points");
        for (DataPoint point : points) {
            if (point == null) throw invalid("点位不能为空");
            pointNodes.add(entityFields(point, DataPoint.class, POINT_FIELDS));
        }
        return decodeLocalDevice(root);
    }

    /** 白名单还原独立快照；逐字段赋值，绕过 common 中不精确的 Map 反序列化器。 */
    public DeviceContext toRuntime(DeviceConfiguration configuration) {
        String deviceId = configuration == null ? "[unknown]" : configuration.deviceId();
        ObjectNode root;
        try {
            root = encodeLocalDevice(configuration);
        } catch (IllegalArgumentException exception) {
            throw mappingFailure(deviceId, "configuration");
        }
        // 结构错误统一脱敏；字段还原错误已脱敏，保留具体字段便于定位不可恢复配置。
        return restoreDocument(root, deviceId);
    }

    private DeviceContext restoreDocument(ObjectNode root, String deviceId) {
        DeviceInfo device = new DeviceInfo();
        restoreFields(device, DeviceInfo.class, object(root.get("device")), DEVICE_FIELDS, deviceId, "device");
        DeviceConnection connection = null;
        if (!root.get("connection").isNull()) {
            connection = new DeviceConnection();
            restoreFields(connection, DeviceConnection.class, object(root.get("connection")), CONNECTION_FIELDS,
                    deviceId, "connection");
        }
        List<DataPoint> points = new ArrayList<>();
        for (JsonNode node : root.get("points")) {
            DataPoint point = new DataPoint();
            restoreFields(point, DataPoint.class, object(node), POINT_FIELDS, deviceId, "points");
            Long base = point.getBaseCollectionInterval();
            point.setCurrentCollectionInterval(base != null && base > 0
                    ? base : AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL);
            points.add(point);
        }
        return DeviceContext.of(device, connection, points);
    }

    /** 快照只提取原始配置字段，不序列化整个运行实体。 */
    public DeviceConfiguration fromRuntime(DeviceContext context) {
        if (context == null) throw invalid("设备配置快照不能为空");
        return fromRuntime(context.getDeviceInfo(), context.getConnectionConfig(), context.getDataPoints());
    }

    /** 时间元数据和 JSON 对象键序不影响配置版本，数组次序仍有意义。 */
    public boolean equivalent(DeviceContext left, DeviceContext right) {
        if (left == null || right == null) return left == right;
        ObjectNode first = comparisonDocument(fromRuntime(left));
        ObjectNode second = comparisonDocument(fromRuntime(right));
        return sameJson(first, second);
    }

    private ObjectNode comparisonDocument(DeviceConfiguration configuration) {
        ObjectNode document = encodeLocalDevice(configuration);
        normalizeEntity(object(document.get("device")), configuration.deviceId());
        object(document.get("device")).remove("id");
        if (!document.get("connection").isNull()) normalizeEntity(object(document.get("connection")), configuration.deviceId());
        for (JsonNode point : document.get("points")) normalizeEntity(object(point), configuration.deviceId());
        return document;
    }

    private void normalizeEntity(ObjectNode entity, String deviceId) {
        entity.remove(List.of("createTime", "updateTime"));
        for (String name : CONFIGURATION_CONTAINERS) {
            JsonNode value = entity.get(name);
            if (value == null || !value.isTextual() || value.textValue().isBlank()) continue;
            try {
                entity.set(name, readJson(value.textValue()));
            } catch (IllegalArgumentException exception) {
                throw mappingFailure(deviceId, name);
            }
        }
    }

    private boolean sameJson(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue()) == 0;
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) return false;
            for (var field : left.properties()) {
                JsonNode other = right.get(field.getKey());
                if (other == null || !sameJson(field.getValue(), other)) return false;
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                if (!sameJson(left.get(index), right.get(index))) return false;
            }
            return true;
        }
        return left.equals(right);
    }

    private void restoreFields(Object entity, Class<?> type, ObjectNode node, List<String> fields,
                               String deviceId, String prefix) {
        for (String name : fields) {
            if (!node.has(name) || (type == DeviceInfo.class && "id".equals(name))) continue;
            String fieldName = switch (name) {
                case "connectTimeoutMs" -> "connectTimeout";
                case "readTimeoutMs" -> "readTimeout";
                case "writeTimeoutMs" -> "writeTimeout";
                default -> name;
            };
            try {
                Field field = type.getDeclaredField(fieldName);
                if (!field.trySetAccessible()) throw mappingFailure(deviceId, prefix + "." + name);
                JsonNode value = node.get(name);
                Object restored;
                if ("alarmRule".equals(name)) {
                    restored = restoreAlarm(value);
                } else if (field.getType() == java.util.Map.class) {
                    JsonNode map = value.isTextual() ? readJson(value.textValue()) : value;
                    if (!map.isNull() && !map.isObject()) throw invalid("配置扩展容器必须为对象");
                    restored = plainValue(map);
                } else if (field.getType() == CloudTargetConfig.class && !value.isNull()) {
                    CloudTargetConfig cloud = new CloudTargetConfig();
                    restoreFields(cloud, CloudTargetConfig.class, object(value), CLOUD_FIELDS, deviceId, prefix + ".cloudTarget");
                    restored = cloud;
                } else {
                    restored = scalar(value, field.getType());
                }
                // 直接写底层字段，避免 setter 的空对象归一化和 typed 告警反序列化损失。
                field.set(entity, restored);
            } catch (ReflectiveOperationException | JsonProcessingException | RuntimeException exception) {
                throw mappingFailure(deviceId, prefix + "." + name);
            }
        }
    }

    private Object restoreAlarm(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isTextual()) {
            String raw = value.textValue();
            if (raw.isBlank()) return raw;
            JsonNode parsed = readJson(raw);
            if (!parsed.isArray() && !parsed.isObject()) throw invalid("告警规则必须为对象或数组");
            return raw;
        }
        if (!value.isArray() && !value.isObject()) throw invalid("告警规则必须为对象或数组");
        return writeJson(value);
    }

    private Object plainValue(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isObject()) {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (var field : value.properties()) result.put(field.getKey(), plainValue(field.getValue()));
            return result;
        }
        if (value.isArray()) {
            List<Object> result = new ArrayList<>(value.size());
            value.forEach(item -> result.add(plainValue(item)));
            return result;
        }
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.bigIntegerValue();
        if (value.isFloatingPointNumber()) return value.decimalValue();
        throw invalid("配置值类型不合法");
    }

    private Object scalar(JsonNode value, Class<?> type) throws JsonProcessingException {
        if (value.isNull()) {
            if (type.isPrimitive()) throw invalid("基础类型配置不可为 null");
            return null;
        }
        if (type == String.class && value.isTextual()) return value.textValue();
        if ((type == Integer.class || type == int.class) && value.isIntegralNumber() && value.canConvertToInt()) return value.intValue();
        if ((type == Long.class || type == long.class) && value.isIntegralNumber() && value.canConvertToLong()) return value.longValue();
        if ((type == Double.class || type == double.class) && value.isNumber() && Double.isFinite(value.doubleValue())) return value.doubleValue();
        if ((type == Boolean.class || type == boolean.class) && value.isBoolean()) return value.booleanValue();
        if (type == Charset.class && value.isTextual()) return Charset.forName(value.textValue());
        if (type == java.util.Date.class && ((value.isIntegralNumber() && value.canConvertToLong()) || value.isTextual())) {
            if (value.isIntegralNumber()) return new java.util.Date(value.longValue());
            String text = value.textValue();
            try {
                return java.util.Date.from(OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
            } catch (java.time.format.DateTimeParseException exception) {
                // 无偏移旧JSON使用确定的来源时区；严格拒绝非法日期，不做宽松滚动修复。
                return java.util.Date.from(LocalDateTime.parse(text, LEGACY_DATE_FORMAT).atZone(legacyTimeZone).toInstant());
            }
        }
        if (type.isEnum() && value.isTextual()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(value.textValue())) return constant;
            }
        }
        throw invalid("配置字段类型或范围不合法");
    }

    private IllegalArgumentException mappingFailure(String deviceId, String field) {
        return invalid("设备 " + deviceId + " 的配置字段 " + field + " 无法还原");
    }

    /** 输出新的 JSON 树；模型的冗余身份、状态、白名单须全部一致，不能通过此入口偷渡运行态。 */
    public ObjectNode encodeLocalDevice(DeviceConfiguration configuration) {
        ObjectNode root = document(configuration);
        validateLocalDevice(configuration, root);
        return root;
    }

    /** 数据库写入口必须调用：拒绝模型身份与 JSON 不一致、运行态字段或来源污染。 */
    public void validateLocalDevice(DeviceConfiguration configuration) {
        ObjectNode root = document(configuration);
        validateLocalDevice(configuration, root);
        // SQL提交前走同一恢复路径，不能将下次启动无法读取的配置写入权威库。
        restoreDocument(root, configuration.deviceId());
    }

    private void validateLocalDevice(DeviceConfiguration configuration, ObjectNode root) {
        DeviceConfiguration decoded = decodeLocalDevice(root);
        if (!configuration.deviceId().equals(decoded.deviceId())
                || !root.equals(document(decoded)) || configuration.points().size() != decoded.points().size()) {
            throw invalid("配置模型与 JSON 身份或白名单不一致");
        }
        for (int i = 0; i < configuration.points().size(); i++) {
            PointConfiguration point = configuration.points().get(i);
            PointConfiguration other = decoded.points().get(i);
            if (!point.pointId().equals(other.pointId()) || !Objects.equals(point.status(), other.status())) {
                throw invalid("点位模型与 JSON 身份或配置状态不一致");
            }
        }
    }

    private ObjectNode document(DeviceConfiguration configuration) {
        if (configuration == null) throw invalid("设备配置不能为空");
        ObjectNode root = mapper.createObjectNode();
        root.set("device", object(readJson(configuration.deviceJson())));
        root.set("connection", configuration.connection() == null ? mapper.nullNode()
                : object(readJson(configuration.connection().configJson())));
        ArrayNode points = root.putArray("points");
        for (PointConfiguration point : configuration.points()) points.add(object(readJson(point.configJson())));
        return root;
    }

    private ObjectNode entityFields(Object entity, Class<?> type, List<String> fields) {
        ObjectNode result = mapper.createObjectNode();
        for (String name : fields) {
            // 设备 id 是派生兼容 getter，而不是独立字段，不生成第二个身份。
            if (type == DeviceInfo.class && "id".equals(name)) continue;
            String fieldName = switch (name) {
                case "connectTimeoutMs" -> "connectTimeout";
                case "readTimeoutMs" -> "readTimeout";
                case "writeTimeoutMs" -> "writeTimeout";
                default -> name;
            };
            try {
                Field field = type.getDeclaredField(fieldName);
                if (!field.trySetAccessible()) throw invalid("无法读取配置白名单字段");
                Object value = field.get(entity);
                if (value instanceof CloudTargetConfig target) {
                    result.set(name, entityFields(target, CloudTargetConfig.class, CLOUD_FIELDS));
                } else if (value instanceof Charset charset) {
                    result.put(name, charset.name());
                } else {
                    result.set(name, mapper.valueToTree(value));
                }
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                throw invalid("配置白名单字段读取失败");
            }
        }
        // 统一重新解析标量节点，让实体路径和原始文件路径的数值精度策略相同。
        return object(readJson(writeJson(result)));
    }

    private String resolveDeviceId(ObjectNode device) {
        JsonNode canonical = device.get("deviceId");
        JsonNode alias = device.get("id");
        if (canonical == null) {
            String id = identity(alias);
            device.put("deviceId", id);
            return id;
        }
        String id = identity(canonical);
        if (alias != null && !id.equals(identity(alias))) throw invalid("deviceId 与历史 id 身份冲突");
        return id;
    }

    private void requireOwner(ObjectNode node, String deviceId) {
        if (!deviceId.equals(identity(node.get("deviceId")))) throw invalid("连接或点位的设备归属不一致");
    }

    private String identity(JsonNode node) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) throw invalid("稳定身份必须为非空字符串");
        return node.textValue();
    }

    private Integer pointStatus(ObjectNode point) {
        JsonNode node = point.get("status");
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw invalid("点位配置状态必须为整数或 null");
        return node.intValue();
    }

    private void validateObjectContainer(JsonNode container, boolean stringValues) {
        if (container == null || container.isNull()) return;
        JsonNode value = container.isTextual() ? readJson(container.textValue()) : container;
        if (!value.isObject()) throw invalid("配置扩展容器必须为对象或对象 JSON 字符串");
        if (stringValues) {
            for (JsonNode item : value) {
                if (!item.isNull() && !item.isTextual()) throw invalid("authParams 值必须为字符串或 null，禁止隐式转换");
            }
        }
    }

    private ObjectNode select(ObjectNode source, List<String> fields) {
        ObjectNode result = mapper.createObjectNode();
        for (String field : fields) {
            if (source.has(field)) result.set(field, source.get(field).deepCopy());
        }
        return result;
    }

    private ObjectNode object(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("配置文档必须为 JSON 对象");
        return (ObjectNode) node;
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw invalid("配置 JSON 编码失败");
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
