package com.wangbin.collector.core.report.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.config.ThreadPoolFallbacks;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.constant.ProtocolConstant;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.cloud.config.CloudAckCommitMode;
import com.wangbin.collector.core.cloud.config.CloudAckMode;
import com.wangbin.collector.core.cloud.config.CloudAckOptions;
import com.wangbin.collector.core.cloud.config.CloudBatchFlushPolicy;
import com.wangbin.collector.core.cloud.config.CloudPayloadOptions;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.CloudProtocolAdapter;
import com.wangbin.collector.core.cloud.protocol.CloudProtocolAdapterRegistry;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkCloudProtocolAdapter;
import com.wangbin.collector.core.cloud.service.CloudReportTargetContext;
import com.wangbin.collector.core.report.downlink.MqttDownlinkService;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.inbound.MqttAckReplyHandler;
import com.wangbin.collector.core.report.inbound.MqttAckReplyObserver;
import com.wangbin.collector.core.report.inbound.MqttBusinessReplyService;
import com.wangbin.collector.core.report.inbound.MqttDownlinkCommandHandler;
import com.wangbin.collector.core.report.inbound.MqttInboundMessage;
import com.wangbin.collector.core.report.inbound.MqttInboundMessageDispatcher;
import com.wangbin.collector.core.report.lifecycle.MqttCloudDeviceLifecyclePublisher;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import org.eclipse.paho.mqttv5.common.packet.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * MQTT报告处理器 - v5版本
 */
@Slf4j
@Component
public class MqttReportHandler extends AbstractReportHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ScheduledExecutorService DEFAULT_MONITOR_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "mqtt-report-monitor-shared");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService DEFAULT_PUBLISH_EXECUTOR =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
                Thread thread = new Thread(r, "mqtt-report-io-shared");
                thread.setDaemon(true);
                return thread;
            });

    private MqttClientManager clientManager;
    private MessagePublisher messagePublisher;
    private SubscriptionManager subscriptionManager;
    private final Map<String, MqttConnectionConfig> connectionConfigs = new ConcurrentHashMap<>();
    private final AckManager ackManager = new AckManager();
    private final CloudProtocolAdapter fallbackCloudProtocolAdapter = AlinkCloudProtocolAdapter.standalone(OBJECT_MAPPER);
    @Autowired(required = false)
    @Qualifier("monitorExecutor")
    private ScheduledExecutorService monitorExecutor;
    @Autowired(required = false)
    @Qualifier("ioIntensiveExecutor")
    private ExecutorService ioExecutor;
    @Autowired(required = false)
    private MqttDownlinkService downlinkService;
    @Autowired(required = false)
    private MqttBusinessReplyService businessReplyService;
    @Autowired(required = false)
    private CloudProtocolAdapterRegistry cloudProtocolAdapters;
    @Autowired(required = false)
    private ReportProperties reportProperties;
    @Autowired(required = false)
    private MqttCloudDeviceLifecyclePublisher lifecyclePublisher;
    @Autowired(required = false)
    private MqttAckReplyObserver ackReplyObserver;

    /**
     * 创建当前组件实例。
     */
    public MqttReportHandler() {
        super("MqttReportHandler", "MQTT", "MQTT v5协议上报处理器");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doInit() throws Exception {
        log.info("初始化 MQTT v5 报告处理器...");

        ScheduledExecutorService effectiveMonitorExecutor = ThreadPoolFallbacks.preferScheduler(
                monitorExecutor,
                DEFAULT_MONITOR_EXECUTOR,
                "MqttReportHandler",
                "mqtt-report-monitor-shared");
        ExecutorService effectivePublishExecutor = ThreadPoolFallbacks.preferExecutorService(
                ioExecutor,
                DEFAULT_PUBLISH_EXECUTOR,
                "MqttReportHandler",
                "mqtt-report-io-shared");

        clientManager = new MqttClientManager(
                ackManager,
                downlinkService,
                businessReplyService,
                lifecyclePublisher,
                ackReplyObserver,
                effectiveMonitorExecutor,
                resolveMaxConcurrentConnects(),
                resolveReconnectScanIntervalMs()
        );
        clientManager.init();

        messagePublisher = new MessagePublisher(
                clientManager,
                effectivePublishExecutor
        );
        messagePublisher.init();

        subscriptionManager = new SubscriptionManager(clientManager);
        subscriptionManager.init();

        log.info("MQTT v5 上报处理器初始化完成");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected ReportResult doReport(ReportData data, ReportConfig config) throws Exception {
        log.debug("开始 MQTT v5 上报：{} -> {}:{}", data.getPointCode(), config.getHost(), config.getPort());
        long startTime = System.currentTimeMillis();

        AckManager.AckRegistration ackRegistration = null;
        try {
            // 1. 获取连接配置
            MqttConnectionConfig connConfig = getConnectionConfig(config);

            // 2. 获取 MQTT 客户端
            MqttAsyncClient mqttClient = obtainConnectedClient(connConfig);
            if (mqttClient == null) {
                return buildOfflineResult(data, config,
                        "MQTT client not connected, reconnect scheduled");
            }
            updateSubscriptions(connConfig);

            // 3. 构建发布选项
            MqttPublishOptions publishOptions = buildPublishOptions(data, config);

            // 4. 构建消息内容
            byte[] messagePayload = buildMessagePayload(data, config);

            // 4.1 注册 ACK 监听
            ackRegistration = prepareAckRegistration(connConfig, data);

            // 5. 发布消息 - v5 API
            PublishResult publishResult = messagePublisher.publish(
                    mqttClient,
                    publishOptions.getTopic(),
                    messagePayload,
                    publishOptions
            );

            // 6. 创建上报结果
            ReportResult result = ReportResult.success(data.getPointCode(), config.getTargetId());
            result.setCostTime(System.currentTimeMillis() - startTime);
            result.addMetadata("mqttMessageId", publishResult.getMessageId());
            result.addMetadata("mqttQos", publishOptions.getQos());

            if (publishResult.isSuccess()) {
                log.debug("MQTT v5上报成功：{} -> {}:{}, QoS: {}, 耗时：{}ms",
                        data.getPointCode(), config.getHost(), config.getPort(),
                        publishOptions.getQos(), result.getCostTime());
            } else {
                result.setSuccess(false);
                result.setErrorMessage("MQTT v5发布失败: " + publishResult.getErrorMessage());
                log.warn("MQTT v5上报失败：{} -> {}:{}, 错误：{}",
                        data.getPointCode(), config.getHost(), config.getPort(),
                        publishResult.getErrorMessage());
                ackManager.cancel(ackRegistration);
            }

            if (result.isSuccess()) {
                applyAckResult(connConfig, ackRegistration, result);
            }

            return result;

        } catch (Exception e) {
            ackManager.cancel(ackRegistration);
            log.error("MQTT v5上报异常：{} -> {}:{}",
                    data.getPointCode(), config.getHost(), config.getPort(), e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected List<ReportResult> doBatchReport(List<ReportData> dataList, ReportConfig config) throws Exception {
        log.debug("开始批量 MQTT v5 上报：{}:{}，数据量：{}", config.getHost(), config.getPort(), dataList.size());

        List<ReportResult> results = new ArrayList<>(dataList.size());
        MqttConnectionConfig connConfig = getConnectionConfig(config);
        List<PublishTask> publishTasks = new ArrayList<>(dataList.size());
        List<AckManager.AckRegistration> ackRegistrations = new ArrayList<>(dataList.size());

        try {
            // 1. 获取 MQTT 客户端
            MqttAsyncClient mqttClient = obtainConnectedClient(connConfig);
            if (mqttClient == null) {
                String offlineMessage = "MQTT client not connected, reconnect scheduled";
                for (ReportData data : dataList) {
                    results.add(buildOfflineResult(data, config, offlineMessage));
                }
                return results;
            }
            updateSubscriptions(connConfig);

            // 2. 批量发布消息
            for (ReportData data : dataList) {
                // 构建发布选项
                MqttPublishOptions publishOptions = buildPublishOptions(data, config);

                // 构建消息内容
                byte[] messagePayload = buildMessagePayload(data, config);

                // 注册 ACK 监听
                ackRegistrations.add(prepareAckRegistration(connConfig, data));

                // 创建发布任务
                PublishTask task = new PublishTask(
                        mqttClient,
                        publishOptions.getTopic(),
                        messagePayload,
                        publishOptions,
                        data.getPointCode(),
                        config.getTargetId()
                );
                publishTasks.add(task);
            }

            // 3. 执行批量发布
            List<PublishResult> publishResults = messagePublisher.publishBatch(publishTasks);

            // 4. 转换发布结果为上报结果
            for (int i = 0; i < publishResults.size(); i++) {
                PublishResult publishResult = publishResults.get(i);
                ReportData data = dataList.get(i);

                ReportResult result = ReportResult.success(data.getPointCode(), config.getTargetId());
                result.addMetadata("mqttMessageId", publishResult.getMessageId());

                if (!publishResult.isSuccess()) {
                    result.setSuccess(false);
                    result.setErrorMessage("MQTT v5发布失败: " + publishResult.getErrorMessage());
                    ackManager.cancel(ackRegistrations.get(i));
                } else {
                    applyAckResult(connConfig, ackRegistrations.get(i), result);
                }

                results.add(result);
            }

            // 统计成功数量
            long successCount = results.stream().filter(ReportResult::isSuccess).count();
            log.debug("批量 MQTT v5 上报完成：{}:{}, 成功：{}，失败：{}，总计：{}",
                    config.getHost(), config.getPort(),
                    successCount, results.size() - successCount, results.size());

        } catch (Exception e) {
            ackRegistrations.forEach(ackManager::cancel);
            log.error("批量 MQTT v5 上报异常：{}:{}", config.getHost(), config.getPort(), e);

            // 为所有数据创建错误结果
            for (ReportData data : dataList) {
                ReportResult errorResult = ReportResult.error(
                        data.getPointCode(),
                        "批量 MQTT v5 上报异常: " + e.getMessage(),
                        config.getTargetId()
                );
                results.add(errorResult);
            }
        }

        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConfigUpdate(ReportConfig config) throws Exception {
        log.debug("更新 MQTT v5 处理器配置：{}", config.getTargetId());

        try {
            // 1. 获取或创建连接配置
            MqttConnectionConfig connConfig = getConnectionConfig(config);

            // 2. 更新客户端管理器配置
            clientManager.updateConnectionConfig(connConfig);

            // 3. 更新订阅配置
            updateSubscriptions(connConfig);

            log.info("MQTT v5处理器配置更新完成：{}", config.getTargetId());
        } catch (Exception e) {
            log.error("MQTT v5处理器配置更新失败：{}", config.getTargetId(), e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConfigRemove(ReportConfig config) throws Exception {
        log.debug("删除 MQTT v5 处理器配置：{}", config.getTargetId());

        try {
            // 1. 移除连接配置
            String configKey = getConnectionConfigKey(config);
            connectionConfigs.remove(configKey);

            // 2. 移除客户端连接
            clientManager.removeClient(configKey);

            // 3. 移除订阅配置
            subscriptionManager.removeSubscriptions(configKey);

            log.info("MQTT v5处理器配置删除完成：{}", config.getTargetId());
        } catch (Exception e) {
            log.error("MQTT v5处理器配置删除失败：{}", config.getTargetId(), e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDestroy() throws Exception {
        log.info("销毁 MQTT v5 报告处理器...");

        try {
            // 1. 销毁订阅管理器
            if (subscriptionManager != null) {
                subscriptionManager.destroy();
                subscriptionManager = null;
            }

            // 2. 销毁消息发布管理器
            if (messagePublisher != null) {
                messagePublisher.destroy();
                messagePublisher = null;
            }

            // 3. 销毁客户端管理器
            if (clientManager != null) {
                clientManager.destroy();
                clientManager = null;
            }

            // 4. 清空配置
            connectionConfigs.clear();

            log.info("MQTT v5上报处理器销毁完成");
        } catch (Exception e) {
            log.error("MQTT v5上报处理器销毁失败", e);
            throw e;
        }
    }

    @Override
    protected Map<String, Object> getImplementationStatus() {
        Map<String, Object> status = new HashMap<>();

        status.put("clientManager", clientManager != null ?
                clientManager.getStatus() : "未初始化");
        status.put("messagePublisher", messagePublisher != null ?
                "已初始化" : "未初始化");
        status.put("subscriptionManager", subscriptionManager != null ?
                "已初始化" : "未初始化");
        status.put("connectionConfigsCount", connectionConfigs.size());

        return status;
    }

    @Override
    protected Map<String, Object> getImplementationStatistics() {
        Map<String, Object> stats = new HashMap<>();

        if (clientManager != null) {
            stats.put("clientManager", clientManager.getStatistics());
        }

        if (messagePublisher != null) {
            stats.put("messagePublisher", messagePublisher.getStatistics());
        }

        if (subscriptionManager != null) {
            stats.put("subscriptionManager", subscriptionManager.getStatistics());
        }

        return stats;
    }

    // =============== 私有辅助方法 ===============

    /**
     * 查询并返回业务数据。
     */
    private void loadMqttConfig() {
        log.debug("加载 MQTT v5 配置完成");
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveMaxConcurrentConnects() {
        if (reportProperties == null || reportProperties.getMqtt() == null) {
            return 1;
        }
        return Math.max(1, reportProperties.getMqtt().getMaxConcurrentConnects());
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveReconnectScanIntervalMs() {
        if (reportProperties == null || reportProperties.getMqtt() == null) {
            return 30000L;
        }
        return Math.max(1000L, reportProperties.getMqtt().getReconnectScanIntervalMs());
    }

    private MqttConnectionConfig getConnectionConfig(ReportConfig config) {
        String configKey = getConnectionConfigKey(config);

        return connectionConfigs.compute(configKey, (key, existing) -> {
            MqttConnectionConfig connConfig = new MqttConnectionConfig(config);
            CloudReportTargetContext cloudTargetContext = resolveCloudReportTargetContext(config);
            connConfig.setCloudTargetContext(cloudTargetContext);
            connConfig.setCloudProvider(cloudTargetContext.cloudProvider());
            connConfig.setAckMethods(cloudTargetContext.protocolAdapter().ackMethods());
            connConfig.setAckOptions(cloudTargetContext.ackOptions());

            // 基础连接配置
            connConfig.setClientId(config.getMqttClientId());
            connConfig.setKeepAliveInterval(config.getIntParam(
                    ProtocolConstant.MQTT_PARAM_KEEP_ALIVE, 60));
            connConfig.setConnectionTimeout((int) config.getEffectiveConnectTimeout());
            connConfig.setCleanStart(config.getBooleanParam(
                    ProtocolConstant.MQTT_PARAM_CLEAN_SESSION, true)); // MQTT v5 使用 cleanStart 表达清理会话。
            connConfig.setAutomaticReconnect(false);
            connConfig.setMaxReconnectDelay(60000);

            // 认证配置
            connConfig.setUsername(config.getStringParam(ProtocolConstant.MQTT_PARAM_USERNAME));

            String password = config.getStringParam(ProtocolConstant.MQTT_PARAM_PASSWORD);
            if (password != null) {
                connConfig.setPassword(password.toCharArray());
            }

            // SSL配置
            connConfig.setSslEnabled(config.getBooleanParam(
                    ProtocolConstant.MQTT_PARAM_SSL_ENABLED, false));

            // 遗嘱消息配置
            String willTopic = config.getStringParam(ProtocolConstant.MQTT_PARAM_WILL_TOPIC);
            String willMessage = config.getStringParam(ProtocolConstant.MQTT_PARAM_WILL_MESSAGE);
            if (willTopic != null && willMessage != null) {
                MqttWillMessage will = new MqttWillMessage();
                will.setTopic(willTopic);
                will.setMessage(willMessage.getBytes());
                will.setQos(config.getIntParam(ProtocolConstant.MQTT_PARAM_WILL_QOS, 1));
                will.setRetained(config.getBooleanParam(
                        ProtocolConstant.MQTT_PARAM_WILL_RETAINED, false));
                connConfig.setWillMessage(will);
            }

            // 发布主题配置
            String publishTopic = config.getStringParam(ProtocolConstant.MQTT_PARAM_PUBLISH_TOPIC);
            if (publishTopic != null) {
                connConfig.setDefaultPublishTopic(publishTopic);
            }

            // 订阅主题配置
            List<String> subscribeTopicList = new ArrayList<>();
            Object subscribeTopics = config.getParam(ProtocolConstant.MQTT_PARAM_SUBSCRIBE_TOPICS);
            if (subscribeTopics instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<String> topics = (java.util.List<String>) subscribeTopics;
                for (Object topic : topics) {
                    if (topic != null) {
                        subscribeTopicList.add(String.valueOf(topic));
                    }
                }
            }
            connConfig.setSubscribeTopics(subscribeTopicList);

            connConfig.setGatewayProductKey(config.getStringParam("gatewayProductKey"));
            connConfig.setGatewayDeviceName(config.getStringParam("gatewayDeviceName"));
            applyLifecycleConfig(connConfig);
            String ackPrefix = config.getStringParam(ProtocolConstant.MQTT_PARAM_ACK_TOPIC_PREFIX);
            String ackSuffix = config.getStringParam(ProtocolConstant.MQTT_PARAM_ACK_TOPIC_SUFFIX);
            connConfig.setAckTopicPrefix(ackPrefix);
            connConfig.setAckTopicSuffix(ackSuffix);
            connConfig.prepareAckSettings();
            for (String ackTopic : connConfig.getAckSubscriptionTopics()) {
                if (!subscribeTopicList.contains(ackTopic)) {
                    subscribeTopicList.add(ackTopic);
                }
            }

            return connConfig;
        });
    }

    private String getConnectionConfigKey(ReportConfig config) {
        return config.getTargetId() + "@" + config.getHost() + ":" + config.getPort();
    }

    /**
     * 处理当前业务流程。
     */
    private void applyLifecycleConfig(MqttConnectionConfig connConfig) {
        ReportProperties.Mqtt.Lifecycle lifecycle = reportProperties != null && reportProperties.getMqtt() != null
                ? reportProperties.getMqtt().getLifecycle()
                : new ReportProperties.Mqtt.Lifecycle();
        connConfig.setLifecycleEnabled(lifecycle.isEnabled());
        connConfig.setGatewayOnlineEnabled(lifecycle.isGatewayOnlineEnabled());
        connConfig.setGatewayGracefulOfflineEnabled(lifecycle.isGatewayGracefulOfflineEnabled());
        connConfig.setLifecycleQos(Math.max(0, Math.min(1, lifecycle.getQos())));
        connConfig.setLifecyclePublishTimeoutMs(Math.max(1000L, lifecycle.getPublishTimeoutMs()));
    }

    /**
     * 创建并返回业务对象。
     */
    private MqttPublishOptions buildPublishOptions(ReportData data, ReportConfig config) {
        MqttPublishOptions options = new MqttPublishOptions();

        options.setTopic(resolveCloudReportTargetContext(config).protocolAdapter().buildPublishTopic(data, config));
        options.setQos(getQosLevel(config));
        options.setRetained(isRetainedMessage(config));
        return options;
    }

    /**
     * 解析或转换业务数据。
     */
    private CloudReportTargetContext resolveCloudReportTargetContext(ReportConfig config) {
        if (config != null) {
            Object raw = config.getParam("cloudTargetContext");
            if (raw instanceof CloudReportTargetContext context) {
                return context;
            }
        }
        CloudProtocolAdapter adapter = resolveCloudProtocolAdapter(config);
        return new CloudReportTargetContext(
                config != null ? config.getTargetId() : null,
                adapter.provider(),
                adapter,
                CloudPayloadOptions.defaults(),
                CloudBatchFlushPolicy.defaults(),
                CloudAckOptions.defaults());
    }

    /**
     * 解析或转换业务数据。
     */
    private CloudProtocolAdapter resolveCloudProtocolAdapter(ReportConfig config) {
        String provider = configText(config, "cloudProvider");
        if (cloudProtocolAdapters != null) {
            return cloudProtocolAdapters.resolve(provider);
        }
        if (!hasText(provider) || fallbackCloudProtocolAdapter.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(provider))) {
            return fallbackCloudProtocolAdapter;
        }
        throw new IllegalArgumentException("unsupported cloud protocol provider: " + provider);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String configText(ReportConfig config, String key) {
        if (config == null || key == null) {
            return null;
        }
        String value = config.getStringParam(key);
        if (hasText(value)) {
            return value;
        }
        Object raw = config.getParams() != null ? config.getParams().get(key) : null;
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private int getQosLevel(ReportConfig config) {
        Map<String, Object> params = config.getParams();
        if (params != null) {
            Object qosObj = params.get("qos");
            if (qosObj instanceof Number) {
                int qos = ((Number) qosObj).intValue();
                if (qos >= 0 && qos <= 2) {
                    return qos;
                }
            }
        }
        return 1; // 默认 QoS 1
    }

    private boolean isRetainedMessage(ReportConfig config) {
        Map<String, Object> params = config.getParams();
        if (params != null) {
            Object retainedObj = params.get("retained");
            if (retainedObj instanceof Boolean) {
                return (Boolean) retainedObj;
            }
        }
        return false; // 默认不保留消息
    }

    /**
     * 创建并返回业务对象。
     */
    private byte[] buildMessagePayload(ReportData data, ReportConfig config) {
        return buildJsonPayload(data, config);
    }

    /**
     * 创建并返回业务对象。
     */
    private byte[] buildJsonPayload(ReportData data, ReportConfig config) {
        CloudReportTargetContext targetContext = resolveCloudReportTargetContext(config);
        return targetContext.protocolAdapter().encodeReportData(data, targetContext.payloadOptions());
    }

    /**
     * 创建并返回业务对象。
     */
    private byte[] buildTextPayload(ReportData data, ReportConfig config) {
        StringBuilder text = new StringBuilder();
        text.append("pointCode=").append(data.getPointCode()).append(";");
        text.append("value=").append(data.getValue()).append(";");
        text.append("timestamp=").append(data.getTimestamp()).append(";");

        if (data.getQuality() != null) {
            text.append("quality=").append(data.getQuality()).append(";");
        }

        return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 创建并返回业务对象。
     */
    private byte[] buildBinaryPayload(ReportData data, ReportConfig config) {
        // 简化的二进制编码格式
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

        try {
            // 写入点位编码
            if (data.getPointCode() != null) {
                byte[] pointCodeBytes = data.getPointCode().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeInt(pointCodeBytes.length);
                dos.write(pointCodeBytes);
            } else {
                dos.writeInt(0);
            }

            // 写入时间戳
            dos.writeLong(data.getTimestamp());

            // 写入数值
            if (data.getValue() instanceof Number) {
                dos.writeDouble(((Number) data.getValue()).doubleValue());
            } else {
                dos.writeDouble(0.0);
            }

            dos.flush();
            return baos.toByteArray();

        } catch (java.io.IOException e) {
            log.error("构建二进制消息失败", e);
            return new byte[0];
        }
    }

    /**
     * 更新或刷新业务状态。
     */
    private void updateSubscriptions(MqttConnectionConfig connConfig) {
        List<String> topics = connConfig.getSubscribeTopics();
        if (topics != null && !topics.isEmpty()) {
            subscriptionManager.updateSubscriptions(connConfig, topics);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private AckManager.AckRegistration prepareAckRegistration(MqttConnectionConfig connConfig, ReportData data) {
        if (connConfig == null || data == null) {
            return AckManager.AckRegistration.disabled();
        }
        CloudAckOptions ackOptions = connConfig.getAckOptions();
        if (ackOptions == null || !ackOptions.enabled() || !connConfig.shouldWaitForAck()) {
            return AckManager.AckRegistration.disabled();
        }
        return ackManager.register(extractMessageId(data), ackOptions);
    }
    /**
     * 解析或转换业务数据。
     */
    private String extractMessageId(ReportData data) {
        if (data == null || data.getMetadata() == null) {
            return null;
        }
        Object messageId = data.getMetadata().get(MessageConstant.FIELD_MESSAGE_ID);
        return messageId != null ? messageId.toString() : null;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveMessageId(ReportData data) {
        if (data == null) {
            return UUID.randomUUID().toString();
        }
        Map<String, Object> metadata = data.getMetadata();
        if (metadata != null) {
            Object existing = metadata.get(MessageConstant.FIELD_MESSAGE_ID);
            if (existing != null) {
                String existingId = existing.toString();
                if (!existingId.isBlank()) {
                    return existingId;
                }
            }
        }

        String batchId = data.getBatchId();
        if (batchId != null && !batchId.isBlank()) {
            String suffix = null;
            if (metadata != null) {
                Object chunkIndex = metadata.get("chunkIndex");
                if (chunkIndex != null) {
                    String chunkStr = chunkIndex.toString();
                    if (!chunkStr.isBlank()) {
                        suffix = chunkStr;
                    }
                }
            }
            if (suffix == null || suffix.isEmpty()) {
                if (data.getPointCode() != null && !data.getPointCode().isEmpty()) {
                    suffix = data.getPointCode();
                } else {
                    suffix = Long.toString(System.currentTimeMillis());
                }
            }
            return batchId + "-" + suffix;
        }

        return UUID.randomUUID().toString();
    }

    /**
     * 处理当前业务流程。
     */
    private void applyAckResult(MqttConnectionConfig connConfig,
                                AckManager.AckRegistration registration,
                                ReportResult result) {
        if (registration == null || result == null) {
            return;
        }
        if (!registration.isEnabled() || connConfig == null || !connConfig.shouldWaitForAck()) {
            ackManager.cancel(registration);
            return;
        }
        if (registration.getMode() == CloudAckMode.ASYNC) {
            result.addMetadata("ackMode", CloudAckMode.ASYNC.name());
            result.addMetadata("ackPending", true);
            result.addMetadata("ackTimeoutMs", registration.getTimeoutMs());
            result.addMetadata("ackCommitOn", registration.getCommitMode().name());
            return;
        }

        AckMessage ack = ackManager.await(registration, registration.getTimeoutMs());
        if (ack == null) {
            ackManager.cancel(registration);
            return;
        }

        result.addMetadata("ackCode", ack.code);
        result.addMetadata("ackMessage", ack.message);

        if (ack.timeout) {
            result.setSuccess(false);
            result.setErrorMessage("MQTT ack timeout");
        } else if (ack.code != 0) {
            result.setSuccess(false);
            result.setErrorMessage("MQTT ack error: " + ack.message);
        }
    }
    /**
     * 执行当前业务逻辑。
     */
    private MqttAsyncClient obtainConnectedClient(MqttConnectionConfig connConfig) {
        try {
            MqttAsyncClient client = clientManager.getClient(connConfig);
            if (client != null && client.isConnected()) {
                return client;
            }
        } catch (Exception e) {
            log.warn("获取 MQTT 客户端失败：{}", connConfig.getKey(), e);
        }

        if (!clientManager.tryReconnect(connConfig)) {
            return null;
        }

        try {
            MqttAsyncClient client = clientManager.getClient(connConfig);
            if (client != null && client.isConnected()) {
                return client;
            }
        } catch (Exception e) {
            log.warn("重连后获取 MQTT 客户端失败：{}", connConfig.getKey(), e);
        }
        return null;
    }

    /**
     * 创建并返回业务对象。
     */
    private ReportResult buildOfflineResult(ReportData data, ReportConfig config, String message) {
        ReportResult result = ReportResult.error(
                data != null ? data.getPointCode() : "unknown",
                message,
                config != null ? config.getTargetId() : "unknown");
        result.addMetadata("deferred", true);
        return result;
    }

    /**
     * 管理当前模块的生命周期和状态。
     */
    private static class AckManager {
        private final ConcurrentHashMap<String, AckTicket> pendingAcks = new ConcurrentHashMap<>();

        /**
         * 维护注册或订阅关系。
         */
        AckRegistration register(String messageId, CloudAckOptions options) {
            if (options == null || !options.enabled() || messageId == null || messageId.isEmpty()) {
                return AckRegistration.disabled();
            }
            if (pendingAcks.size() >= options.maxPending()) {
                log.warn("MQTT ACK pending 已达上限，跳过本次 ACK 等待：消息={}, maxPending={}",
                        messageId, options.maxPending());
                return AckRegistration.disabled();
            }
            AckTicket ticket = new AckTicket(
                    messageId,
                    new CompletableFuture<>(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis() + options.timeoutMs(),
                    options);
            AckTicket previous = pendingAcks.put(messageId, ticket);
            if (previous != null) {
                previous.future.cancel(true);
            }
            return new AckRegistration(messageId, ticket, true);
        }

        /**
         * 执行当前业务逻辑。
         */
        AckMessage await(AckRegistration registration, long timeoutMs) {
            if (registration == null || !registration.isEnabled()) {
                return null;
            }
            long waitMs = timeoutMs > 0 ? timeoutMs : ProtocolConstant.DEFAULT_MQTT_ACK_TIMEOUT_MS;
            try {
                return registration.ticket.future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pendingAcks.remove(registration.messageId, registration.ticket);
                return AckMessage.timeout(registration.messageId);
            } catch (Exception e) {
                pendingAcks.remove(registration.messageId, registration.ticket);
                return AckMessage.failure(registration.messageId, e.getMessage());
            }
        }

        /**
         * 执行当前业务逻辑。
         */
        void cancel(AckRegistration registration) {
            if (registration == null || !registration.isEnabled()) {
                return;
            }
            pendingAcks.remove(registration.messageId, registration.ticket);
        }

        /**
         * 执行当前业务逻辑。
         */
        void complete(String messageId, AckMessage ackMessage) {
            if (messageId == null) {
                return;
            }
            AckTicket ticket = pendingAcks.remove(messageId);
            if (ticket != null) {
                ticket.future.complete(ackMessage);
            } else {
                log.trace("收到未跟踪的 MQTT ACK：消息={}", messageId);
            }
        }

        /**
         * 执行当前业务逻辑。
         */
        void expireTimeouts() {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, AckTicket> entry : pendingAcks.entrySet()) {
                AckTicket ticket = entry.getValue();
                if (ticket == null || ticket.deadlineAt > now) {
                    continue;
                }
                if (pendingAcks.remove(entry.getKey(), ticket)) {
                    ticket.future.complete(AckMessage.timeout(ticket.messageId));
                    log.warn("MQTT ACK 等待超时：消息={}, 模式={}, timeoutMs={}",
                            ticket.messageId, ticket.options.mode(), ticket.options.timeoutMs());
                }
            }
        }

        /**
         * 定义当前模块的业务组件。
         */
        private static class AckTicket {
            private final String messageId;
            private final CompletableFuture<AckMessage> future;
            private final long createdAt;
            private final long deadlineAt;
            private final CloudAckOptions options;

            /**
             * 创建当前组件实例。
             */
            private AckTicket(String messageId,
                              CompletableFuture<AckMessage> future,
                              long createdAt,
                              long deadlineAt,
                              CloudAckOptions options) {
                this.messageId = messageId;
                this.future = future;
                this.createdAt = createdAt;
                this.deadlineAt = deadlineAt;
                this.options = options;
            }
        }

        /**
         * 定义当前模块的业务组件。
         */
        private static class AckRegistration {
            private final String messageId;
            private final AckTicket ticket;
            private final boolean enabled;

            /**
             * 创建当前组件实例。
             */
            private AckRegistration(String messageId, AckTicket ticket, boolean enabled) {
                this.messageId = messageId;
                this.ticket = ticket;
                this.enabled = enabled;
            }

            /**
             * 执行当前业务逻辑。
             */
            static AckRegistration disabled() {
                return new AckRegistration(null, null, false);
            }

            boolean isEnabled() {
                return enabled && messageId != null && ticket != null;
            }

            CloudAckMode getMode() {
                return isEnabled() ? ticket.options.mode() : CloudAckMode.DISABLED;
            }

            CloudAckCommitMode getCommitMode() {
                return isEnabled() ? ticket.options.commitMode() : CloudAckCommitMode.PUBLISH_SUCCESS;
            }

            long getTimeoutMs() {
                return isEnabled() ? ticket.options.timeoutMs() : 0L;
            }
        }
    }
    /**
     * 定义当前模块的业务组件。
     */
    private static class AckMessage {
        private final String messageId;
        private final int code;
        private final String message;
        private final boolean timeout;

        /**
         * 创建当前组件实例。
         */
        private AckMessage(String messageId, int code, String message, boolean timeout) {
            this.messageId = messageId;
            this.code = code;
            this.message = message;
            this.timeout = timeout;
        }

        /**
         * 执行当前业务逻辑。
         */
        private static AckMessage received(String messageId, int code, String message) {
            return new AckMessage(messageId, code, message, false);
        }

        /**
         * 执行当前业务逻辑。
         */
        private static AckMessage timeout(String messageId) {
            return new AckMessage(messageId, -1, "ACK timeout", true);
        }

        /**
         * 构造标准业务结果。
         */
        private static AckMessage failure(String messageId, String message) {
            return new AckMessage(messageId, -1, message != null ? message : "ACK wait failed", false);
        }
    }

    // =============== 内部数据模型 ===============

    /**
     * MQTT连接配置 - v5
     */
    @Data
    private static class MqttConnectionConfig {
        private final String targetId;
        private final String host;
        private final int port;
        private String clientId;
        private String username;
        private char[] password;
        private int keepAliveInterval = 60;
        private int connectionTimeout = 30;
        private boolean cleanStart = true; // MQTT v5 的 cleanStart 对应旧版 cleanSession。
        private boolean automaticReconnect = true;
        private int maxReconnectDelay = 60000;
        private boolean sslEnabled = false;
        private MqttWillMessage willMessage;
        private String defaultPublishTopic;
        private List<String> subscribeTopics = new ArrayList<>();
        @Setter
        private String ackTopicPrefix;
        @Setter
        private String ackTopicSuffix;
        @Getter
        private List<String> ackSubscriptionTopics = Collections.emptyList();
        private List<Pattern> ackTopicPatterns = Collections.emptyList();
        private String gatewayProductKey;
        private String gatewayDeviceName;
        private String cloudProvider = CloudProtocolAdapter.DEFAULT_PROVIDER;
        private CloudReportTargetContext cloudTargetContext;
        private CloudAckOptions ackOptions = CloudAckOptions.defaults();
        private List<String> ackMethods = MessageConstant.getAckMethods();
        private boolean lifecycleEnabled = true;
        private boolean gatewayOnlineEnabled = true;
        private boolean gatewayGracefulOfflineEnabled = true;
        private int lifecycleQos = 1;
        private long lifecyclePublishTimeoutMs = 3000L;

        /**
         * 创建当前组件实例。
         */
        public MqttConnectionConfig(ReportConfig config) {
            this.targetId = config.getTargetId();
            this.host = config.getHost();
            this.port = config.getPort();
        }

        public String getBrokerUrl() {
            String protocol = sslEnabled ? "ssl://" : "tcp://";
            return protocol + host + ":" + port;
        }

        public String getKey() {
            return targetId + "@" + host + ":" + port;
        }

        public CloudDeviceIdentity getGatewayIdentity() {
            return CloudDeviceIdentity.of(gatewayProductKey, gatewayDeviceName);
        }

        /**
         * 执行当前业务逻辑。
         */
        public void prepareAckSettings() {
            if (ackTopicSuffix == null || ackTopicSuffix.isEmpty()) {
                ackSubscriptionTopics = Collections.emptyList();
                ackTopicPatterns = Collections.emptyList();
                return;
            }

            String normalizedSuffix = ackTopicSuffix.trim();
            String prefix = normalizePrefix(ackTopicPrefix);
            List<String> topics = new ArrayList<>();
            List<Pattern> patterns = new ArrayList<>();

            List<String> methods = ackMethods == null || ackMethods.isEmpty() ? MessageConstant.getAckMethods() : ackMethods;
            for (String method : methods) {
                String methodPath = MessageConstant.methodToTopicPath(method);
                if (methodPath.isEmpty()) {
                    continue;
                }
                // ACK 订阅必须使用通配符匹配产品和设备名称，兼容网关子设备回复 topic。
                String topic = prefix + "/+/+/" + methodPath + normalizedSuffix;
                topics.add(topic);
                patterns.add(buildAckPattern(topic));
            }

            ackSubscriptionTopics = Collections.unmodifiableList(topics);
            ackTopicPatterns = Collections.unmodifiableList(patterns);
        }

        /**
         * 创建并返回业务对象。
         */
        private Pattern buildAckPattern(String topic) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < topic.length(); i++) {
                char ch = topic.charAt(i);
                if (ch == '+') {
                    regex.append("[^/]+");
                } else if ("\\.[]{}()*+-?^$|".indexOf(ch) >= 0) {
                    regex.append('\\').append(ch);
                } else {
                    regex.append(ch);
                }
            }
            return Pattern.compile(regex.toString());
        }

        /**
         * 执行当前业务逻辑。
         */
        public boolean shouldWaitForAck() {
            return ackOptions != null && ackOptions.enabled() && !ackTopicPatterns.isEmpty() && ackOptions.timeoutMs() > 0;
        }

        /**
         * 解析或转换业务数据。
         */
        private String normalizePrefix(String prefix) {
            String value = prefix == null || prefix.isBlank() ? "/sys" : prefix.trim();
            if (!value.startsWith("/")) {
                value = "/" + value;
            }
            if (value.endsWith("/") && value.length() > 1) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        }
    }

    /**
     * MQTT遗嘱消息配置
     */
    private static class MqttWillMessage {
        private String topic;
        private byte[] message;
        private int qos = 1;
        private boolean retained = false;

        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }

        public byte[] getMessage() { return message; }
        public void setMessage(byte[] message) { this.message = message; }

        public int getQos() { return qos; }
        public void setQos(int qos) { this.qos = qos; }

        public boolean isRetained() { return retained; }
        public void setRetained(boolean retained) { this.retained = retained; }
    }

    /**
     * MQTT发布选项
     */
    private static class MqttPublishOptions {
        private String topic;
        private int qos = 1;
        private boolean retained = false;

        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }

        public int getQos() { return qos; }
        public void setQos(int qos) { this.qos = qos; }

        public boolean isRetained() { return retained; }
        public void setRetained(boolean retained) { this.retained = retained; }
    }

    /**
     * 发布任务
     */
    @Data
    private static class PublishTask {
        private final MqttAsyncClient client;
        private final String topic;
        private final byte[] payload;
        private final MqttPublishOptions options;
        private final String pointCode;
        private final String targetId;

        /**
         * 创建当前组件实例。
         */
        public PublishTask(MqttAsyncClient client, String topic, byte[] payload,
                           MqttPublishOptions options, String pointCode, String targetId) {
            this.client = client;
            this.topic = topic;
            this.payload = payload;
            this.options = options;
            this.pointCode = pointCode;
            this.targetId = targetId;
        }
    }

    /**
     * 发布结果
     */
    @Data
    private static class PublishResult {
        private final boolean success;
        private final String errorMessage;
        private final int messageId;
        private final String pointCode;
        private final String targetId;

        /**
         * 创建当前组件实例。
         */
        public PublishResult(boolean success, String errorMessage, int messageId,
                             String pointCode, String targetId) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.messageId = messageId;
            this.pointCode = pointCode;
            this.targetId = targetId;
        }
    }

    /**
     * MQTT客户端管理器 - v5
     */
    private static class MqttClientManager {
        private final Map<String, MqttClientHolder> clients = new ConcurrentHashMap<>();
        private final Map<String, Long> lastReconnectErrorLog = new ConcurrentHashMap<>();
        private static final long ERROR_LOG_INTERVAL_MS = 10000L;
        private final ScheduledExecutorService monitorExecutor;
        private final Semaphore connectSemaphore;
        private final long reconnectScanIntervalMs;
        private ScheduledFuture<?> monitorFuture;
        private ScheduledFuture<?> ackMonitorFuture;
        private final AckManager ackManager;
        private final MqttDownlinkService downlinkService;
        private final MqttBusinessReplyService businessReplyService;
        private final MqttCloudDeviceLifecyclePublisher lifecyclePublisher;
        private final MqttAckReplyObserver ackReplyObserver;

        /**
         * 创建当前组件实例。
         */
        public MqttClientManager(AckManager ackManager,
                                 MqttDownlinkService downlinkService,
                                 MqttBusinessReplyService businessReplyService,
                                 MqttCloudDeviceLifecyclePublisher lifecyclePublisher,
                                 MqttAckReplyObserver ackReplyObserver,
                                 ScheduledExecutorService monitorExecutor,
                                 int maxConcurrentConnects,
                                 long reconnectScanIntervalMs) {
            this.ackManager = ackManager;
            this.downlinkService = downlinkService;
            this.businessReplyService = businessReplyService;
            this.lifecyclePublisher = lifecyclePublisher;
            this.ackReplyObserver = ackReplyObserver;
            this.monitorExecutor = monitorExecutor;
            this.connectSemaphore = new Semaphore(Math.max(1, maxConcurrentConnects));
            this.reconnectScanIntervalMs = Math.max(1000L, reconnectScanIntervalMs);
        }

        /**
         * 处理组件生命周期。
         */
        public void init() {
            if (monitorExecutor != null && !monitorExecutor.isShutdown()) {
                monitorFuture = monitorExecutor.scheduleAtFixedRate(
                        this::monitorClients,
                        reconnectScanIntervalMs,
                        reconnectScanIntervalMs,
                        TimeUnit.MILLISECONDS);
                ackMonitorFuture = monitorExecutor.scheduleAtFixedRate(ackManager::expireTimeouts, 1, 1, TimeUnit.SECONDS);
            }
            log.info("MQTT v5 客户端管理器初始化完成，maxConcurrentConnects={}，reconnectScanIntervalMs={}",
                    connectSemaphore.availablePermits(), reconnectScanIntervalMs);
        }

        public MqttAsyncClient getClient(MqttConnectionConfig config) throws MqttException {
            MqttClientHolder holder = holder(config);
            return holder.getOrConnect(config);
        }

        /**
         * 执行当前业务逻辑。
         */
        public boolean tryReconnect(MqttConnectionConfig config) {
            if (config == null) {
                return false;
            }
            String configKey = config.getKey();
            try {
                MqttClientHolder holder = holder(config);
                return holder.reconnect(config) != null;
            } catch (MqttException e) {
                logReconnectFailure(configKey, config.getBrokerUrl(), e);
                return false;
            }
        }

        /**
         * 更新或刷新业务状态。
         */
        public void updateConnectionConfig(MqttConnectionConfig config) {
            if (config == null) {
                return;
            }
            try {
                MqttClientHolder holder = clients.computeIfAbsent(config.getKey(), MqttClientHolder::new);
                holder.close();
                holder.updateConfig(config);
                holder.reconnect(config);
            } catch (MqttException e) {
                log.error("更新 MQTT v5 客户端配置失败：{}", config.getBrokerUrl(), e);
            }
        }

        /**
         * 清理或删除业务数据。
         */
        public void removeClient(String configKey) {
            MqttClientHolder holder = clients.remove(configKey);
            if (holder != null) {
                holder.close();
            }
        }

        /**
         * 处理组件生命周期。
         */
        public void destroy() {
            if (monitorFuture != null) {
                monitorFuture.cancel(false);
                monitorFuture = null;
            }
            if (ackMonitorFuture != null) {
                ackMonitorFuture.cancel(false);
                ackMonitorFuture = null;
            }

            for (MqttClientHolder holder : clients.values()) {
                holder.close();
            }

            clients.clear();
            log.info("MQTT v5 客户端管理器销毁完成");
        }

        public Map<String, Object> getStatus() {
            Map<String, Object> status = new HashMap<>();
            status.put("clientCount", clients.size());
            status.put("availableConnectPermits", connectSemaphore.availablePermits());
            status.put("waitingConnectThreads", connectSemaphore.getQueueLength());

            Map<String, Object> clientStatus = new HashMap<>();
            for (Map.Entry<String, MqttClientHolder> entry : clients.entrySet()) {
                MqttAsyncClient client = entry.getValue().client();
                Map<String, Object> clientInfo = new HashMap<>();
                clientInfo.put("connected", client != null && client.isConnected());
                clientInfo.put("serverURI", client != null ? client.getServerURI() : null);
                clientStatus.put(entry.getKey(), clientInfo);
            }
            status.put("clients", clientStatus);

            return status;
        }

        public Map<String, Object> getStatistics() {
            Map<String, Object> stats = new HashMap<>();

            int connectedCount = 0;
            for (MqttClientHolder holder : clients.values()) {
                MqttAsyncClient client = holder.client();
                if (client != null && client.isConnected()) {
                    connectedCount++;
                }
            }

            stats.put("totalClients", clients.size());
            stats.put("connectedClients", connectedCount);
            stats.put("disconnectedClients", clients.size() - connectedCount);

            return stats;
        }

        /**
         * 执行当前业务逻辑。
         */
        private MqttClientHolder holder(MqttConnectionConfig config) {
            String configKey = config.getKey();
            MqttClientHolder holder = clients.computeIfAbsent(configKey, MqttClientHolder::new);
            holder.updateConfig(config);
            return holder;
        }

        /**
         * 创建并返回业务对象。
         */
        private MqttAsyncClient createMqttClient(MqttConnectionConfig config) throws MqttException {
            try {
                String brokerUrl = config.getBrokerUrl();
                String clientId = config.getClientId();
                MemoryPersistence persistence = new MemoryPersistence();

                MqttAsyncClient asyncClient = new MqttAsyncClient(brokerUrl, clientId, persistence);
                MqttConnectionOptions options = buildConnectOptions(config);
                CloudProtocolAdapter protocolAdapter = config.getCloudTargetContext() != null
                        ? config.getCloudTargetContext().protocolAdapter()
                        : null;
                MqttInboundMessageDispatcher inboundDispatcher = new MqttInboundMessageDispatcher(
                        protocolAdapter,
                        config.getAckTopicSuffix(),
                        new MqttAckReplyHandler(OBJECT_MAPPER, ackReply -> {
                            ackManager.complete(
                                    ackReply.messageId(),
                                    AckMessage.received(ackReply.messageId(), ackReply.code(), ackReply.message()));
                            if (ackReplyObserver != null) {
                                try {
                                    ackReplyObserver.onAck(ackReply);
                                } catch (RuntimeException exception) {
                                    log.error("提交MQTT业务确认到持久化发件箱失败，消息={}",
                                            ackReply.messageId(), exception);
                                }
                            }
                        }),
                        businessReplyService,
                        new MqttDownlinkCommandHandler(downlinkService, (topic, payload, qos) -> {
                            MqttMessage response = new MqttMessage(payload);
                            response.setQos(qos);
                            asyncClient.publish(topic, response);
                        }, config.getAckTopicSuffix()));
                asyncClient.setCallback(new MqttCallbackHandler(config, inboundDispatcher));

                connectWithPermit(asyncClient, options);
                publishGatewayOnline(asyncClient, config);

                log.info("MQTT v5 客户端创建并连接成功：{} -> {}", clientId, brokerUrl);
                return asyncClient;
            } catch (MqttException e) {
                log.error("创建 MQTT v5 客户端失败：{} -> {}", config.getClientId(), config.getBrokerUrl(), e);
                throw e;
            }
        }

        /**
         * 处理连接生命周期。
         */
        private void connectClient(MqttAsyncClient client, MqttConnectionConfig config) throws MqttException {
            try {
                MqttConnectionOptions options = buildConnectOptions(config);
                connectWithPermit(client, options);
                publishGatewayOnline(client, config);
                log.info("MQTT v5 客户端重新连接成功：{}", config.getBrokerUrl());
            } catch (MqttException e) {
                String message = e.getMessage();
                if (message != null && (message.contains("connect in progress") || message.contains("disconnecting"))) {
                    log.warn("MQTT v5 客户端处于连接过渡状态（{}），忽略本次请求：{}", message, config.getBrokerUrl());
                    return;
                }
                throw e;
            }
        }

        /**
         * 执行当前业务逻辑。
         */
        private void publishGatewayOnline(MqttAsyncClient client, MqttConnectionConfig config) {
            if (!shouldPublishOnline(config)) {
                return;
            }
            if (lifecyclePublisher == null) {
                log.warn("MQTT 生命周期发布器未初始化，跳过网关上线状态上报：{}", config.getKey());
                return;
            }
            lifecyclePublisher.publishGatewayOnline(
                    client,
                    config.getGatewayIdentity(),
                    config.getLifecycleQos(),
                    config.getLifecyclePublishTimeoutMs());
        }

        /**
         * 执行当前业务逻辑。
         */
        private void publishGatewayOffline(MqttAsyncClient client, MqttConnectionConfig config) {
            if (!shouldPublishOffline(config)) {
                return;
            }
            if (lifecyclePublisher == null) {
                log.warn("MQTT 生命周期发布器未初始化，跳过网关离线状态上报：{}", config.getKey());
                return;
            }
            lifecyclePublisher.publishGatewayOffline(
                    client,
                    config.getGatewayIdentity(),
                    config.getLifecycleQos(),
                    config.getLifecyclePublishTimeoutMs());
        }

        /**
         * 执行当前业务逻辑。
         */
        private boolean shouldPublishOnline(MqttConnectionConfig config) {
            return config != null && config.isLifecycleEnabled() && config.isGatewayOnlineEnabled();
        }

        /**
         * 执行当前业务逻辑。
         */
        private boolean shouldPublishOffline(MqttConnectionConfig config) {
            return config != null && config.isLifecycleEnabled() && config.isGatewayGracefulOfflineEnabled();
        }

        /**
         * 处理连接生命周期。
         */
        private void connectWithPermit(MqttAsyncClient client, MqttConnectionOptions options) throws MqttException {
            boolean acquired = false;
            try {
                connectSemaphore.acquire();
                acquired = true;
                IMqttToken token = client.connect(options);
                token.waitForCompletion();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MqttException(e);
            } catch (MqttException e) {
                throw e;
            } catch (Exception e) {
                throw new MqttException(e);
            } finally {
                if (acquired) {
                    connectSemaphore.release();
                }
            }
        }

        /**
         * 执行当前业务逻辑。
         */
        private void logReconnectFailure(String configKey, String brokerUrl, Exception e) {
            long now = System.currentTimeMillis();
            Long last = lastReconnectErrorLog.get(configKey);
            if (last == null || now - last >= ERROR_LOG_INTERVAL_MS) {
                lastReconnectErrorLog.put(configKey, now);
                log.warn("MQTT v5 客户端重连失败：{} ({})", configKey, brokerUrl, e);
            } else {
                log.debug("忽略重复的 MQTT 重连失败日志 {}: {}", configKey, e.getMessage());
            }
        }

        /**
         * 创建并返回业务对象。
         */
        private MqttConnectionOptions buildConnectOptions(MqttConnectionConfig config) {
            MqttConnectionOptions options = new MqttConnectionOptions();

            options.setCleanStart(config.isCleanStart());
            options.setConnectionTimeout(config.getConnectionTimeout());
            options.setKeepAliveInterval(config.getKeepAliveInterval());
            // 统一由本管理器串行重连，禁止 Paho 自动重连绕过建连闸门。
            options.setAutomaticReconnect(false);
            options.setMaxReconnectDelay(config.getMaxReconnectDelay());

            if (config.getUsername() != null) {
                options.setUserName(config.getUsername());
            }
            if (config.getPassword() != null) {
                options.setPassword(new String(config.getPassword()).getBytes());
            }

            MqttWillMessage will = config.getWillMessage();
            if (will != null) {
                MqttMessage willMessage = new MqttMessage(will.getMessage());
                willMessage.setQos(will.getQos());
                willMessage.setRetained(will.isRetained());
                options.setWill(will.getTopic(), willMessage);
            }

            if (config.isSslEnabled()) {
                configureSsl(options);
            }

            return options;
        }

        /**
         * 执行当前业务逻辑。
         */
        private void configureSsl(MqttConnectionOptions options) {
            // SSL/TLS 配置预留，后续按平台证书要求扩展。
        }

        /**
         * 执行当前业务逻辑。
         */
        private void monitorClients() {
            for (Map.Entry<String, MqttClientHolder> entry : clients.entrySet()) {
                String configKey = entry.getKey();
                MqttClientHolder holder = entry.getValue();
                MqttConnectionConfig config = holder.config();
                MqttAsyncClient client = holder.client();

                if (config != null && (client == null || !client.isConnected())) {
                    try {
                        log.info("MQTT v5 客户端已断开，尝试重新连接：{}", configKey);
                        holder.reconnect(config);
                    } catch (MqttException e) {
                        logReconnectFailure(configKey, config.getBrokerUrl(), e);
                    }
                }
            }
        }

        /**
         * 定义当前模块的业务组件。
         */
        private class MqttClientHolder {
            private final String configKey;
            private final ReentrantLock lifecycleLock = new ReentrantLock();
            private volatile MqttConnectionConfig config;
            private volatile MqttAsyncClient client;

            /**
             * 创建当前组件实例。
             */
            private MqttClientHolder(String configKey) {
                this.configKey = configKey;
            }

            /**
             * 更新或刷新业务状态。
             */
            private void updateConfig(MqttConnectionConfig latestConfig) {
                if (latestConfig != null) {
                    this.config = latestConfig;
                }
            }

            /**
             * 执行当前业务逻辑。
             */
            private MqttConnectionConfig config() {
                return config;
            }

            /**
             * 执行当前业务逻辑。
             */
            private MqttAsyncClient client() {
                return client;
            }

            private MqttAsyncClient getOrConnect(MqttConnectionConfig latestConfig) throws MqttException {
                updateConfig(latestConfig);
                MqttAsyncClient existing = client;
                if (existing != null && existing.isConnected()) {
                    return existing;
                }
                return reconnect(config);
            }

            /**
             * 处理连接生命周期。
             */
            private MqttAsyncClient reconnect(MqttConnectionConfig latestConfig) throws MqttException {
                updateConfig(latestConfig);
                lifecycleLock.lock();
                try {
                    MqttConnectionConfig activeConfig = config;
                    if (activeConfig == null) {
                        return null;
                    }
                    MqttAsyncClient existing = client;
                    if (existing == null) {
                        client = createMqttClient(activeConfig);
                        return client;
                    }
                    if (existing.isConnected()) {
                        return existing;
                    }
                    connectClient(existing, activeConfig);
                    return existing;
                } finally {
                    lifecycleLock.unlock();
                }
            }

            /**
             * 执行当前业务逻辑。
             */
            private void close() {
                lifecycleLock.lock();
                try {
                    MqttAsyncClient existing = client;
                    MqttConnectionConfig activeConfig = config;
                    client = null;
                    if (existing == null) {
                        return;
                    }
                    try {
                        publishGatewayOffline(existing, activeConfig);
                        if (existing.isConnected()) {
                            existing.disconnect();
                        }
                        existing.close();
                    } catch (MqttException e) {
                        log.warn("关闭 MQTT v5 客户端失败：{}", configKey, e);
                    }
                } finally {
                    lifecycleLock.unlock();
                }
            }
        }

    }

    /**
     * MQTT v5 回调处理器。
     */
    private static class MqttCallbackHandler implements MqttCallback {
        private final MqttConnectionConfig config;
        private final MqttInboundMessageDispatcher inboundDispatcher;

        /**
         * 创建当前组件实例。
         */
        private MqttCallbackHandler(MqttConnectionConfig config,
                                    MqttInboundMessageDispatcher inboundDispatcher) {
            this.config = config;
            this.inboundDispatcher = inboundDispatcher;
        }

        /**
         * 处理连接生命周期。
         */
        @Override
        public void disconnected(MqttDisconnectResponse disconnectResponse) {
            log.warn("MQTT v5连接已断开：broker={} returnCode={}",
                    config.getBrokerUrl(),
                    disconnectResponse != null ? disconnectResponse.getReturnCode() : -1);
        }

        /**
         * 执行当前业务逻辑。
         */
        @Override
        public void mqttErrorOccurred(MqttException exception) {
            log.error("MQTT v5发生异常：broker={}", config.getBrokerUrl(), exception);
        }

        /**
         * 执行当前业务逻辑。
         */
        @Override
        public void messageArrived(String topic, MqttMessage message) {
            int payloadLength = message != null && message.getPayload() != null
                    ? message.getPayload().length : 0;
            log.trace("MQTT v5收到消息：主题={} 服务质量={} 字节数={}",
                    topic, message != null ? message.getQos() : -1, payloadLength);
            inboundDispatcher.dispatch(new MqttInboundMessage(
                    topic,
                    message != null ? message.getPayload() : null,
                    message != null ? message.getQos() : 0,
                    config.getCloudProvider()));
        }

        /**
         * 执行当前业务逻辑。
         */
        @Override
        public void deliveryComplete(IMqttToken token) {
            log.trace("MQTT v5消息发布完成：消息={}", token != null ? token.getMessageId() : -1);
        }

        /**
         * 处理连接生命周期。
         */
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            log.info("MQTT v5连接完成：type={} serverURI={}", reconnect ? "重连" : "首次连接", serverURI);
        }

        /**
         * 执行当前业务逻辑。
         */
        @Override
        public void authPacketArrived(int reasonCode, MqttProperties properties) {
            log.trace("MQTT v5认证包到达：reasonCode={}", reasonCode);
        }
    }

    /**
     * MQTT消息发布器 - v5
     */
    private static class MessagePublisher {
        private final MqttClientManager clientManager;
        private final ExecutorService publishExecutor;
        private final AtomicLong totalPublishCount = new AtomicLong(0);
        private final AtomicLong successPublishCount = new AtomicLong(0);
        private final AtomicLong failurePublishCount = new AtomicLong(0);

        /**
         * 创建当前组件实例。
         */
        public MessagePublisher(MqttClientManager clientManager, ExecutorService publishExecutor) {
            this.clientManager = clientManager;
            this.publishExecutor = publishExecutor;
        }

        /**
         * 处理组件生命周期。
         */
        public void init() {
            log.info("MQTT v5 消息发布管理器初始化完成");
        }

        /**
         * 执行当前业务逻辑。
         */
        public PublishResult publish(MqttAsyncClient client, String topic,
                                     byte[] payload, MqttPublishOptions options) {
            totalPublishCount.incrementAndGet();
            long startTime = System.currentTimeMillis();

            try {
                // 创建 MQTT v5 消息
                MqttMessage message = new MqttMessage(payload);
                message.setQos(options.getQos());
                message.setRetained(options.isRetained());

                IMqttToken token = null;
                int messageId = 0;

                // 异步发布消息，按 QoS 等待确认
                if (client != null) {
                    // 异步发布消息
                    token = client.publish(topic, message);

                    // QoS 大于 0 时等待发布完成
                    if (options.getQos() > 0) {
                        token.waitForCompletion(5000); // 等待发布完成
                    }

                    // 获取消息 ID
                    try {
                        MqttPublish publishPacket = (MqttPublish) token.getRequestMessage();
                        if (publishPacket != null) {
                            messageId = publishPacket.getMessageId();
                        }
                    } catch (Exception e) {
                        messageId = (int)(System.currentTimeMillis() % 1000);
                    }
                } else {
                    return new PublishResult(false, "MQTT client is null", 0, null, null);
                }

                // 检查发布结果
                MqttException exception = (token != null) ? token.getException() : null;
                if (exception == null) {
                    successPublishCount.incrementAndGet();
                    long costTime = System.currentTimeMillis() - startTime;

                    log.debug("MQTT v5消息发布成功：{}，QoS={}，耗时={}ms",
                            topic, options.getQos(), costTime);

                    return new PublishResult(true, null, messageId, null, null);
                } else {
                    failurePublishCount.incrementAndGet();
                    log.warn("MQTT v5消息发布失败：{}，错误={}", topic, exception.getMessage());
                    return new PublishResult(false, exception.getMessage(), 0, null, null);
                }

            } catch (Exception e) {
                failurePublishCount.incrementAndGet();
                log.error("MQTT v5消息发布异常：{}", topic, e);
                return new PublishResult(false, e.getMessage(), 0, null, null);
            }
        }

        /**
         * 执行当前业务逻辑。
         */
        public List<PublishResult> publishBatch(List<PublishTask> tasks) {
            List<PublishResult> results = new ArrayList<>(tasks.size());
            List<Future<PublishResult>> futures = new ArrayList<>(tasks.size());

            // 提交所有发布任务
            for (PublishTask task : tasks) {
                Future<PublishResult> future = publishExecutor.submit(() ->
                        publish(task.getClient(), task.getTopic(), task.getPayload(), task.getOptions())
                );
                futures.add(future);
            }

            // 收集所有结果
            for (int i = 0; i < futures.size(); i++) {
                Future<PublishResult> future = futures.get(i);
                PublishTask task = tasks.get(i);

                try {
                    PublishResult publishResult = future.get(10, TimeUnit.SECONDS);
                    // 补充点位和目标信息
                    PublishResult resultWithInfo = new PublishResult(
                            publishResult.isSuccess(),
                            publishResult.getErrorMessage(),
                            publishResult.getMessageId(),
                            task.getPointCode(),
                            task.getTargetId()
                    );
                    results.add(resultWithInfo);
                } catch (Exception e) {
                    log.error("MQTT v5批量发布任务执行失败", e);
                    results.add(new PublishResult(
                            false,
                            "任务执行失败: " + e.getMessage(),
                            0,
                            task.getPointCode(),
                            task.getTargetId()
                    ));
                }
            }

            return results;
        }

        /**
         * 处理组件生命周期。
         */
        public void destroy() {
            log.info("MQTT v5消息发布管理器销毁完成");
        }

        public Map<String, Object> getStatistics() {
            Map<String, Object> stats = new HashMap<>();

            long total = totalPublishCount.get();
            long success = successPublishCount.get();
            long failure = failurePublishCount.get();

            stats.put("totalPublishCount", total);
            stats.put("successPublishCount", success);
            stats.put("failurePublishCount", failure);

            if (total > 0) {
                double successRate = (double) success / total * 100;
                stats.put("publishSuccessRate", String.format("%.2f%%", successRate));
            }

            return stats;
        }
    }

    /**
     * 订阅管理器 - v5
     */
    private static class SubscriptionManager {
        private final MqttClientManager clientManager;
        private final Map<String, Set<String>> clientSubscriptions = new ConcurrentHashMap<>();
        private final AtomicLong totalSubscribeCount = new AtomicLong(0);

        /**
         * 创建当前组件实例。
         */
        public SubscriptionManager(MqttClientManager clientManager) {
            this.clientManager = clientManager;
        }

        /**
         * 处理组件生命周期。
         */
        public void init() {
            log.info("MQTT v5 订阅管理器初始化完成");
        }

        /**
         * 更新或刷新业务状态。
         */
        public void updateSubscriptions(MqttConnectionConfig config, List<String> topics) {
            String configKey = config.getKey();

            try {
                MqttAsyncClient client = clientManager.getClient(config);
                if (client == null || !client.isConnected()) {
                    log.warn("无法更新订阅，MQTT 客户端未连接：{}", configKey);
                    return;
                }

                // 获取当前订阅
                Set<String> currentTopics = clientSubscriptions.getOrDefault(configKey, new HashSet<>());
                Set<String> newTopics = new HashSet<>(topics);

                // 计算需要取消的订阅
                Set<String> toUnsubscribe = new HashSet<>(currentTopics);
                toUnsubscribe.removeAll(newTopics);

                // 计算需要新增的订阅
                Set<String> toSubscribe = new HashSet<>(newTopics);
                toSubscribe.removeAll(currentTopics);

                // 取消订阅
                for (String topic : toUnsubscribe) {
                    try {
                        client.unsubscribe(topic);
                        log.debug("取消 MQTT v5 订阅：{} -> {}", configKey, topic);
                    } catch (MqttException e) {
                        log.warn("取消 MQTT v5 订阅失败：{} -> {}", configKey, topic, e);
                    }
                }

                // 新增订阅
                for (String topic : toSubscribe) {
                    try {
                        // MQTT v5 订阅返回 IMqttToken。
                        IMqttToken token = client.subscribe(topic, 1); // 默认 QoS 1
                        token.waitForCompletion(5000);
                        totalSubscribeCount.incrementAndGet();
                        log.debug("新增 MQTT v5 订阅：{} -> {}", configKey, topic);
                    } catch (MqttException e) {
                        log.warn("新增 MQTT v5 订阅失败：{} -> {}", configKey, topic, e);
                    }
                }

                // 更新订阅状态
                clientSubscriptions.put(configKey, newTopics);

                log.info("MQTT v5订阅更新完成：{}，当前订阅数量={}", configKey, newTopics.size());

            } catch (Exception e) {
                log.error("更新 MQTT v5 订阅失败：{}", configKey, e);
            }
        }

        /**
         * 清理或删除业务数据。
         */
        public void removeSubscriptions(String configKey) {
            Set<String> topics = clientSubscriptions.remove(configKey);
            if (topics != null && !topics.isEmpty()) {
                log.info("移除 MQTT v5 订阅：{}，数量={}", configKey, topics.size());
            }
        }

        /**
         * 处理组件生命周期。
         */
        public void destroy() {
            clientSubscriptions.clear();
            log.info("MQTT v5订阅管理器销毁完成");
        }

        public Map<String, Object> getStatistics() {
            Map<String, Object> stats = new HashMap<>();

            int totalSubscriptions = 0;
            for (Set<String> topics : clientSubscriptions.values()) {
                totalSubscriptions += topics.size();
            }

            stats.put("totalSubscribeCount", totalSubscribeCount.get());
            stats.put("currentSubscriptions", totalSubscriptions);
            stats.put("clientCount", clientSubscriptions.size());

            return stats;
        }
    }
}
