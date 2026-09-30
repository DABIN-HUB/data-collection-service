package com.wangbin.collector.core.collector.protocol.ethernetip;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.ethernetip.EtherNetIpConnectionContract;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpPlcType;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;
import com.wangbin.collector.core.collector.protocol.ethernetip.util.EtherNetIpAddressParser;
import com.wangbin.collector.core.collector.protocol.ethernetip.util.EtherNetIpPlcTypeResolver;
import com.wangbin.collector.core.collector.protocol.ethernetip.util.ProgramTagCipClient;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.EtherNetIpConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.exceptions.PlcIoException;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcTagResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class EtherNetIpCollector extends ConnectionBackedCollector {
    private DevicePointResolver devicePointResolver;

    /**
     * 注入点位解析辅助组件。
     */
    @Autowired(required = false)
    public void setDevicePointResolver(DevicePointResolver devicePointResolver) {
        this.devicePointResolver = devicePointResolver;
    }

    private EtherNetIpConnectionAdapter connectionAdapter;
    private final Map<String, EtherNetIpTagAddress> configuredAddresses = new ConcurrentHashMap<>();
    private int timeout = 30000;
    private int maxFieldsPerRequest = 64;

    @Override
    public String getCollectorType() {
        return "ETHERNET_IP";
    }

    @Override
    public String getProtocolType() {
        return "ETHERNET_IP";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, EtherNetIpConnectionAdapter.class, "EtherNet/IP");

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }

        Integer readTimeout = currentConfig.getReadTimeout();
        Integer configuredTimeout = readTimeout != null && readTimeout > 0
                ? readTimeout : currentConfig.getTimeout();
        this.timeout = configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : 30000;
        this.maxFieldsPerRequest = EtherNetIpConnectionContract.integer(
                currentConfig, "maxFieldsPerRequest", 64, 1, Integer.MAX_VALUE);
        log.info("PLC4X EtherNet/IP 采集器 已连接, 设备={}, 超时={}, 单次最大字段数={}",
                deviceInfo.getDeviceId(), timeout, maxFieldsPerRequest);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeManagedConnection("EtherNet/IP");
        connectionAdapter = null;
        configuredAddresses.clear();
        log.info("PLC4X EtherNet/IP 采集器 已断开, 设备={}", deviceInfo.getDeviceId());
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        if (!isArrayPoint(point)) {
            return super.readPoint(point);
        }
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            EtherNetIpTagAddress address = requireAddress(point);
            validateArrayPointConfiguration(point, address, "read");

            Object rawValue = doReadPoint(point);
            ProcessResult processResult = buildArrayProcessResult(point, address, rawValue, "array pass-through read");
            lastProcessResults.put(point.getPointId(), processResult);

            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return processResult.getFinalValue();
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            log.error("点位 读取 失败 {}.{}", deviceInfo.getDeviceId(), point.getPointName(), e);
            recordException(e, point);
            throw new CollectorException("点位读取失败", deviceInfo.getDeviceId(),
                    point.getPointId(), e);
        }
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException {
        if (!containsArrayPoint(points)) {
            return super.readPoints(points);
        }
        checkConnection();

        Map<String, Object> results = new LinkedHashMap<>();
        List<DataPoint> scalarPoints = new ArrayList<>();
        List<DataPoint> arrayPoints = new ArrayList<>();
        partitionPoints(points, scalarPoints, arrayPoints);

        if (!scalarPoints.isEmpty()) {
            results.putAll(super.readPoints(scalarPoints));
        }
        if (arrayPoints.isEmpty()) {
            return results;
        }

        long arrayStartTime = System.currentTimeMillis();
        try {
            Map<String, Object> rawValues = doReadPoints(arrayPoints);
            for (DataPoint point : arrayPoints) {
                String pointId = point.getPointId();
                Object rawValue = rawValues.get(pointId);
                if (rawValue == null) {
                    results.put(pointId, null);
                    continue;
                }
                try {
                    EtherNetIpTagAddress address = requireAddress(point);
                    ProcessResult processResult = buildArrayProcessResult(point, address, rawValue,
                            "array pass-through batch read");
                    lastProcessResults.put(pointId, processResult);
                    results.put(pointId, processResult.getFinalValue());
                } catch (Exception e) {
                    log.error("EtherNet/IP 数组点位处理失败: 设备={}, 点位名称={}", deviceInfo.getDeviceId(), point.getPointName(), e);
                    recordException(e, point);
                    results.put(pointId, null);
                }
            }

            totalReadCount.addAndGet(arrayPoints.size());
            totalReadTime.addAndGet(System.currentTimeMillis() - arrayStartTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            log.error("EtherNet/IP 数组点位批量读取失败: 设备={}", deviceInfo.getDeviceId(), e);
            recordException(e, null);
            throw new CollectorException("批量读取失败", deviceInfo.getDeviceId(),
                    null, e);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public boolean writePoint(DataPoint point, Object value) throws CollectorException {
        if (!isArrayPoint(point)) {
            return super.writePoint(point, value);
        }
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                throw new CollectorException("点位不可写", deviceInfo.getDeviceId(), point.getPointId());
            }

            EtherNetIpTagAddress address = requireAddress(point);
            validateArrayPointConfiguration(point, address, "write");
            boolean result = doWritePoint(point, value);

            totalWriteCount.incrementAndGet();
            totalWriteTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return result;
        } catch (CollectorException e) {
            throw e;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            log.error("EtherNet/IP 数组点位写入失败: 设备={}, 点位名称={}", deviceInfo.getDeviceId(), point.getPointName(), e);
            recordException(e, point);
            throw new CollectorException("点位写入失败", deviceInfo.getDeviceId(),
                    point.getPointId(), e);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public Map<String, Boolean> writePoints(Map<DataPoint, Object> points) throws CollectorException {
        if (!containsArrayPoint(points != null ? points.keySet() : null)) {
            return super.writePoints(points);
        }
        checkConnection();

        Map<String, Boolean> results = new LinkedHashMap<>();
        Map<DataPoint, Object> scalarPoints = new LinkedHashMap<>();
        Map<DataPoint, Object> arrayPoints = new LinkedHashMap<>();
        partitionPointValues(points, scalarPoints, arrayPoints);

        if (!scalarPoints.isEmpty()) {
            results.putAll(super.writePoints(scalarPoints));
        }
        if (arrayPoints.isEmpty()) {
            return results;
        }

        long arrayStartTime = System.currentTimeMillis();
        try {
            for (Map.Entry<DataPoint, Object> entry : arrayPoints.entrySet()) {
                DataPoint point = entry.getKey();
                if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                    results.put(point.getPointId(), false);
                    continue;
                }
                try {
                    EtherNetIpTagAddress address = requireAddress(point);
                    validateArrayPointConfiguration(point, address, "write");
                    results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
                } catch (FatalTransportException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("EtherNet/IP 数组点位批量写入失败: 点位={}", point.getPointId(), e);
                    recordException(e, point);
                    results.put(point.getPointId(), false);
                }
            }

            totalWriteCount.addAndGet(arrayPoints.size());
            totalWriteTime.addAndGet(System.currentTimeMillis() - arrayStartTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            log.error("EtherNet/IP 数组点位批量写入失败: 设备={}", deviceInfo.getDeviceId(), e);
            recordException(e, null);
            throw new CollectorException("批量写入失败", deviceInfo.getDeviceId(),
                    null, e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        EtherNetIpTagAddress address = requireAddress(point);
        if (address.getTagName().startsWith("Program:")) {
            return programClient().read(address.getTagName());
        }
        String fieldName = resolvePointTagName(point);
        PlcReadResponse response;
        try {
            response = await(requireConnection().getClient()
                    .readRequestBuilder()
                    .addTagAddress(fieldName, address.getPlc4xAddress())
                    .build()
                    .execute());
        } catch (Exception ex) {
            invalidateIfTransportFailure(ex);
            throw ex;
        }
        ensureResponseOk(response, fieldName, "read");
        return extractValue(response, fieldName, point, address);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        List<DataPoint> batch = new ArrayList<>(Math.min(points.size(), maxFieldsPerRequest));
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            batch.add(point);
            if (batch.size() >= maxFieldsPerRequest) {
                executeReadBatch(batch, results);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            executeReadBatch(batch, results);
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        EtherNetIpTagAddress address = requireAddress(point);
        if (address.getTagName().startsWith("Program:")) {
            throw unsupported("Program scoped tag write", "Program 路径写入未实现，禁止改写其他标签");
        }
        String fieldName = resolvePointTagName(point);

        PlcWriteResponse response;
        try {
            response = await(requireConnection().getClient()
                    .writeRequestBuilder()
                    .addTagAddress(fieldName, address.getPlc4xAddress(), coerceWriteValue(value, address, point))
                    .build()
                    .execute());
        } catch (Exception ex) {
            invalidateIfTransportFailure(ex);
            throw ex;
        }
        ensureResponseOk(response, fieldName, "write");
        return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) throws Exception {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        PlcWriteRequest.Builder builder = null;
        List<DataPoint> orderedPoints = new ArrayList<>();
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null || point.getPointId() == null) {
                continue;
            }
            try {
                EtherNetIpTagAddress address = requireAddress(point);
                if (address.getTagName().startsWith("Program:")) {
                    results.put(point.getPointId(), false);
                    continue;
                }
                if (!address.isScalar()) {
                    validateArrayPointConfiguration(point, address, "write");
                }
                Object writeValue = coerceWriteValue(entry.getValue(), address, point);
                if (builder == null) {
                    builder = requireConnection().getClient().writeRequestBuilder();
                }
                builder.addTagAddress(resolvePointTagName(point), address.getPlc4xAddress(),
                        writeValue);
                orderedPoints.add(point);
            } catch (IllegalArgumentException ex) {
                log.warn("EtherNet/IP 跳过无效写入点位: 点位={}", point.getPointId(), ex);
                results.put(point.getPointId(), false);
            }
        }
        if (orderedPoints.isEmpty()) {
            return results;
        }
        // 已提交的批量写入可能已在设备侧生效；Future 失败时绝不逐点重发。
        PlcWriteResponse response;
        try {
            response = await(builder.build().execute());
        } catch (Exception ex) {
            invalidateIfTransportFailure(ex);
            // 执行结果不明，不能把未确认的写入标记为成功或再次下发。
            log.warn("EtherNet/IP 批量写入结果未确认: 设备={}, 字段数={}, 异常类型={}",
                    deviceInfo.getDeviceId(), orderedPoints.size(), ex.getClass().getSimpleName());
            lastError = "UNCONFIRMED: EtherNet/IP batch write result unknown";
            recordException(new IllegalStateException(lastError), null);
            for (DataPoint point : orderedPoints) {
                results.put(point.getPointId(), false);
            }
            return results;
        }
        if (response == null) {
            invalidateTransport(connectionAdapter);
            log.warn("EtherNet/IP 批量写入结果未确认: 设备={}, 响应为空", deviceInfo.getDeviceId());
            lastError = "UNCONFIRMED: EtherNet/IP batch write response missing";
            for (DataPoint point : orderedPoints) {
                results.put(point.getPointId(), false);
            }
            return results;
        }
        for (DataPoint point : orderedPoints) {
            try {
                String fieldName = resolvePointTagName(point);
                results.put(point.getPointId(), response.getResponseCode(fieldName) == PlcResponseCode.OK);
            } catch (Exception ex) {
                log.warn("EtherNet/IP 写入响应字段无效: 点位={}", point.getPointId());
                results.put(point.getPointId(), false);
            }
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSubscribe(List<DataPoint> points) {
        cacheAddresses(points);
        throw unsupported("subscribe", "PLC4X Logix driver metadata reports subscribe unsupported for the current connection");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            configuredAddresses.clear();
            return;
        }
        for (DataPoint point : points) {
            configuredAddresses.remove(resolvePointCacheKey(point));
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.PROTOCOL, getProtocolType());
        status.put(CommonMapKeys.DRIVER, "PLC4X");
        status.put("implemented", true);
        status.put(CommonMapKeys.WRITABLE, true);
        status.put(CommonMapKeys.SUBSCRIBABLE, false);
        status.put(CommonMapKeys.IS_CONNECTED, isConnected());
        status.put(CommonMapKeys.CONFIGURED_POINT_COUNT, configuredAddresses.size());
        status.put("maxFieldsPerRequest", maxFieldsPerRequest);

        DeviceConnection connection = getCurrentConnectionConfig();
        if (connection != null) {
            boolean explicit = connection.getString("plc4xConnectionString", null) != null
                    && !connection.getString("plc4xConnectionString", null).isBlank();
            status.put("connectionSource", explicit ? "EXPLICIT" : "AUTO");
            if (!explicit) {
                status.put(CommonMapKeys.HOST, connection.getHost());
                status.put(CommonMapKeys.PORT, connection.getPort() == null ? 44818 : connection.getPort());
                status.put("communicationPath", connection.getString("communicationPath", null));
                status.put("backplane", connection.getInt("backplane", 1));
                status.put("slot", connection.getInt("slot", 0));
            }
            status.put(CommonMapKeys.TIMEOUT, timeout);
        }

        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        String normalized = normalizeCommand(command);
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        return switch (normalized) {
            case "read", "read_point", "readpoint" -> executeCommandRead(safeParams);
            case "write", "write_point", "writepoint" -> executeCommandWrite(safeParams);
            case "status", "diagnostic" -> getDeviceStatus();
            default -> throw new IllegalArgumentException("Unsupported PLC4X EtherNet/IP command: " + command);
        };
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        cacheAddresses(points);
    }

    @Override
    public void rebuildReadPlans(String deviceId, List<DataPoint> points) {
        // 基类只记录异常；全部点位无效时必须向启动调用方明确报错。
        buildReadPlans(deviceId, points);
    }

    /**
     * 执行当前业务逻辑。
     */
    private void cacheAddresses(List<DataPoint> points) {
        configuredAddresses.clear();
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            if (!point.isEnabled()) {
                continue;
            }
            try {
                configuredAddresses.put(resolvePointCacheKey(point), EtherNetIpAddressParser.parse(point));
            } catch (IllegalArgumentException ex) {
                log.warn("EtherNet/IP 跳过无效配置点位: 点位={}", point.getPointId(), ex);
            }
        }
        if (points.stream().anyMatch(point -> point != null && point.isEnabled()) && configuredAddresses.isEmpty()) {
            throw new IllegalStateException("EtherNet/IP 没有有效的已启用点位");
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private EtherNetIpTagAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(resolvePointCacheKey(point), ignored -> EtherNetIpAddressParser.parse(point));
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupported(String operation) {
        return unsupported(operation, null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupported(String operation, String reason) {
        String message = String.format("PLC4X EtherNet/IP collector does not implement %s", operation);
        if (reason != null && !reason.isBlank()) {
            message = message + ": " + reason;
        }
        log.warn(message);
        return new UnsupportedOperationException(message);
    }

    /**
     * 处理当前业务流程。
     */
    private void executeReadBatch(List<DataPoint> batch, Map<String, Object> results) throws Exception {
        List<DataPoint> valid = new ArrayList<>();
        var builder = requireConnection().getClient().readRequestBuilder();
        for (DataPoint point : batch) {
            if (point == null || point.getPointId() == null) {
                continue;
            }
            try {
                EtherNetIpTagAddress address = requireAddress(point);
                if (address.getTagName().startsWith("Program:")) {
                    try {
                        results.put(point.getPointId(), programClient().read(address.getTagName()));
                    } catch (Exception ex) {
                        log.warn("EtherNet/IP Program 点位读取失败: 点位={}", point.getPointId(), ex);
                        reportPointFailure(point, "COMM_ERROR");
                        results.put(point.getPointId(), null);
                    }
                    continue;
                }
                if (!address.isScalar()) {
                    validateArrayPointConfiguration(point, address, "read");
                }
                builder.addTagAddress(resolvePointTagName(point), address.getPlc4xAddress());
                valid.add(point);
            } catch (IllegalArgumentException ex) {
                log.warn("EtherNet/IP 跳过无效读取点位: 点位={}", point.getPointId(), ex);
                results.put(point.getPointId(), null);
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        PlcReadResponse response;
        try {
            response = await(builder.build().execute());
        } catch (Exception ex) {
            invalidateIfTransportFailure(ex);
            throw ex;
        }
        if (response == null) {
            invalidateTransport(connectionAdapter);
            throw new FatalTransportException("EtherNet/IP 批量读取响应为空");
        }
        for (DataPoint point : valid) {
            try {
                String fieldName = resolvePointTagName(point);
                if (response.getResponseCode(fieldName) != PlcResponseCode.OK) {
                    results.put(point.getPointId(), null);
                    continue;
                }
                results.put(point.getPointId(), extractValue(response, fieldName, point, requireAddress(point)));
            } catch (Exception ex) {
                log.warn("EtherNet/IP 点位读取响应无效: 点位={}", point.getPointId(), ex);
                results.put(point.getPointId(), null);
            }
        }
    }

    /**
     * 处理当前业务流程。
     */

    /**
     * 解析或转换业务数据。
     */
    private Object extractValue(PlcReadResponse response, String fieldName, DataPoint point, EtherNetIpTagAddress address) {
        PlcValue plcValue = response.getPlcValue(fieldName);
        if (plcValue == null || plcValue.isNull()) {
            return null;
        }
        if (plcValue.isList()) {
            if (!address.isScalar() && plcValue.getLength() != address.getArraySize()) {
                throw new IllegalStateException("EtherNet/IP array read size mismatch, expected "
                        + address.getArraySize() + " but got " + plcValue.getLength());
            }
            if (address.isScalar()) {
                if (plcValue.getLength() != 1) {
                    throw new IllegalStateException("EtherNet/IP scalar point returned "
                            + plcValue.getLength() + " elements");
                }
                plcValue = plcValue.getIndex(0);
            } else {
                return extractArrayValue(plcValue, point, address);
            }
        }

        if (!address.isScalar()) {
            throw new IllegalStateException("EtherNet/IP array point did not return list payload: " + address.getRawAddress());
        }
        return coerceScalarValue(plcValue, resolvePointType(point, address));
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceScalarValue(PlcValue plcValue, EtherNetIpPlcType plcType) {
        if (plcValue == null) {
            return null;
        }
        return plcType != null ? plcType.read(plcValue) : plcValue.getObject();
    }

    /**
     * 解析或转换业务数据。
     */
    private List<Object> extractArrayValue(PlcValue plcValue, DataPoint point, EtherNetIpTagAddress address) {
        List<Object> values = new ArrayList<>();
        EtherNetIpPlcType plcType = resolvePointType(point, address);
        int length = plcValue.getLength();
        for (int i = 0; i < length; i++) {
            values.add(coerceScalarValue(plcValue.getIndex(i), plcType));
        }
        return values;
    }

    /**
     * 解析或转换业务数据。
     */
    private EtherNetIpPlcType resolvePointType(DataPoint point, EtherNetIpTagAddress address) {
        return EtherNetIpPlcTypeResolver.INSTANCE.resolveOrNull(point, address);
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceWriteValue(Object value, EtherNetIpTagAddress address, DataPoint point) {
        if (!address.isScalar()) {
            return coerceWriteArrayValue(value, address, point);
        }
        return coerceWriteScalarValue(value, address, point);
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceWriteScalarValue(Object value, EtherNetIpTagAddress address, DataPoint point) {
        if (value == null) {
            return null;
        }
        EtherNetIpPlcType plcType = resolvePointType(point, address);
        return plcType != null ? plcType.write(value) : value;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<Object> coerceWriteArrayValue(Object value, EtherNetIpTagAddress address, DataPoint point) {
        List<Object> sourceValues = toObjectList(value);
        if (sourceValues.isEmpty()) {
            throw new IllegalArgumentException("EtherNet/IP array write value cannot be empty");
        }
        if (sourceValues.size() != address.getArraySize()) {
            throw new IllegalArgumentException("EtherNet/IP array write size mismatch, expected "
                    + address.getArraySize() + " but got " + sourceValues.size());
        }

        List<Object> coerced = new ArrayList<>(sourceValues.size());
        for (Object sourceValue : sourceValues) {
            coerced.add(coerceWriteScalarValue(sourceValue, address, point));
        }
        return coerced;
    }

    /**
     * 解析或转换业务数据。
     */
    private List<Object> toObjectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
            return values;
        }
        throw new IllegalArgumentException("EtherNet/IP array write requires collection or array value");
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            invalidateTransport(connectionAdapter);
            throw new IllegalStateException("PLC4X EtherNet/IP " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X EtherNet/IP " + operation + " failed with response code: " + code);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> T await(CompletableFuture<? extends T> future) throws Exception {
        EtherNetIpConnectionAdapter session = connectionAdapter;
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // Future 取消不保证设备端未执行；仅会话本身失效时才撤销连接。
            future.cancel(false);
            if (session != null && !session.isConnected()) {
                invalidateTransport(session);
            }
            throw ex;
        } catch (InterruptedException ex) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            if (isTransportFailure(ex.getCause(), session)) {
                invalidateTransport(session);
                throw new FatalTransportException("EtherNet/IP 传输连接失效");
            }
            throw new IllegalStateException("EtherNet/IP 请求执行失败");
        }
    }

    private boolean isTransportFailure(Throwable failure, EtherNetIpConnectionAdapter session) {
        if (session != null && !session.isConnected()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PlcConnectionException || cause instanceof PlcIoException || cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private void invalidateIfTransportFailure(Throwable failure) {
        EtherNetIpConnectionAdapter session = connectionAdapter;
        if (isTransportFailure(failure, session)) {
            invalidateTransport(session);
        }
    }

    private void invalidateTransport(EtherNetIpConnectionAdapter session) {
        synchronized (this) {
            if (session == null || connectionAdapter != session) {
                return;
            }
            connectionAdapter = null;
            connected = false;
            connectionStatus = "ERROR";
            if (connectionManager == null || connectionManager.getConnection(deviceInfo.getDeviceId()) == session) {
                removeManagedConnection("EtherNet/IP");
            }
        }
    }

    private static final class FatalTransportException extends IllegalStateException {
        private FatalTransportException(String message) {
            super(message);
        }

        private FatalTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private ProgramTagCipClient programClient() {
        DeviceConnection config = getCurrentConnectionConfig();
        if (config == null || config.getHost() == null || config.getHost().isBlank()) {
            throw new IllegalStateException("EtherNet/IP Program 点位需要明确的连接 host 和 port");
        }
        return new ProgramTagCipClient(config.getHost(),
                config.getPort() == null ? 44818 : config.getPort(), timeout);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private EtherNetIpConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X EtherNet/IP connection has not been established");
        }
        return connectionAdapter;
    }


    /**
     * 处理当前业务流程。
     */
    private Object executeCommandRead(Map<String, Object> params) throws Exception {
        DataPoint point = resolveCommandPoint(params);
        Object value = readPoint(point);
        Map<String, Object> result = new LinkedHashMap<>();
        populatePointMetadata(result, point);
        result.put(CommonMapKeys.VALUE, value);
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeCommandWrite(Map<String, Object> params) throws Exception {
        DataPoint point = resolveCommandPoint(params);
        if (!params.containsKey(CommonMapKeys.VALUE)) {
            throw new IllegalArgumentException("value is required");
        }
        Object value = params.get(CommonMapKeys.VALUE);
        boolean success = writePoint(point, value);
        Map<String, Object> result = new LinkedHashMap<>();
        populatePointMetadata(result, point);
        result.put(CommonMapKeys.VALUE, value);
        result.put(CommonMapKeys.SUCCESS, success);
        return result;
    }

    /**
     * 解析或转换业务数据。
     */
    private DataPoint resolveCommandPoint(Map<String, Object> params) {
        List<DataPoint> points = configManager != null && deviceInfo != null
                ? configManager.getDataPoints(deviceInfo.getDeviceId())
                : Collections.emptyList();
        if (points.isEmpty()) {
            throw new IllegalArgumentException("No configured EtherNet/IP points found for device: "
                    + (deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN"));
        }

        String pointRef = firstNonBlank(
                asText(params.get("pointRef")),
                asText(params.get(CommonMapKeys.POINT_ID)),
                asText(params.get(CommonMapKeys.POINT_CODE)),
                asText(params.get(CommonMapKeys.POINT_NAME)),
                asText(params.get(CommonMapKeys.FIELD)),
                asText(params.get("reportField"))
        );
        if (hasText(pointRef)) {
            DataPoint point = resolveConfiguredPoint(points, pointRef);
            if (point != null) {
                return point;
            }
        }

        String address = asText(params.get(CommonMapKeys.ADDRESS));
        if (hasText(address)) {
            DataPoint point = points.stream()
                    .filter(candidate -> candidate != null && candidate.isEnabled() && hasText(candidate.getAddress())
                            && normalize(candidate.getAddress()).equals(normalize(address)))
                    .findFirst()
                    .orElse(null);
            if (point != null) {
                return point;
            }
        }

        throw new IllegalArgumentException("Unable to resolve EtherNet/IP point from command params");
    }

    /**
     * 解析或转换业务数据。
     */
    private DataPoint resolveConfiguredPoint(List<DataPoint> points, String pointRef) {
        if (devicePointResolver != null) {
            return devicePointResolver.resolve(points, pointRef)
                    .filter(DataPoint::isEnabled)
                    .orElse(null);
        }
        String normalizedRef = normalize(pointRef);
        return points.stream()
                .filter(point -> point != null && point.isEnabled() && matchesPointRef(point, normalizedRef))
                .findFirst()
                .orElse(null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean matchesPointRef(DataPoint point, String normalizedRef) {
        return point != null
                && (normalizedRef.equals(normalize(point.getReportField()))
                || normalizedRef.equals(normalize(point.getPointAlias()))
                || normalizedRef.equals(normalize(point.getPointCode()))
                || normalizedRef.equals(normalize(point.getPointId()))
                || normalizedRef.equals(normalize(point.getPointName())));
    }

    /**
     * 执行当前业务逻辑。
     */
    private void populatePointMetadata(Map<String, Object> target, DataPoint point) {
        target.put(CommonMapKeys.POINT_ID, point.getPointId());
        target.put(CommonMapKeys.POINT_CODE, point.getPointCode());
        target.put(CommonMapKeys.POINT_NAME, point.getPointName());
        if (point.getAddress() != null) {
            target.put(CommonMapKeys.ADDRESS, point.getAddress());
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeCommand(String command) {
        return command != null ? command.trim().toLowerCase(Locale.ROOT).replace('-', '_') : "";
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 执行当前业务逻辑。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String asText(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isArrayPoint(DataPoint point) {
        if (point == null) {
            return false;
        }
        try {
            return !requireAddress(point).isScalar();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean containsArrayPoint(Iterable<DataPoint> points) {
        if (points == null) {
            return false;
        }
        for (DataPoint point : points) {
            if (point != null && point.isEnabled() && isArrayPoint(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行当前业务逻辑。
     */
    private void partitionPoints(List<DataPoint> points, List<DataPoint> scalarPoints, List<DataPoint> arrayPoints) {
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null || !point.isEnabled()) {
                continue;
            }
            if (isArrayPoint(point)) {
                arrayPoints.add(point);
            } else {
                scalarPoints.add(point);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void partitionPointValues(Map<DataPoint, Object> points,
                                      Map<DataPoint, Object> scalarPoints,
                                      Map<DataPoint, Object> arrayPoints) {
        if (points == null) {
            return;
        }
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null) {
                continue;
            }
            if (isArrayPoint(point)) {
                arrayPoints.put(point, entry.getValue());
            } else {
                scalarPoints.put(point, entry.getValue());
            }
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateArrayPointConfiguration(DataPoint point,
                                                 EtherNetIpTagAddress address,
                                                 String operation) {
        if (point == null || address == null || address.isScalar()) {
            return;
        }
        if (point.getScalingFactor() != null && point.getScalingFactor() != 0 && point.getScalingFactor() != 1.0d) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support scalingFactor: "
                    + point.getPointId());
        }
        if (point.getOffset() != null && point.getOffset() != 0.0d) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support offset: "
                    + point.getPointId());
        }
        if (point.getPrecision() != null) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support precision: "
                    + point.getPointId());
        }
        if (point.getMinValue() != null || point.getMaxValue() != null) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support min/max validation: "
                    + point.getPointId());
        }
        if (point.getAlarmEnabled() != null && point.getAlarmEnabled() == 1) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support alarm processing: "
                    + point.getPointId());
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildArrayProcessResult(DataPoint point,
                                                  EtherNetIpTagAddress address,
                                                  Object rawValue,
                                                  String message) {
        if (!(rawValue instanceof Collection<?>) && !(rawValue != null && rawValue.getClass().isArray())) {
            throw new IllegalArgumentException("EtherNet/IP array point did not produce collection payload: " + point.getPointId());
        }
        ProcessResult processResult = ProcessResult.success(rawValue, rawValue, message);
        processResult.addMetadata("arrayValue", true);
        processResult.addMetadata("arraySize", address.getArraySize());
        processResult.addMetadata("processingMode", "protocol_passthrough");
        if (point != null && point.getAddress() != null) {
            processResult.addMetadata(CommonMapKeys.ADDRESS, point.getAddress());
        }
        return processResult;
    }

    @Override
    public boolean isConnected() {
        return connectionAdapter != null && connectionAdapter.isConnected();
    }
}
