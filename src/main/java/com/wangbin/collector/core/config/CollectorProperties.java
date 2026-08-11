package com.wangbin.collector.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 采集器配置类
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "collector")
public class CollectorProperties {

    /**
     * SNMP配置
     */
    private SnmpConfig snmp = new SnmpConfig();

    /**
     * OPC UA配置
     */
    private OpcUaConfig opcUa = new OpcUaConfig();

    /**
     * MQTT配置
     */
    private MqttConfig mqtt = new MqttConfig();

    /**
     * Modbus配置
     */
    private ModbusConfig modbus = new ModbusConfig();

    /**
     * CoAP配置
     */
    private CoapConfig coap = new CoapConfig();

    /**
     * IEC104配置
     */
    private Iec104Config iec104 = new Iec104Config();

    /**
     * IEC61850配置
     */
    private Iec61850Config iec61850 = new Iec61850Config();

    /**
     * 通用配置
     */
    private CommonConfig common = new CommonConfig();

    /**
     * 调度器配置
     */
    private SchedulerConfig scheduler = new SchedulerConfig();

    /**
     * 自适应采集配置
     */
    private AdaptiveCollectionConfig adaptiveCollection = new AdaptiveCollectionConfig();

    /**
     * 配置加载配置。
     */
    @Valid
    private ConfigConfig config = new ConfigConfig();

    // =============== 配置类定义 ===============

    /**
     * 装配当前模块的配置。
     */
    @Data
    public static class SnmpConfig {
        private String community = "public";
        private int timeout = 3000;
        private int retries = 2;
        private String version = "2c";
        private int pollingInterval = 5000;
        private Map<String, String> devices;
        private String securityLevel = "authPriv";
        private String securityName;
        private String authProtocol = "SHA";
        private String authPassword;
        private String privProtocol = "AES128";
        private String privPassword;
        private String contextName;
        private String contextEngineId;
    }

    /**
     * 装配当前模块的配置。
     */
    @Data
    public static class OpcUaConfig {
        private String securityPolicy = "None";
        private String messageMode = "Binary";
        private int requestTimeout = 5000;
        private int subscriptionInterval = 1000;
        private Map<String, String> endpoints;
    }

    /**
     * 装配当前模块的配置。
     */
    @Data
    public static class MqttConfig {
        private String brokerUrl = "tcp://localhost:1883";
        private String clientId;
        private String username;
        private String password;
        private int qos = 1;
        private boolean cleanSession = true;
        private int connectionTimeout = 30;
        private int keepAliveInterval = 60;
        private int maxPendingMessages = 5000;
        private int dispatchBatchSize = 1;
        private long dispatchFlushInterval = 0;
        private String overflowStrategy = "BLOCK";
        private int maxGroupConnections = 0;
        /**
         * MQTT 建连全局并发上限。
         * 平台不支持并发创建连接时应配置为 1。
         */
        private int maxConcurrentConnects = 1;
    }

    /**
     * 装配当前模块的配置。
     */
    @Data
    public static class ModbusConfig {
        private int timeout = 3000;
        private int retries = 2;
        private int poolSize = 10;
        private Map<String, String> connections;
    }

    /**
     * 装配当前模块的配置。
     */
    @Data
    public static class CoapConfig {
        private int timeout = 3000;
        private int retries = 2;
        private String scheme = "coap";
        private Map<String, String> servers;
    }

    /**
     * 装配当前模块的配置。
     */
    @Data
    public static class Iec104Config {
        private int timeout = 5000;
        private int retries = 3;
        private int commonAddress = 1;
        private boolean generalInterrogationOnConnect = true;
        private long generalInterrogationInterval = 600000;
        private boolean singleInterrogationOnReadMiss = true;
        private long cacheTtl = 5000;
        private String singleInterrogationGroupField = "groupId";
        private Map<String, String> stations;
    }

    /**
     * 装配当前模块的配置。
     */
    @Data
    public static class Iec61850Config {
        private int timeout = 5000;
        private int retries = 3;
        private String securityPolicy = "None";
        private Map<String, String> servers;
    }

    /**
     * 装配当前模块的配置。
     */
    @Data
    public static class CommonConfig {
        private int heartbeatInterval = 30000;
        private int reconnectInterval = 5000;
        private int maxReconnectTimes = 3;
        private int dataCacheSize = 10000;
        private boolean enableMonitor = true;
        private boolean enableAlert = true;
    }

    /**
     * 调度器配置类
     */
    @Data
    public static class SchedulerConfig {
        private int initialTimeSliceCount = 2;
        private int maxTimeSliceCount = 10;
        private int minTimeSliceIntervalMs = 50;
        private int defaultTimeSliceIntervalMs = 1000;
        private int initialTimeSliceIntervalMs = 1000;
        private int dynamicAdjustIntervalMs = 30000;
        private long collectTimeoutMs = 500;
        private long deviceStartTimeoutMs = 15000;
        private int deviceStartExecutorSize = 4;
        private int reconnectExecutorSize = 4;
        private long reconnectBaseDelayMs = 1000;
        private long reconnectMaxDelayMs = 30000;
        /**
         * 每个时间片期望承载的批量采集任务数量，用于根据真实批任务负载计算分片数。
         */
        private int targetTasksPerTimeSlice = 8;
        /**
         * 每个时间片期望承载的点位数量，用于避免少量大批次设备形成瞬时突发。
         */
        private int targetPointsPerTimeSlice = 1000;
    }

    /**
     * 自适应采集配置类
     */
    @Data
    public static class AdaptiveCollectionConfig {
        private boolean enabled = true;
        private long adjustWindowMs = 60000;
    }

    /**
     * 配置加载与同步配置。
     */
    @Data
    public static class ConfigConfig {
        private String yunUrl = "http://localhost:8080/admin-api";

        @Min(1000)
        private long syncInterval = 30000;

        @Min(1000)
        private long syncInitialDelay = 30000;

        private String serviceId = "collector-1";
        private String tenantId = "1";
        private String apiToken = "";

        @Valid
        private FileConfig file = new FileConfig();
    }

    /**
     * 本地文件配置加载路径。
     */
    @Data
    public static class FileConfig {
        private String devices = "";
        private String pointsDir = "";
        private String connectionsDir = "";
    }
}
