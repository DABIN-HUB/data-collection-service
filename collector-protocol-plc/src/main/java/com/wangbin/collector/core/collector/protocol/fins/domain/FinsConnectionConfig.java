package com.wangbin.collector.core.collector.protocol.fins.domain;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.FinsTransportMode;
import lombok.Getter;

/**
 * 装配当前模块的配置。
 */
@Getter
public class FinsConnectionConfig {

    private final String host;
    private final int port;
    private final int plcNetwork;
    private final int plcNode;
    private final int plcUnit;
    private final int localNetwork;
    private final int localNode;
    private final int localUnit;
    private final int serviceIdSeed;
    private final int timeoutMs;
    private final FinsTransportMode transport;
    private final int connectTimeoutMs;
    private final int receiveBufferSize;
    private final int maxFrameSize;
    private final int maxWordsPerRequest;
    private final int maxBitsPerRequest;
    private final boolean batchReadEnabled;
    private final FinsByteOrder byteOrder;
    private final FinsWordOrder wordOrder;

    /**
     * 创建当前组件实例。
     */
    public FinsConnectionConfig(String host,
                                int port,
                                int plcNetwork,
                                int plcNode,
                                int plcUnit,
                                int localNetwork,
                                int localNode,
                                int localUnit,
                                int serviceIdSeed,
                                int timeoutMs,
                                FinsTransportMode transport,
                                int connectTimeoutMs,
                                int receiveBufferSize,
                                int maxFrameSize,
                                int maxWordsPerRequest,
                                int maxBitsPerRequest,
                                boolean batchReadEnabled,
                                FinsByteOrder byteOrder,
                                FinsWordOrder wordOrder) {
        this.host = host;
        this.port = port;
        this.plcNetwork = plcNetwork;
        this.plcNode = plcNode;
        this.plcUnit = plcUnit;
        this.localNetwork = localNetwork;
        this.localNode = localNode;
        this.localUnit = localUnit;
        this.serviceIdSeed = serviceIdSeed;
        this.timeoutMs = timeoutMs;
        this.transport = transport;
        this.connectTimeoutMs = connectTimeoutMs;
        this.receiveBufferSize = receiveBufferSize;
        this.maxFrameSize = maxFrameSize;
        this.maxWordsPerRequest = maxWordsPerRequest;
        this.maxBitsPerRequest = maxBitsPerRequest;
        this.batchReadEnabled = batchReadEnabled;
        this.byteOrder = byteOrder;
        this.wordOrder = wordOrder;
    }

    /**
     * 创建并返回业务对象。
     */
    public static FinsConnectionConfig from(DeviceConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("FINS connection config cannot be null");
        }
        int timeout = positive(firstPositive(connection.getReadTimeout(), connection.getTimeout()), 5000);
        FinsTransportMode mode = FinsTransportMode.from(connection.getProperty("transport"));
        return new FinsConnectionConfig(
                connection.getHost(),
                positive(connection.getPort(), 9600),
                bounded(connection.getIntConfig("plcNetwork", 0), 0, 255, 0),
                bounded(required(connection.getIntConfig("plcNode", null), "plcNode"), 0, 255, 0),
                bounded(connection.getIntConfig("plcUnit", 0), 0, 255, 0),
                bounded(connection.getIntConfig("localNetwork", 0), 0, 255, 0),
                bounded(required(connection.getIntConfig("localNode", null), "localNode"),
                        0, mode == FinsTransportMode.TCP ? 254 : 255, 0),
                bounded(connection.getIntConfig("localUnit", 0), 0, 255, 0),
                bounded(connection.getIntConfig("serviceIdSeed", 1), 0, 255, 1),
                timeout,
                mode,
                positiveRequired(connection.getConnectTimeout(), 5000, "connectTimeout"),
                positiveRequired(connection.getBufferSize(), 8192, "bufferSize"),
                frameSize(connection.getIntConfig("maxFrameSize", null)),
                positive(connection.getIntConfig("maxWordsPerRequest", 120), 120),
                positive(connection.getIntConfig("maxBitsPerRequest", 256), 256),
                !Boolean.FALSE.equals(connection.getBoolConfig("batchReadEnabled", true)),
                FinsByteOrder.from(connection.getStringConfig("byteOrder", null), FinsByteOrder.BIG_ENDIAN),
                FinsWordOrder.from(connection.getStringConfig("wordOrder", null), FinsWordOrder.BIG_ENDIAN)
        );
    }

    /** 保留 DeviceInfo 旧式 IP/端口回退，但将解析后的 endpoint 统一放入 FINS 配置。 */
    public static FinsConnectionConfig from(DeviceConnection connection, DeviceInfo deviceInfo) {
        FinsConnectionConfig parsed = from(connection);
        String host = parsed.host != null && !parsed.host.isBlank() ? parsed.host.trim()
                : deviceInfo == null ? null : deviceInfo.getIpAddress();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("FINS host is required");
        }
        int port = connection.getPort() != null && connection.getPort() > 0
                ? connection.getPort() : deviceInfo != null && deviceInfo.getPort() != null
                && deviceInfo.getPort() > 0 ? deviceInfo.getPort() : parsed.port;
        return new FinsConnectionConfig(host.trim(), port, parsed.plcNetwork, parsed.plcNode, parsed.plcUnit,
                parsed.localNetwork, parsed.localNode, parsed.localUnit, parsed.serviceIdSeed, parsed.timeoutMs,
                parsed.transport, parsed.connectTimeoutMs, parsed.receiveBufferSize, parsed.maxFrameSize,
                parsed.maxWordsPerRequest, parsed.maxBitsPerRequest, parsed.batchReadEnabled,
                parsed.byteOrder, parsed.wordOrder);
    }

    /**
     * 使用 TCP 会话协商的节点生成仅用于构帧的不可变配置，不回写设备配置。
     */
    public FinsConnectionConfig withEffectiveNodes(int sourceNode, int destinationNode) {
        return new FinsConnectionConfig(host, port, plcNetwork,
                bounded(destinationNode, 0, 255, 0), plcUnit, localNetwork,
                bounded(sourceNode, 0, 255, 0), localUnit, serviceIdSeed, timeoutMs,
                transport, connectTimeoutMs, receiveBufferSize, maxFrameSize,
                maxWordsPerRequest, maxBitsPerRequest, batchReadEnabled, byteOrder, wordOrder);
    }

    private static int frameSize(Integer value) {
        if (value == null) {
            return 8192;
        }
        if (value < 34 || value > 1_048_576) {
            throw new IllegalArgumentException("OMRON_FINS maxFrameSize must be between 34 and 1048576");
        }
        return value;
    }

    private static int positiveRequired(Integer value, int defaultValue, String field) {
        if (value == null) {
            return defaultValue;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("FINS " + field + " must be positive");
        }
        return value;
    }

    /**
     * 执行业务逻辑。
     */
    private static Integer firstPositive(Integer first, Integer second) {
        if (first != null && first > 0) {
            return first;
        }
        return second;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int positive(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int bounded(Integer value, int min, int max, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("FINS config out of range: " + value);
        }
        return value;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private static Integer required(Integer value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("FINS requires " + field);
        }
        return value;
    }
}