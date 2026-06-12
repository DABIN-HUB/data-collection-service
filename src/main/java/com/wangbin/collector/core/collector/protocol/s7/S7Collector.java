package com.wangbin.collector.core.collector.protocol.s7;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import com.wangbin.collector.core.collector.protocol.s7.util.S7AddressParser;
import com.wangbin.collector.core.connection.adapter.S7ConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcTagResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class S7Collector extends ConnectionBackedCollector {

    private S7ConnectionAdapter connectionAdapter;
    private final Map<String, S7Address> configuredAddresses = new ConcurrentHashMap<>();
    private int timeout = 5000;
    private int maxFieldsPerRequest = 64;

    @Override
    public String getCollectorType() {
        return "SIEMENS_S7";
    }

    @Override
    public String getProtocolType() {
        return "SIEMENS_S7";
    }

    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, S7ConnectionAdapter.class, "S7");

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }

        Integer configuredTimeout = currentConfig.getReadTimeout() != null
                ? currentConfig.getReadTimeout()
                : currentConfig.getTimeout();
        this.timeout = configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : 5000;
        this.maxFieldsPerRequest = Math.max(1, currentConfig.getInt("maxFieldsPerRequest", 64));
        log.info("PLC4X S7 collector connected, deviceId={}, timeout={}, maxFieldsPerRequest={}",
                deviceInfo.getDeviceId(), timeout, maxFieldsPerRequest);
    }

    @Override
    protected void doDisconnect() {
        removeManagedConnection("S7");
        connectionAdapter = null;
        configuredAddresses.clear();
        log.info("PLC4X S7 collector disconnected, deviceId={}", deviceInfo.getDeviceId());
    }

    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        S7Address address = requireAddress(point);
        ensureScalar(address, point, "read");
        String fieldName = tagName(point);

        PlcReadResponse response = await(requireConnection().getClient()
                .readRequestBuilder()
                .addTagAddress(fieldName, address.getPlc4xAddress())
                .build()
                .execute());
        ensureResponseOk(response, fieldName, "read");
        return extractValue(response, fieldName, point, address);
    }

    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
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

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        S7Address address = requireAddress(point);
        ensureScalar(address, point, "write");
        String fieldName = tagName(point);

        PlcWriteResponse response = await(requireConnection().getClient()
                .writeRequestBuilder()
                .addTagAddress(fieldName, address.getPlc4xAddress(), coerceWriteValue(value, address, point))
                .build()
                .execute());
        ensureResponseOk(response, fieldName, "write");
        return true;
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        try {
            PlcWriteRequest.Builder builder = requireConnection().getClient().writeRequestBuilder();
            List<DataPoint> orderedPoints = new ArrayList<>();

            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                S7Address address = requireAddress(point);
                ensureScalar(address, point, "write");
                builder.addTagAddress(tagName(point), address.getPlc4xAddress(), coerceWriteValue(entry.getValue(), address, point));
                orderedPoints.add(point);
            }

            PlcWriteResponse response = await(builder.build().execute());
            for (DataPoint point : orderedPoints) {
                String fieldName = tagName(point);
                results.put(point.getPointId(), response != null && response.getResponseCode(fieldName) == PlcResponseCode.OK);
            }
            return results;
        } catch (Exception ex) {
            log.warn("PLC4X S7 batch write failed, falling back to point-by-point writes: {}", ex.getMessage());
            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                try {
                    results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
                } catch (Exception singleEx) {
                    log.error("PLC4X S7 point write failed, pointId={}", point.getPointId(), singleEx);
                    results.put(point.getPointId(), false);
                }
            }
            return results;
        }
    }

    @Override
    protected void doSubscribe(List<DataPoint> points) {
        cacheAddresses(points);
        throw unsupported("subscribe");
    }

    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            configuredAddresses.clear();
            return;
        }
        for (DataPoint point : points) {
            configuredAddresses.remove(cacheKey(point));
        }
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("protocol", getProtocolType());
        status.put("driver", "PLC4X");
        status.put("implemented", true);
        status.put("writable", true);
        status.put("subscribable", false);
        status.put("isConnected", isConnected());
        status.put("configuredPointCount", configuredAddresses.size());
        status.put("maxFieldsPerRequest", maxFieldsPerRequest);

        DeviceConnection connection = getCurrentConnectionConfig();
        if (connection != null) {
            status.put("host", connection.getHost());
            status.put("port", connection.getPort());
            status.put("rack", connection.getInt("rack", 0));
            status.put("slot", connection.getInt("slot", 1));
            status.put("controllerType", connection.getString("controllerType", "S7_1200"));
            status.put("timeout", connection.getReadTimeout() != null ? connection.getReadTimeout() : connection.getTimeout());
        }

        if (connectionAdapter != null) {
            status.put("connectionString", connectionAdapter.getConnectionString());
        }
        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
        throw unsupported("executeCommand");
    }

    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        cacheAddresses(points);
    }

    private void cacheAddresses(List<DataPoint> points) {
        configuredAddresses.clear();
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            configuredAddresses.put(cacheKey(point), S7AddressParser.parse(point));
        }
    }

    private S7Address requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(cacheKey(point), ignored -> S7AddressParser.parse(point));
    }

    private UnsupportedOperationException unsupported(String operation) {
        String message = String.format("PLC4X S7 collector does not implement %s", operation);
        log.warn(message);
        return new UnsupportedOperationException(message);
    }

    private void executeReadBatch(List<DataPoint> batch, Map<String, Object> results) {
        try {
            PlcReadResponse response = executeReadBatchRequest(batch);
            for (DataPoint point : batch) {
                if (point == null || point.getPointId() == null) {
                    continue;
                }
                String fieldName = tagName(point);
                if (response == null || response.getResponseCode(fieldName) != PlcResponseCode.OK) {
                    results.put(point.getPointId(), null);
                    continue;
                }
                results.put(point.getPointId(), extractValue(response, fieldName, point, requireAddress(point)));
            }
        } catch (Exception ex) {
            log.error("PLC4X S7 batch read failed, deviceId={}, batchSize={}", deviceInfo.getDeviceId(), batch.size(), ex);
            for (DataPoint point : batch) {
                if (point != null && point.getPointId() != null) {
                    results.put(point.getPointId(), null);
                }
            }
        }
    }

    private PlcReadResponse executeReadBatchRequest(List<DataPoint> batch) throws Exception {
        var builder = requireConnection().getClient().readRequestBuilder();
        for (DataPoint point : batch) {
            if (point == null) {
                continue;
            }
            S7Address address = requireAddress(point);
            ensureScalar(address, point, "read");
            builder.addTagAddress(tagName(point), address.getPlc4xAddress());
        }
        return await(builder.build().execute());
    }

    private Object extractValue(PlcReadResponse response, String fieldName, DataPoint point, S7Address address) {
        PlcValue plcValue = response.getPlcValue(fieldName);
        if (plcValue == null || plcValue.isNull()) {
            return null;
        }
        if (plcValue.isList()) {
            if (address.isScalar() && plcValue.getLength() == 1) {
                plcValue = plcValue.getIndex(0);
            } else {
                throw new IllegalStateException("S7 point arrays are not supported by the current collector: " + address.getRawAddress());
            }
        }

        String pointType = point != null && point.getDataType() != null
                ? point.getDataType().trim().toUpperCase()
                : address.getBasePlcType();
        return switch (pointType) {
            case "BOOLEAN", "BOOL" -> plcValue.isBoolean() ? plcValue.getBoolean() : toBoolean(plcValue.getObject());
            case "STRING", "WSTRING", "CHAR", "WCHAR" -> plcValue.isString() ? plcValue.getString() : Objects.toString(plcValue.getObject(), null);
            case "BYTE", "INT8", "SINT" -> plcValue.isByte() ? plcValue.getByte() : ((Number) coerceNumber(plcValue.getObject())).byteValue();
            case "UINT8", "USINT" -> plcValue.isInteger() ? plcValue.getInteger() : ((Number) coerceNumber(plcValue.getObject())).intValue();
            case "SHORT", "INT", "INT16", "UINT16", "UINT", "WORD" ->
                    plcValue.isInteger() ? plcValue.getInteger() : ((Number) coerceNumber(plcValue.getObject())).intValue();
            case "LONG", "INT32", "DINT" ->
                    plcValue.isInteger() ? plcValue.getInteger() : ((Number) coerceNumber(plcValue.getObject())).intValue();
            case "UINT32", "UDINT", "DWORD" ->
                    plcValue.isLong() ? plcValue.getLong() : ((Number) coerceNumber(plcValue.getObject())).longValue();
            case "INT64", "LINT" ->
                    plcValue.isLong() ? plcValue.getLong() : ((Number) coerceNumber(plcValue.getObject())).longValue();
            case "UINT64", "ULINT", "LWORD" -> plcValue.isBigInteger()
                    ? plcValue.getBigInteger()
                    : BigInteger.valueOf(((Number) coerceNumber(plcValue.getObject())).longValue());
            case "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE", "REAL" ->
                    plcValue.isFloat() ? plcValue.getFloat() : ((Number) coerceNumber(plcValue.getObject())).floatValue();
            case "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP", "LREAL" ->
                    plcValue.isDouble() ? plcValue.getDouble() : ((Number) coerceNumber(plcValue.getObject())).doubleValue();
            default -> plcValue.getObject();
        };
    }

    private Object coerceWriteValue(Object value, S7Address address, DataPoint point) {
        if (value == null) {
            return null;
        }
        String pointType = point != null && point.getDataType() != null
                ? point.getDataType().trim().toUpperCase()
                : address.getBasePlcType();
        return switch (pointType) {
            case "BOOLEAN", "BOOL" -> toBoolean(value);
            case "STRING", "WSTRING", "CHAR", "WCHAR" -> value.toString();
            case "BYTE", "INT8", "SINT" -> ((Number) coerceNumber(value)).byteValue();
            case "UINT8", "USINT", "SHORT", "INT", "INT16", "UINT16", "UINT", "WORD" ->
                    ((Number) coerceNumber(value)).intValue();
            case "LONG", "INT32", "DINT", "UINT32", "UDINT", "DWORD", "INT64", "LINT" ->
                    ((Number) coerceNumber(value)).longValue();
            case "UINT64", "ULINT", "LWORD" -> value instanceof BigInteger bigInteger
                    ? bigInteger
                    : BigInteger.valueOf(((Number) coerceNumber(value)).longValue());
            case "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE", "REAL" ->
                    ((Number) coerceNumber(value)).floatValue();
            case "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP", "LREAL" ->
                    ((Number) coerceNumber(value)).doubleValue();
            default -> value;
        };
    }

    private Number coerceNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof PlcValue plcValue) {
            return coerceNumber(plcValue.getObject());
        }
        if (value instanceof String text) {
            return text.contains(".") ? Double.parseDouble(text) : Long.parseLong(text);
        }
        throw new IllegalArgumentException("Cannot convert S7 value to number: " + value);
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private void ensureScalar(S7Address address, DataPoint point, String operation) {
        if (!address.isScalar()) {
            throw new IllegalArgumentException("S7 " + operation + " does not support array point: " + point.getPointId());
        }
    }

    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            throw new IllegalStateException("PLC4X S7 " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X S7 " + operation + " failed with response code: " + code);
        }
    }

    private <T> T await(CompletableFuture<? extends T> future) throws Exception {
        return future.get(timeout, TimeUnit.MILLISECONDS);
    }

    private S7ConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X S7 connection has not been established");
        }
        return connectionAdapter;
    }

    private String cacheKey(DataPoint point) {
        if (point.getPointId() != null && !point.getPointId().isBlank()) {
            return point.getPointId();
        }
        if (point.getAddress() != null && !point.getAddress().isBlank()) {
            return point.getAddress();
        }
        if (point.getPointCode() != null && !point.getPointCode().isBlank()) {
            return point.getPointCode();
        }
        throw new IllegalArgumentException("Point cache key cannot be resolved");
    }

    private String tagName(DataPoint point) {
        return point.getPointId() != null && !point.getPointId().isBlank()
                ? point.getPointId()
                : cacheKey(point);
    }

    @Override
    public boolean isConnected() {
        return connectionAdapter != null && connectionAdapter.isConnected();
    }
}
