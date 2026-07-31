package com.wangbin.collector.core.collector.protocol.dlt645;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.dlt645.codec.Dlt645DataCodec;
import com.wangbin.collector.core.collector.protocol.dlt645.transport.Dlt645Session;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Dlt645ConnectionAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DL/T 645-2007 电能表采集器。
 */
@Slf4j
public class Dlt645Collector extends ConnectionBackedCollector {

    private Dlt645Session session;

    @Override
    public String getCollectorType() {
        return "DLT645_2007";
    }

    @Override
    public String getProtocolType() {
        return "DLT645_2007";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection connectionConfig = requireConnectionConfig();
        try {
            ConnectionAdapter<?> adapter = createManagedConnection(connectionConfig);
            Dlt645ConnectionAdapter dlt645Adapter = requireAdapterType(
                    adapter, Dlt645ConnectionAdapter.class, "DL/T 645");
            connectManagedConnection();
            session = dlt645Adapter.getClient();
        } catch (Exception exception) {
            removeManagedConnection("DL/T 645");
            throw exception;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeManagedConnection("DL/T 645");
        session = null;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        byte[] payload = requireSession().readData(resolveIdentifier(point));
        return decodePointValue(point, payload);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        Map<String, byte[]> responseCache = new LinkedHashMap<>();
        for (DataPoint point : points) {
            String identifier = resolveIdentifier(point);
            byte[] payload = responseCache.get(identifier);
            if (payload == null) {
                payload = requireSession().readData(identifier);
                responseCache.put(identifier, payload);
            }
            results.put(point.getPointId(), decodePointValue(point, payload));
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        DeviceConnection connectionConfig = requireConnectionConfig();
        if (!connectionConfig.getBoolConfig("writeEnabled", false)) {
            throw new Dlt645ProtocolException("DL/T 645 写入能力未启用");
        }
        String passwordHex = connectionConfig.getStringConfig("writePasswordHex", null);
        String operatorHex = connectionConfig.getStringConfig("operatorCodeHex", null);
        byte[] password = Dlt645DataCodec.parseHex(passwordHex);
        byte[] operatorCode = Dlt645DataCodec.parseHex(operatorHex);
        String valueType = pointConfig(point, "valueType", "BCD");
        String dataFormat = pointConfig(point, "dataFormat", null);
        byte[] encoded = Dlt645DataCodec.encodeValue(value, valueType, dataFormat);
        return requireSession().writeData(resolveIdentifier(point), password, operatorCode, encoded);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            try {
                results.put(entry.getKey().getPointId(), doWritePoint(entry.getKey(), entry.getValue()));
            } catch (Exception exception) {
                log.error("DL/T 645 点位写入失败，点位={}", entry.getKey().getPointId(), exception);
                results.put(entry.getKey().getPointId(), false);
            }
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSubscribe(List<DataPoint> points) {
        throw new UnsupportedOperationException("DL/T 645 不支持原生订阅");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        subscribedPointMap.clear();
        subscribedPointsSet.clear();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("protocol", "DL/T 645-2007");
        status.put("connected", session != null && session.isOpen());
        status.put("meterAddress", session != null ? session.meterAddress() : null);
        status.put("lastActivityTime", lastActivityTime);
        status.put("totalReadCount", totalReadCount.get());
        status.put("totalWriteCount", totalWriteCount.get());
        status.put("totalErrorCount", totalErrorCount.get());
        status.put("lastError", lastError);
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        if ("read_address".equals(normalized)) {
            return requireSession().readAddress().value();
        }
        throw new UnsupportedOperationException("不支持的 DL/T 645 命令: " + command);
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        // DL/T 645 每个 DI 独立请求，同一批次内由采集器按 DI 复用响应。
    }

    /**
     * 解析或转换业务数据。
     */
    private Object decodePointValue(DataPoint point, byte[] payload) throws Dlt645ProtocolException {
        String valueType = pointConfig(point, "valueType", "BCD");
        String dataFormat = pointConfig(point, "dataFormat", null);
        int valueIndex = pointIntConfig(point, "valueIndex", 0);
        return Dlt645DataCodec.decodeValue(payload, valueType, dataFormat, valueIndex);
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveIdentifier(DataPoint point) {
        return Dlt645DataCodec.normalizeDataIdentifier(point.getAddress());
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Dlt645Session requireSession() {
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("DL/T 645 会话尚未连接");
        }
        return session;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String pointConfig(DataPoint point, String key, String defaultValue) {
        Object value = pointConfigMap(point).get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    /**
     * 执行当前业务逻辑。
     */
    private int pointIntConfig(DataPoint point, String key, int defaultValue) {
        Object value = pointConfigMap(point).get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> pointConfigMap(DataPoint point) {
        return point.getAdditionalConfig() == null ? Collections.emptyMap() : point.getAdditionalConfig();
    }
}
