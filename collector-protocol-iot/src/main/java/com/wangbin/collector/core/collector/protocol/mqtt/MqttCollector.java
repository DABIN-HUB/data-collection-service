package com.wangbin.collector.core.collector.protocol.mqtt;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONPath;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.scheduler.ProtocolPointSelectionSupport;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.validator.MqttConfigurationContract;
import com.wangbin.collector.core.connection.adapter.MqttConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttReceivedMessage;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.wangbin.collector.core.collector.protocol.mqtt.MqttCollectorUtils.asBoolean;


/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class MqttCollector extends ConnectionBackedCollector implements ProtocolPointSelectionSupport {

    private CollectorProperties.MqttConfig defaultConfig;
    private MqttConnectionAdapter mqttConnection;
    private final Consumer<MqttReceivedMessage> inboundListener = this::handleInboundMessage;

    private final Map<String, DataPoint> pointDefinitions = new ConcurrentHashMap<>();
    private final Map<String, MqttPointOptions> pointOptions = new ConcurrentHashMap<>();
    private final Map<String, Object> latestValues = new ConcurrentHashMap<>();
    private final Map<String, Long> latestTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> topicBindings = new ConcurrentHashMap<>();
    private final Map<String, Integer> topicRefCount = new ConcurrentHashMap<>();
    private final Set<String> baseSubscribedTopics = ConcurrentHashMap.newKeySet();

    @Override
    public String getCollectorType() {
        return "MQTT";
    }

    @Override
    public String getProtocolType() {
        return "MQTT";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        initConfig();
        // Read plans are rebuilt by the lifecycle coordinator after connect. Register the
        // listener before connecting; the rebuild then binds points before the first subscribe.
        MqttConnectionAdapter adapter = null;
        try {
            adapter = requireAdapterType(createManagedConnection(), MqttConnectionAdapter.class, "MQTT");
            adapter.addMessageListener(inboundListener);
            this.mqttConnection = adapter;
            connectManagedConnection();
        } catch (Exception failure) {
            if (adapter != null) adapter.removeMessageListener(inboundListener);
            removeManagedConnection("MQTT");
            mqttConnection = null;
            topicBindings.clear();
            topicRefCount.clear();
            baseSubscribedTopics.clear();
            throw failure;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        if (mqttConnection != null) {
            mqttConnection.removeMessageListener(inboundListener);
        }
        removeManagedConnection("MQTT");
        mqttConnection = null;
        topicBindings.clear();
        topicRefCount.clear();
        baseSubscribedTopics.clear();
        latestValues.clear();
        latestTimestamps.clear();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) {
        return latestValues.get(point.getPointId());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        Map<String, Object> result = new HashMap<>();
        if (points == null) {
            return result;
        }
        for (DataPoint point : points) {
            Object value = latestValues.get(point.getPointId());
            if (value != null) {
                result.put(point.getPointId(), value);
            }
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        MqttPointOptions options = resolvePointOptions(point);
        byte[] payload = buildPayloadForWrite(point, value, options);
        publish(options.getWriteTopic(), payload, options.getQos(), options.isRetain());
        return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) throws Exception {
        Map<String, Boolean> result = new ConcurrentHashMap<>();
        if (points == null) {
            return result;
        }
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            boolean success = doWritePoint(entry.getKey(), entry.getValue());
            result.put(entry.getKey().getPointId(), success);
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return;
        }
        for (DataPoint point : points) {
            MqttPointOptions options = resolvePointOptions(point);
            bindPointToTopic(point, options);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            for (String topic : List.copyOf(topicBindings.keySet())) {
                Set<String> bindings = topicBindings.get(topic);
                if (bindings != null) {
                    for (String pointId : List.copyOf(bindings)) {
                        removePointFromTopic(pointId, topic);
                    }
                }
                if (!baseSubscribedTopics.contains(topic)) {
                    topicRefCount.remove(topic);
                }
            }
            pointOptions.clear();
            pointDefinitions.clear();
            latestValues.clear();
            latestTimestamps.clear();
            return;
        }
        for (DataPoint point : points) {
            MqttPointOptions options = pointOptions.remove(point.getPointId());
            pointDefinitions.remove(point.getPointId());
            latestValues.remove(point.getPointId());
            latestTimestamps.remove(point.getPointId());
            if (options != null) {
                removePointFromTopic(point.getPointId(), options.getTopic());
            }
        }
    }

    @Override
    public List<DataPoint> filterPollingPoints(List<DataPoint> points) {
        if (points == null) {
            return List.of();
        }
        return points.stream()
                .filter(point -> "POLLING".equalsIgnoreCase(point.getCollectionMode()))
                .toList();
    }

    @Override
    public List<DataPoint> filterAutoSubscriptionPoints(List<DataPoint> points) {
        if (points == null) {
            return List.of();
        }
        return points.stream().filter(this::isSubscribeMode).toList();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new ConcurrentHashMap<>();
        status.put("brokerUrl", getBrokerUrl());
        status.put("clientId", getClientId());
        status.put("protocolVersion", getProtocolVersion());
        status.put(CommonMapKeys.CONNECTED, mqttConnection != null && mqttConnection.isConnected());
        status.put("subscriptions", topicBindings.keySet());
        status.put("cachedPoints", latestValues.size());
        status.put("lastTimestamps", latestTimestamps);
        status.put("connectionStats", mqttConnection != null ? mqttConnection.getStatistics() : Collections.emptyMap());
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        String normalized = command != null ? command.toLowerCase(Locale.ROOT) : "";
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        return switch (normalized) {
            case "publish" -> executePublishCommand(safeParams);
            case "subscribe" -> executeSubscribeCommand(safeParams);
            case "unsubscribe" -> executeUnsubscribeCommand(safeParams);
            case "status" -> doGetDeviceStatus();
            default -> throw new IllegalArgumentException("Unsupported MQTT command: " + command);
        };
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    public synchronized void rebuildReadPlans(String deviceId, List<DataPoint> points) {
        try {
            buildReadPlans(deviceId, points);
        } catch (RuntimeException failure) {
            lastError = failure.getMessage();
            connectionStatus = "ERROR";
            throw failure;
        }
    }

    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        Map<String, DataPoint> replacement = new HashMap<>();
        Map<String, MqttPointOptions> replacementOptions = new HashMap<>();
        int defaultQos = getDefaultQos();
        for (DataPoint point : points == null ? List.<DataPoint>of() : points) {
            replacement.put(point.getPointId(), point);
            replacementOptions.put(point.getPointId(), MqttPointOptions.from(point, defaultQos,
                    deviceInfo != null ? deviceInfo.getDeviceId() : null));
        }
        for (String oldId : List.copyOf(pointDefinitions.keySet())) {
            MqttPointOptions old = pointOptions.get(oldId);
            MqttPointOptions next = replacementOptions.get(oldId);
            boolean removeBinding = old != null && (next == null || !isSubscribeMode(replacement.get(oldId))
                    || !old.getTopic().equals(next.getTopic()));
            if (removeBinding && mqttConnection != null) {
                try { removePointFromTopic(oldId, old.getTopic()); }
                catch (Exception exception) { throw new IllegalStateException("MQTT obsolete binding cleanup failed", exception); }
            }
            if (!replacement.containsKey(oldId) || removeBinding) {
                latestValues.remove(oldId);
                latestTimestamps.remove(oldId);
            }
        }
        pointDefinitions.clear();
        pointDefinitions.putAll(replacement);
        pointOptions.clear();
        pointOptions.putAll(replacementOptions);
        if (mqttConnection != null) {
            for (DataPoint point : replacement.values()) {
                try {
                    MqttPointOptions options = replacementOptions.get(point.getPointId());
                    if (isSubscribeMode(point)) bindPointToTopic(point, options);
                }
                catch (Exception exception) { throw new IllegalStateException("MQTT binding refresh failed", exception); }
            }
            for (MqttTopicSubscription subscription : getDefaultSubscriptions()) {
                try { addBaseSubscription(subscription.getTopic(), subscription.getQos()); }
                catch (Exception exception) { throw new IllegalStateException("MQTT default subscription failed", exception); }
            }
        }
        log.info("MQTT 点位加载完成，数量={}，设备={}", pointOptions.size(), deviceId);
    }

    private boolean isSubscribeMode(DataPoint point) {
        String mode = point == null ? null : point.getCollectionMode();
        return mode == null || mode.isBlank() || "SUBSCRIBE".equalsIgnoreCase(mode);
    }

    /**
     * 处理组件生命周期。
     */
    private void initConfig() {
        this.defaultConfig = collectorProperties != null
                ? collectorProperties.getMqtt()
                : new CollectorProperties.MqttConfig();
    }


    private Map<String, Object> getConnectionProperties() {
        DeviceConnection connection = getCurrentConnectionConfig();
        Map<String, Object> props = connection != null ? connection.getExtJson() : null;
        return props != null ? props : Collections.emptyMap();
    }

    private String getBrokerUrl() {
        DeviceConnection connection = getCurrentConnectionConfig();
        String url = connection != null ? connection.getUrl() : null;
        if (url == null || url.isBlank()) {
            url = toString(getConnectionProperties().get("brokerUrl"), "N/A");
        }
        return url != null ? url : "N/A";
    }

    private String getClientId() {
        DeviceConnection connection = getCurrentConnectionConfig();
        String clientId = connection != null ? connection.getClientId() : null;
        if (clientId == null || clientId.isBlank()) {
            clientId = toString(getConnectionProperties().get("clientId"), deviceInfo.getDeviceId() + "_mqtt");
        }
        return clientId;
    }

    private String getProtocolVersion() {
        return toString(getConnectionProperties().get("version"), "UNKNOWN");
    }

    private int getDefaultQos() {
        return MqttConfigurationContract.qos(getConnectionProperties().get("subscribeQos"),
                defaultConfig != null ? defaultConfig.getQos() : 1, "subscribeQos");
    }

    private List<MqttTopicSubscription> getDefaultSubscriptions() {
        Object configuredTopics = getConnectionProperties().get("subscribeTopics");
        return parseTopics(configuredTopics, getDefaultQos());
    }

    private Object resolveTopicTemplate(Object value) {
        if (value == null) {
            return value;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("deviceId", deviceInfo == null ? null : deviceInfo.getDeviceId());
        values.put("device_id", deviceInfo == null ? null : deviceInfo.getDeviceId());
        return ProtocolTemplateResolver.resolve(value.toString(), values);
    }

    /**
     * 解析或转换业务数据。
     */
    private List<MqttTopicSubscription> parseTopics(Object value, int defaultQos) {
        List<MqttTopicSubscription> topics = new ArrayList<>();
        if (value == null) {
            return topics;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item == null) {
                    continue;
                }
                String text = resolveTopicTemplate(item).toString().trim();
                if (!text.isEmpty()) {
                    MqttConfigurationContract.filter(text);
                    topics.add(new MqttTopicSubscription(text, defaultQos));
                }
            }
            return topics;
        }
        String[] parts = value.toString().split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                String topic = resolveTopicTemplate(trimmed).toString();
                MqttConfigurationContract.filter(topic);
                topics.add(new MqttTopicSubscription(topic, defaultQos));
            }
        }
        return topics;
    }

    private int getIntValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private long getLongValue(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private boolean getBooleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * 执行当前业务逻辑。
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private String toString(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * 解析或转换业务数据。
     */
    private String toString(Object value, String defaultValue) {
        return value != null ? value.toString() : defaultValue;
    }

    private boolean isSslScheme(String scheme) {
        if (scheme == null) {
            return false;
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        return lower.startsWith("ssl") || lower.startsWith("tls") || lower.startsWith("mqtts");
    }

    /**
     * 解析或转换业务数据。
     */
    private MqttPointOptions resolvePointOptions(DataPoint point) {
        return pointOptions.computeIfAbsent(point.getPointId(),
                id -> MqttPointOptions.from(point, getDefaultQos(),
                        deviceInfo != null ? deviceInfo.getDeviceId() : null));
    }

    /**
     * 执行当前业务逻辑。
     */
    private void bindPointToTopic(DataPoint point, MqttPointOptions options) throws Exception {
        String pointId = point.getPointId();
        for (String bound : List.copyOf(topicBindings.keySet())) {
            if (!bound.equals(options.getTopic()) && topicBindings.get(bound).contains(pointId)) {
                removePointFromTopic(pointId, bound);
                latestValues.remove(pointId);
                latestTimestamps.remove(pointId);
            }
        }
        Set<String> bindings = topicBindings.computeIfAbsent(options.getTopic(), t -> ConcurrentHashMap.newKeySet());
        if (bindings.add(pointId)) {
            try {
                ensureTopicSubscription(options.getTopic(), options.getQos());
            } catch (Exception failure) {
                bindings.remove(pointId);
                if (bindings.isEmpty()) topicBindings.remove(options.getTopic(), bindings);
                throw failure;
            }
        }
        pointDefinitions.put(pointId, point);
        pointOptions.put(pointId, options);
    }

    /**
     * 清理或删除业务数据。
     */
    private void removePointFromTopic(String pointId, String topic) throws Exception {
        Set<String> bindings = topicBindings.get(topic);
        if (bindings != null && bindings.remove(pointId)) {
            if (bindings.isEmpty()) topicBindings.remove(topic, bindings);
            decrementTopicSubscription(topic);
            reconcileTopicQos(topic);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private synchronized void ensureTopicSubscription(String topic, int qos) throws Exception {
        MqttConfigurationContract.filter(topic);
        int count = topicRefCount.getOrDefault(topic, 0);
        if (mqttConnection == null) {
            // Logical point binding is allowed before the wire connection exists.
            // Only the adapter performs wire subscriptions once it has connected.
            topicRefCount.put(topic, count + 1);
            return;
        }
        if (!mqttConnection.isSubscribed(topic) || mqttConnection.getSubscribedQos(topic) < qos) {
            mqttConnection.subscribe(topic, qos);
        }
        topicRefCount.put(topic, count + 1);
    }

    private void reconcileTopicQos(String topic) throws Exception {
        Set<String> bindings = topicBindings.get(topic);
        if (mqttConnection == null || (bindings == null || bindings.isEmpty()) && !baseSubscribedTopics.contains(topic)) {
            return;
        }
        int desired = 0;
        if (bindings != null) {
            for (String pointId : bindings) {
                MqttPointOptions options = pointOptions.get(pointId);
                if (options != null) desired = Math.max(desired, options.getQos());
            }
        }
        if (baseSubscribedTopics.contains(topic)) {
            desired = Math.max(desired, getDefaultQos());
        }
        if (mqttConnection.getSubscribedQos(topic) != desired) {
            mqttConnection.subscribe(topic, desired);
        }
    }

    private synchronized void addBaseSubscription(String topic, int qos) throws Exception {
        MqttConfigurationContract.filter(topic);
        if (baseSubscribedTopics.add(topic)) {
            try {
                if (!mqttConnection.isSubscribed(topic)) mqttConnection.subscribe(topic, qos);
                topicRefCount.merge(topic, 1, Integer::sum);
            } catch (Exception failure) {
                baseSubscribedTopics.remove(topic);
                throw failure;
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private synchronized void decrementTopicSubscription(String topic) throws Exception {
        Integer current = topicRefCount.get(topic);
        if (current == null) {
            return;
        }
        if (current <= 1) {
            if (baseSubscribedTopics.contains(topic)) topicRefCount.put(topic, 1);
            else topicRefCount.remove(topic);
            if (!baseSubscribedTopics.contains(topic) && mqttConnection != null) {
                mqttConnection.unsubscribe(topic);
            }
        } else {
            topicRefCount.put(topic, current - 1);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void publish(String topic, byte[] payload, int qos, boolean retained) throws Exception {
        if (mqttConnection == null || !mqttConnection.isConnected()) {
            throw new IllegalStateException("MQTT client not connected");
        }
        mqttConnection.publish(topic, payload, qos, retained);
    }

    /**
     * 创建并返回业务对象。
     */
    private byte[] buildPayloadForWrite(DataPoint point, Object value, MqttPointOptions options) {
        String payloadText;
        if (options.getPublishTemplate() != null && !options.getPublishTemplate().isBlank()) {
            Map<String, Object> values = new HashMap<>();
            values.put("value", value);
            values.put("pointId", point.getPointId());
            values.put("pointCode", point.getPointCode());
            values.put("deviceId", deviceInfo != null ? deviceInfo.getDeviceId() : null);
            values.put("timestamp", System.currentTimeMillis());
            payloadText = MqttConfigurationContract.resolveTemplate(options.getPublishTemplate(), values,
                    Set.of("value", "pointId", "pointCode", "deviceId", "timestamp"));
        } else {
            payloadText = "JSON".equals(options.getPayloadEncoding())
                    ? JSON.toJSONString(value)
                    : Objects.toString(value, "");
        }
        byte[] encoded = payloadText.getBytes(options.getCharset());
        return switch (options.getPayloadEncoding()) {
            case "BASE64" -> Base64.getEncoder().encode(encoded);
            case "HEX" -> toHex(encoded).getBytes(StandardCharsets.US_ASCII);
            default -> encoded;
        };
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    /**
     * 处理当前业务流程。
     */
    private Object executePublishCommand(Map<String, Object> params) throws Exception {
        String topic = Objects.toString(resolveTopicTemplate(params.get(CommonMapKeys.TOPIC)), "");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        MqttConfigurationContract.publishTopic(topic);
        Object payloadObj = params.getOrDefault("payload", "");
        int qos = MqttConfigurationContract.qos(params.get("qos"), getDefaultQos(), "command qos");
        boolean retained = asBoolean(params.get("retained"), false);
        byte[] payload = Objects.toString(payloadObj, "").getBytes(StandardCharsets.UTF_8);
        publish(topic, payload, qos, retained);
        return Map.of("topic", topic, "status", "success");
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeSubscribeCommand(Map<String, Object> params) throws Exception {
        String topic = Objects.toString(resolveTopicTemplate(params.get(CommonMapKeys.TOPIC)), "");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        MqttConfigurationContract.filter(topic);
        int qos = MqttConfigurationContract.qos(params.get("qos"), getDefaultQos(), "command qos");
        addBaseSubscription(topic, qos);
        return Map.of("topic", topic, "status", "subscribed");
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeUnsubscribeCommand(Map<String, Object> params) throws Exception {
        String topic = Objects.toString(resolveTopicTemplate(params.get(CommonMapKeys.TOPIC)), "");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        MqttConfigurationContract.filter(topic);
        if (baseSubscribedTopics.remove(topic)) decrementTopicSubscription(topic);
        return Map.of("topic", topic, "status", "unsubscribed");
    }

    /**
     * 处理当前业务流程。
     */
    private void handleInboundMessage(MqttReceivedMessage message) {
        if (message == null) {
            return;
        }
        MqttMessageEnvelope envelope = new MqttMessageEnvelope(
                message.getPayload(),
                message.getQos(),
                message.isRetained(),
                message.getUserProperties());
        handleIncomingMessage(message.getTopic(), envelope);
    }

    /**
     * 处理当前业务流程。
     */
    private void handleIncomingMessage(String topic, MqttMessageEnvelope envelope) {
        Set<String> bindings = topicBindings.entrySet().stream()
                .filter(entry -> MqttTopicFilterMatcher.matches(entry.getKey(), topic))
                .flatMap(entry -> entry.getValue().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (bindings.isEmpty()) {
            log.debug("收到无绑定点位的 MQTT 消息，主题={}", topic);
            return;
        }
        for (String pointId : bindings) {
            DataPoint point = pointDefinitions.get(pointId);
            MqttPointOptions options = pointOptions.get(pointId);
            if (point == null || options == null) {
                continue;
            }
            try {
                Object converted = convertPayload(point, options, envelope.getPayload());
                if (converted != null) {
                    latestValues.put(pointId, converted);
                    latestTimestamps.put(pointId, System.currentTimeMillis());
                    ingestPushedValue(point, converted);
                }
            } catch (Exception ex) {
                log.warn("解析 MQTT 消息失败，点位={}, 主题={}", pointId, topic, ex);
            }
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Object convertPayload(DataPoint point, MqttPointOptions options, byte[] payload) {
        if (payload == null) {
            return null;
        }
        byte[] actualPayload = payload;
        switch (options.getPayloadEncoding()) {
            case "BASE64" -> actualPayload = Base64.getDecoder().decode(payload);
            case "HEX" -> actualPayload = decodeHex(payload);
            case "JSON", "PLAIN_TEXT", "AUTO_COMPAT" -> { }
            default -> throw new IllegalArgumentException("Unsupported MQTT payload encoding");
        }
        String text = new String(actualPayload, options.getCharset());
        if ("PLAIN_TEXT".equals(options.getPayloadEncoding())) {
            return text;
        }
        boolean jsonRequired = "JSON".equals(options.getPayloadEncoding())
                || (options.getJsonPath() != null && !options.getJsonPath().isBlank());
        Object raw;
        try {
            raw = JSON.parse(text);
        } catch (Exception parseFailure) {
            if (!jsonRequired) {
                return text;
            }
            throw parseFailure;
        }
        if (options.getJsonPath() != null && !options.getJsonPath().isBlank()) {
            return JSONPath.eval(raw, options.getJsonPath());
        }
        if (raw instanceof Map<?, ?> map && map.containsKey("value")) {
            return map.get("value");
        }
        if (raw instanceof Map<?, ?> || raw instanceof Collection<?> || raw == null) {
            throw new IllegalArgumentException("MQTT JSON payload requires scalar or value field");
        }
        return raw;
    }

    private byte[] decodeHex(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8).trim();
        if ((text.length() & 1) != 0 || !text.matches("[0-9a-fA-F]*")) {
            throw new IllegalArgumentException("MQTT HEX payload must contain an even number of hex digits");
        }
        byte[] result = new byte[text.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    /**
     * 解析或转换业务数据。
     */
}
