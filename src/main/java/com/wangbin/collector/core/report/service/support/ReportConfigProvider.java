
package com.wangbin.collector.core.report.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wangbin.collector.common.constant.ProtocolConstant;
import com.wangbin.collector.core.cloud.config.CloudAckOptions;
import com.wangbin.collector.core.cloud.config.CloudBatchFlushPolicy;
import com.wangbin.collector.core.cloud.config.CloudPayloadOptions;
import com.wangbin.collector.core.cloud.protocol.CloudProtocolAdapter;
import com.wangbin.collector.core.cloud.service.CloudReportTargetContext;
import com.wangbin.collector.core.cloud.protocol.CloudProtocolAdapterRegistry;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkCloudProtocolAdapter;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.model.ReportConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 为指定网关设备提供缓存后的 MQTT 上报配置。
 */
@Component
public class ReportConfigProvider {

    private final ReportProperties reportProperties;
    private final CloudProtocolAdapter fallbackCloudProtocolAdapter = AlinkCloudProtocolAdapter.standalone(new ObjectMapper());
    @Autowired(required = false)
    private CloudProtocolAdapterRegistry cloudProtocolAdapters;
    private final Cache<String, ReportConfig> cache;

    public ReportConfigProvider(ConfigManager configManager, ReportProperties reportProperties) {
        this.reportProperties = reportProperties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    public ReportConfig getConfig(String gatewayDeviceId) {
        if (gatewayDeviceId == null || !reportProperties.mqttEnabled()) {
            return null;
        }
        return cache.get(gatewayDeviceId, this::buildReportConfig);
    }

    public void evict(String gatewayDeviceId) {
        if (gatewayDeviceId != null) {
            cache.invalidate(gatewayDeviceId);
        }
    }

    private ReportConfig buildReportConfig(String gatewayDeviceId) {
        ReportProperties.Mqtt mqtt = reportProperties.getMqtt();
        BrokerEndpoint endpoint = parseBrokerEndpoint(mqtt.getBrokerUrl());
        if (endpoint == null) {
            return null;
        }

        CloudProtocolAdapter cloudProtocolAdapter = resolveCloudProtocolAdapter(mqtt.getCloudProvider());
        CloudReportTargetContext cloudTargetContext = new CloudReportTargetContext(
                gatewayDeviceId,
                cloudProtocolAdapter.provider(),
                cloudProtocolAdapter,
                CloudPayloadOptions.from(reportProperties.getCloud().getPayload()),
                CloudBatchFlushPolicy.from(reportProperties.getCloud().getBatch()),
                CloudAckOptions.from(reportProperties.getCloud().getAck())
        );
        ReportConfig config = new ReportConfig();
        config.setProtocol(ProtocolConstant.PROTOCOL_MQTT);
        config.setHost(endpoint.host());
        config.setPort(endpoint.port());
        config.setTargetId(gatewayDeviceId);
        config.setMaxRetryCount(reportProperties.getRetryTimes());
        config.setRetryInterval((int) reportProperties.getIntervalMs());
        config.setConnectTimeout(reportProperties.getTimeout());
        config.setReadTimeout(reportProperties.getTimeout());

        Map<String, Object> params = new HashMap<>();
        params.put(ProtocolConstant.MQTT_PARAM_CLIENT_ID, resolveClientId(gatewayDeviceId, mqtt.getClientId()));
        params.put(ProtocolConstant.MQTT_PARAM_USERNAME, mqtt.getUsername());
        params.put(ProtocolConstant.MQTT_PARAM_PASSWORD, mqtt.getPassword());
        params.put(ProtocolConstant.MQTT_PARAM_KEEP_ALIVE, mqtt.getKeepAliveInterval());
        params.put(ProtocolConstant.MQTT_PARAM_CLEAN_SESSION, mqtt.isCleanSession());
        params.put("cloudProvider", cloudTargetContext.cloudProvider());
        params.put("cloudTargetContext", cloudTargetContext);
        params.put(ProtocolConstant.MQTT_PARAM_PUBLISH_TOPIC, mqtt.getDefaultTopicTemplate());
        params.put("qos", mqtt.getQos());
        params.put("retained", mqtt.isRetained());
        params.put(ProtocolConstant.MQTT_PARAM_ACK_TOPIC_PREFIX, mqtt.getAckTopicPrefix());
        params.put(ProtocolConstant.MQTT_PARAM_ACK_TOPIC_SUFFIX, mqtt.getAckTopicSuffix());
        params.put(ProtocolConstant.MQTT_PARAM_ACK_TIMEOUT, mqtt.getAckTimeoutMs());
        List<String> subscribeTopics = buildSubscribeTopics(mqtt);
        if (!subscribeTopics.isEmpty()) {
            params.put(ProtocolConstant.MQTT_PARAM_SUBSCRIBE_TOPICS, subscribeTopics);
        }

        String gatewayProductKey = mqtt.getGatewayProductKey();
        if (gatewayProductKey != null && !gatewayProductKey.isEmpty()) {
            params.put("gatewayProductKey", gatewayProductKey);
            params.put("defaultProductKey", gatewayProductKey);
        }

        String gatewayDeviceName = mqtt.getGatewayDeviceName();
        if (gatewayDeviceName != null && !gatewayDeviceName.isEmpty()) {
            params.put("gatewayDeviceName", gatewayDeviceName);
        }

        config.setParams(params);

        return config;
    }

    private List<String> buildSubscribeTopics(ReportProperties.Mqtt mqtt) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        if (mqtt.getSubscribeTopics() != null) {
            mqtt.getSubscribeTopics().stream()
                    .filter(topic -> topic != null && !topic.isBlank())
                    .map(String::trim)
                    .forEach(topics::add);
        }
        if (!mqtt.isDownlinkEnabled()) {
            return new ArrayList<>(topics);
        }

        CloudProtocolAdapter cloudProtocolAdapter = resolveCloudProtocolAdapter(mqtt.getCloudProvider());
        List<String> topicPaths = new ArrayList<>();
        topicPaths.addAll(cloudProtocolAdapter.downlinkTopicPaths());
        // 业务回执不是下行命令，但必须订阅，例如子设备动态注册结果。
        topicPaths.addAll(cloudProtocolAdapter.businessReplyTopicPaths());

        for (String prefix : List.of(mqtt.getAckTopicPrefix(), mqtt.getTopicPrefix())) {
            String normalizedPrefix = normalizeTopicPrefix(prefix);
            for (String topicPath : topicPaths) {
                topics.add(normalizedPrefix + "/+/+/" + topicPath);
            }
        }
        return new ArrayList<>(topics);
    }

    private CloudProtocolAdapter resolveCloudProtocolAdapter(String provider) {
        if (cloudProtocolAdapters != null) {
            return cloudProtocolAdapters.resolve(provider);
        }
        if (provider == null || provider.isBlank()
                || fallbackCloudProtocolAdapter.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(provider))) {
            return fallbackCloudProtocolAdapter;
        }
        throw new IllegalArgumentException("unsupported cloud protocol provider: " + provider);
    }
    private String normalizeTopicPrefix(String prefix) {
        String value = prefix == null || prefix.isBlank() ? "/sys" : prefix.trim();
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String resolveClientId(String deviceId, String template) {
        if (template == null || template.isEmpty()) {
            return "collector-" + deviceId;
        }
        return template
                .replace("{deviceId}", deviceId)
                .replace("${deviceId}", deviceId);
    }

    private BrokerEndpoint parseBrokerEndpoint(String brokerUrl) {
        if (brokerUrl == null || brokerUrl.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(brokerUrl);
            String host = Optional.ofNullable(uri.getHost()).orElse(uri.getPath());
            int port = uri.getPort();
            if (port <= 0) {
                port = Objects.equals("ssl", uri.getScheme()) || Objects.equals("tls", uri.getScheme())
                        ? 8883 : ProtocolConstant.DEFAULT_MQTT_PORT;
            }
            return new BrokerEndpoint(host, port);
        } catch (Exception e) {
            return null;
        }
    }

    private record BrokerEndpoint(String host, int port) {
    }
}
