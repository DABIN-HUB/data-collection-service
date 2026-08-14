package com.wangbin.collector.core.collector.protocol.fins.domain;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
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
        return new FinsConnectionConfig(
                connection.getHost(),
                positive(connection.getPort(), 9600),
                bounded(connection.getIntConfig("plcNetwork", 0), 0, 255, 0),
                bounded(required(connection.getIntConfig("plcNode", null), "plcNode"), 0, 255, 0),
                bounded(connection.getIntConfig("plcUnit", 0), 0, 255, 0),
                bounded(connection.getIntConfig("localNetwork", 0), 0, 255, 0),
                bounded(required(connection.getIntConfig("localNode", null), "localNode"), 0, 255, 0),
                bounded(connection.getIntConfig("localUnit", 0), 0, 255, 0),
                bounded(connection.getIntConfig("serviceIdSeed", 1), 0, 255, 1),
                timeout,
                positive(connection.getIntConfig("maxWordsPerRequest", 120), 120),
                positive(connection.getIntConfig("maxBitsPerRequest", 256), 256),
                !Boolean.FALSE.equals(connection.getBoolConfig("batchReadEnabled", true)),
                FinsByteOrder.from(connection.getStringConfig("byteOrder", null), FinsByteOrder.BIG_ENDIAN),
                FinsWordOrder.from(connection.getStringConfig("wordOrder", null), FinsWordOrder.BIG_ENDIAN)
        );
    }

    /**
     * 执行当前业务逻辑。
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