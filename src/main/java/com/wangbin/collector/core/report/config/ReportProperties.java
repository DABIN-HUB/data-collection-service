package com.wangbin.collector.core.report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * collector.report 配置映射
 */
@Data
@Component
@ConfigurationProperties(prefix = "collector.report")
public class ReportProperties {

    /**
     * 是否开启上报
     */
    private boolean enabled = true;

    /**
     * 上报模式（http/mqtt/websocket等）
     */
    private String mode = "MQTT";

    /**
     * 单次批量上报数量
     */
    private int batchSize = 100;

    /**
     * 调度间隔（毫秒）
     */
    private long intervalMs = 10000;

    /**
     * 上报超时（毫秒）
     */
    private int timeout = 3000;

    /**
     * 最大重试次数
     */
    private int retryTimes = 3;

    /**
     * flush chunk 首次退避毫秒
     */
    private long retryBackoffMs = 1000;

    /**
     * flush chunk 最大退避毫秒
     */
    private long maxRetryBackoffMs = 10000;

    /**
     * 是否启用轻微抖动，避免重试同时打满
     */
    private boolean retryJitterEnabled = true;

    /**
     * 单设备允许同时 pending 的 chunk 数上限
     */
    private int maxPendingChunksPerDevice = 32;

    /**
     * 缓存队列最大长度
     */
    private int maxQueueSize = 5000;

    /**
     * 刷新间隔（毫秒）
     */
    private long flushInterval = 1000;

    /**
     * 最小上报间隔（用于变化触发）
     */
    private long minReportIntervalMs = 2000;

    /**
     * 事件/告警触发默认最小间隔
     */
    private long eventMinIntervalMs = 5000;

    /**
     * 网关每秒最大发包数量（0 表示不限）
     */
    private int maxGatewayMessagesPerSecond = 200;

    /**
     * 启用的上报协议列表，留空表示全部协议可用
     */
    private List<String> enabledProtocols = new ArrayList<>();

    /**
     * 单个快照消息包含的最大属性数量
     */
    private int maxPropertiesPerMessage = 200;

    /**
     * 单个快照消息允许的最大载荷字节数（粗略估算，0 表示不限制）
     */
    private int maxPayloadBytes = 128 * 1024;

    /**
     * 上报数据结构版本
     */
    private int schemaVersion = 2;

    /**
     * MQTT 相关配置
     */
    private final Mqtt mqtt = new Mqtt();

    /**
     * 设备影子相关配置
     */
    private final Shadow shadow = new Shadow();
    /**
     * 云平台上报链路优化配置。
     */
    private final Cloud cloud = new Cloud();

    public boolean mqttEnabled() {
        return mqtt.isEnabled() && isProtocolEnabled("MQTT");
    }

    public boolean isProtocolEnabled(String protocol) {
        if (!enabled || protocol == null || protocol.isEmpty()) {
            return false;
        }
        if (mode != null && !mode.isBlank() && !"AUTO".equalsIgnoreCase(mode)) {
            if (!protocol.equalsIgnoreCase(mode)) {
                return false;
            }
        }
        if (enabledProtocols.isEmpty()) {
            return true;
        }
        return enabledProtocols.stream().anyMatch(item -> item.equalsIgnoreCase(protocol));
    }

    @Data
    public static class Mqtt {
        private boolean enabled = true;
        private String brokerUrl = "tcp://localhost:1883";
        private String clientId = "data-collector";
        /**
         * 对应云平台的产品 key，用于拼装 topic。
         */
        private String gatewayProductKey = "";

        private String gatewayDeviceName = "";
        private String cloudProvider = "alink";
        /**
         * topic 前缀，默认 /sys。
         */
        private String topicPrefix = "/sys";
        private String ackTopicPrefix = "/sys";
        private String ackTopicSuffix = "_reply";
        /**
         * ACK 等待超时时间（秒）
         */
        private int ackTimeoutSeconds = 5;
        private String username;
        private String password;
        private int qos = 1;
        private boolean cleanSession = true;
        private int connectionTimeout = 30;
        private int keepAliveInterval = 60;
        private boolean retained = false;
        private boolean downlinkEnabled = true;
        private List<String> subscribeTopics = new ArrayList<>();
        /**
         * 业务自定义主题
         */
        private Map<String, String> topics = new HashMap<>();
        /**
         * 平台服务名到协议命令名的映射。
         */
        private Map<String, String> serviceCommandMappings = new HashMap<>();

        public String getTopicPrefix() {
            if (topicPrefix == null || topicPrefix.isEmpty()) {
                return "/sys";
            }
            return topicPrefix.endsWith("/") ? topicPrefix.substring(0, topicPrefix.length() - 1) : topicPrefix;
        }

        public String getDefaultTopicTemplate() {
            return getTopicPrefix() + "/{productKey}/{deviceName}/{method}";
        }

        public int getAckTimeoutMs() {
            int seconds = ackTimeoutSeconds <= 0 ? 5 : ackTimeoutSeconds;
            return seconds * 1000;
        }

        public String getAckTopicPrefix() {
            String value = ackTopicPrefix == null ? "/sys" : ackTopicPrefix.trim();
            if (value.isEmpty()) {
                value = "/sys";
            }
            if (!value.startsWith("/")) {
                value = "/" + value;
            }
            if (value.endsWith("/") && value.length() > 1) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        }

        public String getAckTopicSuffix() {
            String value = ackTopicSuffix == null ? "_reply" : ackTopicSuffix.trim();
            if (value.isEmpty()) {
                value = "_reply";
            }
            return value;
        }
    }

    @Data
    public static class Shadow {
        /**
         * 是否启用 Redis 持久化。
         */
        private boolean persistenceEnabled = true;

        /**
         * desired 写入和清理是否启用 Redis 原子 CAS。
         */
        private boolean casEnabled = true;

        /**
         * Redis key 前缀。
         */
        private String keyPrefix = "collector:shadow:";

        /**
         * 影子 Redis 过期时间，单位秒；小于等于 0 表示不过期。
         */
        private long ttlSeconds = 24 * 60 * 60;

        /**
         * CAS 冲突后是否基于 Redis 最新影子自动合并并重试。
         */
        private boolean autoMergeEnabled = true;

        /**
         * CAS 冲突后的最大自动重试次数。
         */
        private int mergeRetryTimes = 2;

        /**
         * 是否记录 desired/clear 操作的影子历史审计。
         */
        private boolean historyEnabled = true;

        /**
         * 历史审计 Redis list key 前缀。
         */
        private String historyKeyPrefix = "collector:shadow:history:";

        /**
         * 每个设备最多保留的历史审计条数；小于等于 0 表示不裁剪。
         */
        private int historyMaxRecords = 100;

        /**
         * 历史审计 Redis 过期时间，单位秒；小于等于 0 表示不过期。
         */
        private long historyTtlSeconds = 7 * 24 * 60 * 60;
    }
    @Data
    public static class Cloud {

        /**
         * payload 精简策略。
         */
        private Payload payload = new Payload();

        /**
         * 平台业务 ACK 处理策略。
         */
        private Ack ack = new Ack();

        /**
         * 网关级批量属性包策略。
         */
        private Batch batch = new Batch();

        @Data
        public static class Payload {
            private String profile = "compact";
            private String includeQuality = "on_error";
            private boolean includePropertyTs = false;
            private boolean includeMetadata = false;
            private boolean includeMessageId = true;
        }

        @Data
        public static class Ack {
            private String mode = "async";
            private long timeoutMs = 5000L;
            private int maxPending = 10000;
            private long timeoutScanMs = 500L;
            private String commitOn = "publish-success";
        }

        @Data
        public static class Batch {
            private boolean enabled = true;
            private int maxDevicesPerPack = 50;
            private int maxPropertiesPerPack = 500;
            private int maxPayloadBytes = 128 * 1024;
            private long maxDelayMs = 1000L;
            private boolean highPriorityBypass = true;
        }
    }
}
