package com.wangbin.collector.core.collector.protocol.custom;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.custom.codec.CustomFrameCodec;
import com.wangbin.collector.core.collector.protocol.custom.codec.CustomRequestEncoder;
import com.wangbin.collector.core.collector.protocol.custom.codec.CustomValueCodec;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.CustomExchangeAdapter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于受控帧配置的自定义 TCP/UDP 请求响应采集器。
 */
@Slf4j
public class CustomProtocolCollector extends ConnectionBackedCollector {

    private CustomExchangeAdapter exchangeAdapter;
    private ConnectionAdapter<?> connectionAdapter;
    private DeviceConnection connectionConfig;
    private long timeoutMs;

    @Override
    public String getCollectorType() {
        return resolveProtocolType();
    }

    @Override
    public String getProtocolType() {
        return resolveProtocolType();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() {
        DeviceConnection desiredConfig = requireConnectionConfig();
        ConnectionAdapter<?> createdAdapter = createManagedConnection(desiredConfig);
        if (!(createdAdapter instanceof CustomExchangeAdapter customAdapter)) {
            removeManagedConnection("自定义协议");
            throw new IllegalStateException("自定义协议连接适配器类型不匹配");
        }
        try {
            connectManagedConnection();
            connectionAdapter = createdAdapter;
            exchangeAdapter = customAdapter;
            connectionConfig = createdAdapter.getConnectionConfig();
            timeoutMs = resolveTimeout(connectionConfig);
            log.info("自定义协议采集器已连接: 设备={}, 协议类型={}",
                    deviceInfo.getDeviceId(), resolveProtocolType());
        } catch (RuntimeException exception) {
            removeManagedConnection("自定义协议");
            throw exception;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeManagedConnection("自定义协议");
        exchangeAdapter = null;
        connectionAdapter = null;
        connectionConfig = null;
        log.info("自定义协议采集器已断开: 设备={}, 协议类型={}",
                deviceInfo.getDeviceId(), resolveProtocolType());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        byte[] request = CustomRequestEncoder.encodeRead(point, requireConfig());
        byte[] response = requireExchangeAdapter().exchange(request, timeoutMs);
        return CustomValueCodec.decode(response, point);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        Map<String, Object> results = new LinkedHashMap<>();
        if (points == null) {
            return results;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                results.put(resolvePointCacheKey(point), doReadPoint(point));
            } catch (Exception exception) {
                log.warn("自定义协议点位读取失败: 设备={}, 点位={}",
                        deviceInfo.getDeviceId(), point.getPointId(), exception);
                results.put(resolvePointCacheKey(point), null);
            }
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        DeviceConnection config = requireConfig();
        byte[] request = CustomRequestEncoder.encodeWrite(point, value, config);
        boolean expectResponse = point.getAdditionalConfig("writeExpectResponse",
                config.getBool("writeExpectResponse", true));
        if (!expectResponse) {
            requireExchangeAdapter().sendOnly(request);
            return true;
        }
        byte[] response = requireExchangeAdapter().exchange(request, timeoutMs);
        String successHex = point.getAdditionalConfig("writeSuccessHex",
                config.getString("writeSuccessHex", null));
        if (successHex == null || successHex.isBlank()) {
            return response != null;
        }
        return CustomFrameCodec.encodeHex(response)
                .startsWith(successHex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT));
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null) {
            return results;
        }
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null) {
                continue;
            }
            try {
                results.put(resolvePointCacheKey(point), doWritePoint(point, entry.getValue()));
            } catch (Exception exception) {
                log.warn("自定义协议点位写入失败: 设备={}, 点位={}",
                        deviceInfo.getDeviceId(), point.getPointId(), exception);
                results.put(resolvePointCacheKey(point), false);
            }
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSubscribe(List<DataPoint> points) {
        throw new UnsupportedOperationException("自定义请求响应协议暂不支持主动订阅");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        // 当前自定义协议没有主动订阅资源，无需释放额外句柄。
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("protocol", resolveProtocolType());
        status.put("implemented", true);
        status.put("writable", true);
        status.put("subscribable", false);
        status.put("transport", resolveProtocolType().endsWith("UDP") ? "UDP" : "TCP");
        status.put("frameMode", connectionConfig != null
                ? connectionConfig.getString("frameMode", resolveProtocolType().endsWith("UDP") ? "DATAGRAM" : "LENGTH_FIELD")
                : null);
        status.put("timeoutMs", timeoutMs);
        status.put("connectionStatistics", connectionAdapter != null
                ? connectionAdapter.getStatistics()
                : Collections.emptyMap());
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        if (!"RAW_EXCHANGE".equalsIgnoreCase(command)) {
            throw new IllegalArgumentException("不支持的自定义协议命令: " + command);
        }
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        byte[] request = resolveRawCommandRequest(safeParams);
        byte[] response = requireExchangeAdapter().exchange(request, timeoutMs);
        return Map.of(
                "responseHex", CustomFrameCodec.encodeHex(response),
                "responseText", new String(response, StandardCharsets.UTF_8));
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        log.debug("自定义协议按点位请求响应执行，不生成跨点位批量计划: 设备={}, 点位数量={}",
                deviceId, points == null ? 0 : points.size());
    }

    /**
     * 解析或转换业务数据。
     */
    private byte[] resolveRawCommandRequest(Map<String, Object> params) {
        Object requestHex = params.get("requestHex");
        if (requestHex != null && !requestHex.toString().isBlank()) {
            return CustomFrameCodec.decodeHex(requestHex.toString());
        }
        Object requestText = params.get("requestText");
        if (requestText != null) {
            return requestText.toString().getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("RAW_EXCHANGE必须提供requestHex或requestText");
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveProtocolType() {
        if (deviceInfo != null && deviceInfo.getProtocolType() != null && !deviceInfo.getProtocolType().isBlank()) {
            return deviceInfo.getProtocolType().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        }
        return "CUSTOM_TCP";
    }

    /**
     * 校验业务条件和参数边界。
     */
    private CustomExchangeAdapter requireExchangeAdapter() {
        if (exchangeAdapter == null) {
            throw new IllegalStateException("自定义协议连接尚未建立");
        }
        return exchangeAdapter;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private DeviceConnection requireConfig() {
        if (connectionConfig == null) {
            throw new IllegalStateException("自定义协议连接配置不存在");
        }
        return connectionConfig;
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveTimeout(DeviceConnection config) {
        Integer configured = config.getReadTimeout();
        if (configured != null && configured > 0) {
            return configured;
        }
        configured = config.getTimeout();
        return configured != null && configured > 0 ? configured : 5_000L;
    }
}
