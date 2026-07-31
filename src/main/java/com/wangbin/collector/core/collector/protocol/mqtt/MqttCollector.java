package com.wangbin.collector.core.collector.protocol.mqtt;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONPath;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.connection.adapter.MqttConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttReceivedMessage;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.wangbin.collector.core.collector.protocol.mqtt.MqttCollectorUtils.asBoolean;
import static com.wangbin.collector.core.collector.protocol.mqtt.MqttCollectorUtils.asInt;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class MqttCollector extends ConnectionBackedCollector {

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
        this.mqttConnection = createAndConnectAdapter(MqttConnectionAdapter.class, "MQTT");
        this.mqttConnection.addMessageListener(inboundListener);
        for (MqttTopicSubscription subscription : getDefaultSubscriptions()) {
            ensureTopicSubscription(subscription.getTopic(), subscription.getQos());
            baseSubscribedTopics.add(subscription.getTopic());
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
        Map<String, Object> result = new ConcurrentHashMap<>();
        if (points == null) {
            return result;
        }
        for (DataPoint point : points) {
            result.put(point.getPointId(), latestValues.get(point.getPointId()));
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        MqttPointOptions options = resolvePointOptions(point);
        byte[] payload = buildPayloadForWrite(value, options);
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
                if (!baseSubscribedTopics.contains(topic)) {
                    if (mqttConnection != null) {
                        mqttConnection.unsubscribe(topic);
                    }
                    topicRefCount.remove(topic);
                }
            }
            topicBindings.clear();
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

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new ConcurrentHashMap<>();
        status.put("brokerUrl", getBrokerUrl());
        status.put("clientId", getClientId());
        status.put("protocolVersion", getProtocolVersion());
        status.put("connected", mqttConnection != null && mqttConnection.isConnected());
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
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        if (points == null) {
            return;
        }
        int defaultQos = getDefaultQos();
        for (DataPoint point : points) {
            pointDefinitions.put(point.getPointId(), point);
            pointOptions.put(point.getPointId(), MqttPointOptions.from(point, defaultQos));
        }
        log.info("MQTT 点位加载完成，数量={}，设备={}", pointOptions.size(), deviceId);
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
        return getIntValue(getConnectionProperties().get("subscribeQos"),
                defaultConfig != null ? defaultConfig.getQos() : 1);
    }

    private List<MqttTopicSubscription> getDefaultSubscriptions() {
        return parseTopics(getConnectionProperties().get("subscribeTopics"), getDefaultQos());
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
                String text = item.toString().trim();
                if (!text.isEmpty()) {
                    topics.add(new MqttTopicSubscription(text, defaultQos));
                }
            }
            return topics;
        }
        String[] parts = value.toString().split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                topics.add(new MqttTopicSubscription(trimmed, defaultQos));
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
                id -> MqttPointOptions.from(point, getDefaultQos()));
    }

    /**
     * 执行当前业务逻辑。
     */
    private void bindPointToTopic(DataPoint point, MqttPointOptions options) throws Exception {
        pointDefinitions.put(point.getPointId(), point);
        topicBindings.computeIfAbsent(options.getTopic(), t -> ConcurrentHashMap.newKeySet())
                .add(point.getPointId());
        ensureTopicSubscription(options.getTopic(), options.getQos());
    }

    /**
     * 清理或删除业务数据。
     */
    private void removePointFromTopic(String pointId, String topic) throws Exception {
        Set<String> bindings = topicBindings.get(topic);
        if (bindings != null) {
            bindings.remove(pointId);
            if (bindings.isEmpty() && !baseSubscribedTopics.contains(topic)) {
                topicBindings.remove(topic);
            }
        }
        decrementTopicSubscription(topic);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private synchronized void ensureTopicSubscription(String topic, int qos) throws Exception {
        if (topic == null || topic.isBlank()) {
            return;
        }
        if (mqttConnection == null) {
            throw new IllegalStateException("MQTT连接未建立");
        }
        int count = topicRefCount.getOrDefault(topic, 0);
        if (count == 0 && !mqttConnection.isSubscribed(topic)) {
            mqttConnection.subscribe(topic, qos);
        }
        topicRefCount.put(topic, count + 1);
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
            topicRefCount.remove(topic);
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
    private byte[] buildPayloadForWrite(Object value, MqttPointOptions options) {
        String payloadText;
        if (options.getPublishTemplate() != null && !options.getPublishTemplate().isBlank()) {
            payloadText = options.getPublishTemplate().replace("${value}", Objects.toString(value, ""));
        } else {
            payloadText = Objects.toString(value, "");
        }
        return payloadText.getBytes(options.getCharset());
    }

    /**
     * 处理当前业务流程。
     */
    private Object executePublishCommand(Map<String, Object> params) throws Exception {
        String topic = Objects.toString(params.get("topic"), "");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        Object payloadObj = params.getOrDefault("payload", "");
        int qos = asInt(params.get("qos"), getDefaultQos());
        boolean retained = asBoolean(params.get("retained"), false);
        byte[] payload = Objects.toString(payloadObj, "").getBytes(StandardCharsets.UTF_8);
        publish(topic, payload, qos, retained);
        return Map.of("topic", topic, "status", "success");
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeSubscribeCommand(Map<String, Object> params) throws Exception {
        String topic = Objects.toString(params.get("topic"), "");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        int qos = asInt(params.get("qos"), getDefaultQos());
        ensureTopicSubscription(topic, qos);
        baseSubscribedTopics.add(topic);
        return Map.of("topic", topic, "status", "subscribed");
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeUnsubscribeCommand(Map<String, Object> params) throws Exception {
        String topic = Objects.toString(params.get("topic"), "");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        baseSubscribedTopics.remove(topic);
        topicBindings.remove(topic);
        if (topicRefCount.containsKey(topic)) {
            topicRefCount.put(topic, 1);
            decrementTopicSubscription(topic);
        }
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
        Set<String> bindings = topicBindings.get(topic);
        if (bindings == null || bindings.isEmpty()) {
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
        if ("base64".equalsIgnoreCase(options.getPayloadEncoding())) {
            actualPayload = Base64.getDecoder().decode(payload);
        }
        String text = new String(actualPayload, options.getCharset());
        Object raw = text;
        if (options.getJsonPath() != null && !options.getJsonPath().isBlank()) {
            Object json = JSON.parse(text);
            raw = JSONPath.eval(json, options.getJsonPath());
        }
        return convertToDataType(point.getDataType(), raw);
    }

    /**
     * 解析或转换业务数据。
     */
    private Object convertToDataType(String dataType, Object raw) {
        if (raw == null) {
            return null;
        }
        if (dataType == null || dataType.isBlank()) {
            return raw;
        }
        String type = dataType.trim().toUpperCase(Locale.ROOT);
        try {
            return switch (type) {
                case "INT", "INTEGER" -> toNumber(raw).intValue();
                case "LONG" -> toNumber(raw).longValue();
                case "FLOAT" -> toNumber(raw).floatValue();
                case "DOUBLE" -> toNumber(raw).doubleValue();
                case "BOOLEAN" -> toBoolean(raw);
                case "SHORT" -> toNumber(raw).shortValue();
                case "BYTE" -> toNumber(raw).byteValue();
                default -> raw.toString();
            };
        } catch (Exception ex) {
            log.warn("MQTT 数据类型转换失败: type={}, 值={}", type, raw, ex);
            return null;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Number toNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return Double.parseDouble(value.toString());
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        return "true".equals(text) || "1".equals(text) || "on".equals(text) || "yes".equals(text);
    }
}
