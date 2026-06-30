package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetAddress;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPointPlan;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultiplePlan;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultiplePlanBuilder;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResultIndex;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;
import com.wangbin.collector.core.collector.protocol.bacnet.util.BacnetAddressParser;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessContext;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * BACnet/IP collector: first delivery only implements UDP polling ReadProperty.
 */
@Slf4j
public class BacnetIpCollector extends ConnectionBackedCollector {

    private final Map<String, BacnetAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final AtomicInteger invokeIdSequence = new AtomicInteger(1);

    private BacnetIpConnectionAdapter connectionAdapter;
    private volatile int requestTimeoutMs = 5000;

    @Override
    public String getCollectorType() {
        return "BACNET_IP";
    }

    @Override
    public String getProtocolType() {
        return "BACNET_IP";
    }

    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, BacnetIpConnectionAdapter.class, "BACnet/IP");
        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }
        Integer timeout = currentConfig.getReadTimeout() != null
                ? currentConfig.getReadTimeout()
                : currentConfig.getTimeout();
        requestTimeoutMs = timeout != null && timeout > 0 ? timeout : 5000;
        configuredAddresses.clear();
        log.info("BACnet/IP collector connected, deviceId={}, timeoutMs={}", deviceInfo.getDeviceId(), requestTimeoutMs);
    }

    @Override
    protected void doDisconnect() {
        removeManagedConnection("BACnet/IP");
        connectionAdapter = null;
        configuredAddresses.clear();
        log.info("BACnet/IP collector disconnected, deviceId={}", deviceInfo.getDeviceId());
    }

    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            BacnetAddress address = requireAddress(point);
            Object rawValue = doReadPoint(point);
            ProcessResult processResult = buildScalarProcessResult(point, address, rawValue);
            lastProcessResults.put(point.getPointId(), processResult);
            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return processResult.getFinalValue();
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            throw new CollectorException("BACnet/IP point read failed",
                    deviceInfo.getDeviceId(),
                    point != null ? point.getPointId() : null,
                    e);
        }
    }

    @Override
    public Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        Map<String, Object> results = new LinkedHashMap<>();
        try {
            List<DataPoint> validPoints = points == null
                    ? Collections.emptyList()
                    : points.stream().filter(point -> point != null && point.isEnabled()).collect(Collectors.toList());
            if (validPoints.isEmpty()) {
                return results;
            }

            Map<String, Object> rawValues = doReadPoints(validPoints);
            for (DataPoint point : validPoints) {
                String pointId = point.getPointId();
                if (pointId == null) {
                    continue;
                }
                try {
                    BacnetAddress address = requireAddress(point);
                    Object rawValue = rawValues.get(pointId);
                    if (rawValue == null) {
                        results.put(pointId, null);
                        continue;
                    }
                    ProcessResult processResult = buildScalarProcessResult(point, address, rawValue);
                    lastProcessResults.put(pointId, processResult);
                    results.put(pointId, processResult.getFinalValue());
                } catch (Exception ex) {
                    log.error("BACnet/IP batch point process failed, deviceId={}, pointId={}",
                            deviceInfo.getDeviceId(), pointId, ex);
                    recordException(ex, point);
                    results.put(pointId, null);
                }
            }

            totalReadCount.addAndGet(validPoints.size());
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, null);
            throw new CollectorException("BACnet/IP batch point read failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        BacnetAddress address = requireAddress(point);
        BacnetReadPropertyRequest request = BacnetReadPropertyRequest.builder()
                .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                .objectInstance(address.getInstanceNumber())
                .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                .arrayIndex(address.getArrayIndex())
                .invokeId(nextInvokeId())
                .remoteDeviceInstance(requireRemoteDeviceInstance())
                .build();
        BacnetReadPropertyResponse response = exchange(request);
        return response.getValue();
    }

    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return values;
        }
        List<BacnetReadPointPlan> pointPlans = points.stream()
                .filter(point -> point != null && point.getPointId() != null)
                .map(point -> BacnetReadPointPlan.builder()
                        .point(point)
                        .address(requireAddress(point))
                        .build())
                .collect(Collectors.toList());
        if (pointPlans.isEmpty()) {
            return values;
        }

        if (!isReadPropertyMultipleEnabled() || pointPlans.size() <= 1) {
            readSequentially(pointPlans, values);
            return values;
        }

        List<BacnetReadPropertyMultiplePlan> plans = BacnetReadPropertyMultiplePlanBuilder.build(
                pointPlans,
                requireConnectionConfig().getIntConfig("maxPropertiesPerRequest", 16));

        for (BacnetReadPropertyMultiplePlan plan : plans) {
            try {
                readPlanByReadPropertyMultiple(plan, values);
            } catch (Exception ex) {
                log.warn("BACnet/IP ReadPropertyMultiple failed, fallback to ReadProperty, deviceId={}, planPoints={}, error={}",
                        deviceInfo.getDeviceId(),
                        plan.getPointPlans() != null ? plan.getPointPlans().size() : 0,
                        ex.getMessage());
                for (BacnetReadPointPlan pointPlan : plan.getPointPlans()) {
                    values.put(pointPlan.getPoint().getPointId(), doReadPoint(pointPlan.getPoint()));
                }
            }
        }
        return values;
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) {
        throw unsupported("writePoint", "first delivery only implements ReadProperty");
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        throw unsupported("writePoints", "first delivery only implements ReadProperty");
    }

    @Override
    protected void doSubscribe(List<DataPoint> points) {
        throw unsupported("subscribe", "first delivery only implements polling");
    }

    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        configuredAddresses.clear();
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("protocol", "BACNET_IP");
        status.put("driver", "SELF_IMPLEMENTED_MINIMAL");
        status.put("implemented", true);
        status.put("transport", "UDP");
        status.put("readPropertyImplemented", true);
        status.put("readPropertyMultipleImplemented", true);
        status.put("writeImplemented", false);
        status.put("subscriptionImplemented", false);
        status.put("connected", connectionAdapter != null && connectionAdapter.isConnected());
        status.put("configuredPointCount", configuredAddresses.size());
        status.put("requestTimeoutMs", requestTimeoutMs);
        if (connectionAdapter != null) {
            BacnetRemoteDevice remoteDevice = connectionAdapter.getRemoteDevice();
            if (remoteDevice != null) {
                status.put("remoteDeviceInstance", remoteDevice.getDeviceInstance());
                status.put("remoteAddress", remoteDevice.getSocketAddress().toString());
            }
        }
        status.put("message", "BACnet/IP supports UDP ReadProperty and ReadPropertyMultiple polling");
        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
        throw unsupported("executeCommand", "first delivery only implements ReadProperty");
    }

    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        if (points == null) {
            configuredAddresses.clear();
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                configuredAddresses.put(resolvePointCacheKey(point), BacnetAddressParser.parse(point));
            } catch (Exception ex) {
                log.warn("Cache BACnet address failed, deviceId={}, pointId={}, address={}, error={}",
                        deviceInfo != null ? deviceInfo.getDeviceId() : null,
                        point.getPointId(),
                        point.getAddress(),
                        ex.getMessage());
            }
        }
    }

    private ProcessResult buildScalarProcessResult(DataPoint point,
                                                   BacnetAddress address,
                                                   Object rawValue) {
        Object processedValue = normalizeReadValue(point, address, rawValue);
        ProcessContext context = new ProcessContext();
        context.addAttribute("deviceId", deviceInfo.getDeviceId());
        ProcessResult processResult = dataQualityProcessor.process(context, point, processedValue);
        if (!processResult.isSuccess()) {
            log.warn("BACnet/IP data quality check failed {}.{}, reason: {}",
                    deviceInfo.getDeviceId(), point.getPointName(), processResult.getMessage());
        }
        processResult.addMetadata("address", address.getCanonicalAddress());
        processResult.addMetadata("objectType", address.getObjectType());
        processResult.addMetadata("instanceNumber", address.getInstanceNumber());
        processResult.addMetadata("propertyIdentifier", address.getPropertyIdentifier());
        processResult.addMetadata("processingMode", isStringLike(address, rawValue)
                ? "protocol_string_passthrough"
                : "default_scalar_conversion");
        return processResult;
    }

    private Object normalizeReadValue(DataPoint point, BacnetAddress address, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (isStringLike(address, rawValue) || rawValue instanceof boolean[] || rawValue instanceof String) {
            return rawValue;
        }
        return convertData(point, rawValue);
    }

    private boolean isStringLike(BacnetAddress address, Object rawValue) {
        if (rawValue instanceof String) {
            return true;
        }
        String driverType = address.getDriverDataType();
        return driverType != null && ("STRING".equalsIgnoreCase(driverType)
                || "CHARACTER_STRING".equalsIgnoreCase(driverType));
    }

    private BacnetReadPropertyResponse exchange(BacnetReadPropertyRequest request) throws Exception {
        try {
            return requireConnection().readProperty(request, requestTimeoutMs);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    private BacnetReadPropertyMultipleResponse exchange(BacnetReadPropertyMultipleRequest request) throws Exception {
        try {
            return requireConnection().readPropertyMultiple(request, requestTimeoutMs);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    private BacnetAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(resolvePointCacheKey(point), ignored -> BacnetAddressParser.parse(point));
    }

    private BacnetIpConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("BACnet/IP connection has not been established");
        }
        return connectionAdapter;
    }

    private int requireRemoteDeviceInstance() {
        DeviceConnection runtimeConfig = getCurrentConnectionConfig();
        if (runtimeConfig == null) {
            runtimeConfig = requireConnectionConfig();
        }
        Integer remoteDeviceInstance = runtimeConfig.getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            throw new IllegalStateException("BACnet/IP requires remoteDeviceInstance");
        }
        return remoteDeviceInstance;
    }

    private boolean isReadPropertyMultipleEnabled() {
        DeviceConnection runtimeConfig = getCurrentConnectionConfig();
        if (runtimeConfig == null) {
            runtimeConfig = requireConnectionConfig();
        }
        return !Boolean.FALSE.equals(runtimeConfig.getBoolConfig("readPropertyMultipleEnabled", true));
    }

    private void readSequentially(List<BacnetReadPointPlan> pointPlans, Map<String, Object> values) throws Exception {
        for (BacnetReadPointPlan pointPlan : pointPlans) {
            values.put(pointPlan.getPoint().getPointId(), doReadPoint(pointPlan.getPoint()));
        }
    }

    private void readPlanByReadPropertyMultiple(BacnetReadPropertyMultiplePlan plan,
                                                Map<String, Object> values) throws Exception {
        BacnetReadPropertyMultipleRequest.BacnetReadPropertyMultipleRequestBuilder requestBuilder =
                BacnetReadPropertyMultipleRequest.builder()
                        .invokeId(nextInvokeId())
                        .remoteDeviceInstance(requireRemoteDeviceInstance());
        for (BacnetReadPropertyMultiplePlan.ReadGroup group : plan.getGroups()) {
            BacnetReadPropertyMultipleRequest.ReadAccessSpec.ReadAccessSpecBuilder accessSpecBuilder =
                    BacnetReadPropertyMultipleRequest.ReadAccessSpec.builder()
                            .objectType(group.getObjectType())
                            .objectInstance(group.getObjectInstance());
            for (BacnetReadPointPlan pointPlan : group.getPointPlans()) {
                BacnetAddress address = pointPlan.getAddress();
                accessSpecBuilder.propertyReference(BacnetReadPropertyMultipleRequest.PropertyReferenceSpec.builder()
                        .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                        .arrayIndex(address.getArrayIndex())
                        .build());
            }
            requestBuilder.accessSpecification(accessSpecBuilder.build());
        }

        BacnetReadPropertyMultipleResponse response = exchange(requestBuilder.build());
        BacnetReadPropertyMultipleResultIndex resultIndex = BacnetReadPropertyMultipleResultIndex.from(response);
        for (BacnetReadPointPlan pointPlan : plan.getPointPlans()) {
            BacnetReadPropertyMultipleResponse.PropertyValueResult propertyResult = resultIndex.get(pointPlan.getAddress());
            if (propertyResult == null) {
                throw new IllegalStateException("BACnet RPM response missing property result for address="
                        + pointPlan.getAddress().getCanonicalAddress());
            }
            if (propertyResult.isError()) {
                throw new IllegalStateException("BACnet RPM property read failed for address="
                        + pointPlan.getAddress().getCanonicalAddress() + ", " + propertyResult.getErrorMessage());
            }
            values.put(pointPlan.getPoint().getPointId(), propertyResult.getValue());
        }
    }

    private int nextInvokeId() {
        return invokeIdSequence.updateAndGet(current -> {
            int next = current + 1;
            if (next > 255) {
                next = 1;
            }
            return next;
        });
    }

    private boolean shouldInvalidateConnection(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof SocketException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("timed out")
                    || message.contains("socket")
                    || message.contains("invokeId mismatch")
                    || message.contains("Unexpected BACnet")
                    || message.contains("frame length mismatch"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void invalidateConnection(Throwable cause) {
        if (!connected && connectionAdapter == null) {
            return;
        }
        log.warn("Invalidate BACnet/IP connection after protocol/transport failure, deviceId={}, error={}",
                deviceInfo != null ? deviceInfo.getDeviceId() : null,
                cause != null ? cause.getMessage() : null);
        try {
            BacnetIpConnectionAdapter adapter = connectionAdapter;
            connectionAdapter = null;
            if (adapter != null) {
                adapter.disconnect();
            }
        } catch (Exception disconnectError) {
            log.warn("Disconnect broken BACnet/IP adapter failed, deviceId={}",
                    deviceInfo != null ? deviceInfo.getDeviceId() : null, disconnectError);
        } finally {
            removeManagedConnection("BACnet/IP");
            connected = false;
            connectionStatus = "DISCONNECTED";
            lastError = cause != null ? cause.getMessage() : lastError;
        }
    }

    protected UnsupportedOperationException unsupported(String operation, String reason) {
        String message = "BACnet/IP collector does not implement " + operation;
        if (reason != null && !reason.isBlank()) {
            message = message + ": " + reason;
        }
        log.warn(message);
        return new UnsupportedOperationException(message);
    }
}
