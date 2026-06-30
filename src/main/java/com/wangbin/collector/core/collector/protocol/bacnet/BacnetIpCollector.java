package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetAddress;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
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
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.util.BacnetAddressParser;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.connection.adapter.BacnetConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessContext;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
public class BacnetIpCollector extends ConnectionBackedCollector {

    private final Map<String, BacnetAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, SubscriptionBinding> subscriptionBindings = new ConcurrentHashMap<>();
    private final AtomicInteger invokeIdSequence = new AtomicInteger(1);
    private final AtomicInteger subscriberProcessSequence = new AtomicInteger(1000);
    private final AtomicLong readPropertyMultipleFallbackCount = new AtomicLong(0);
    private final AtomicLong covNotificationCount = new AtomicLong(0);
    private final AtomicLong subscriptionRequestCount = new AtomicLong(0);
    private final AtomicLong subscriptionCancelCount = new AtomicLong(0);

    private BacnetConnectionAdapter connectionAdapter;
    private volatile int requestTimeoutMs = 5000;

    @Override
    public String getCollectorType() {
        return protocolCode();
    }

    @Override
    public String getProtocolType() {
        return protocolCode();
    }

    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createBacnetConnectionAdapter(desiredConfig);
        this.connectionAdapter.setCovNotificationListener(this::handleCovNotification);
        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }
        requestTimeoutMs = resolveRequestTimeout(currentConfig);
        configuredAddresses.clear();
        subscriptionBindings.clear();
        log.info("{} collector connected, deviceId={}, timeoutMs={}", protocolDisplayName(), deviceInfo.getDeviceId(), requestTimeoutMs);
    }

    @Override
    protected void doDisconnect() {
        removeManagedConnection(protocolDisplayName());
        connectionAdapter = null;
        subscriptionBindings.clear();
        configuredAddresses.clear();
        log.info("{} collector disconnected, deviceId={}", protocolDisplayName(), deviceInfo.getDeviceId());
    }

    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        checkConnection();
        long startTime = System.currentTimeMillis();
        try {
            BacnetAddress address = requireAddress(point);
            Object rawValue = doReadPoint(point);
            ProcessResult processResult = buildScalarProcessResult(point, address, rawValue, "poll");
            lastProcessResults.put(point.getPointId(), processResult);
            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return processResult.getFinalValue();
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            throw new CollectorException(protocolDisplayName() + " point read failed",
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
                    ProcessResult processResult = buildScalarProcessResult(point, address, rawValue, "poll");
                    lastProcessResults.put(pointId, processResult);
                    results.put(pointId, processResult.getFinalValue());
                } catch (Exception ex) {
                    log.error("{} batch point process failed, deviceId={}, pointId={}", protocolDisplayName(),
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
            throw new CollectorException(protocolDisplayName() + " batch point read failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        BacnetAddress address = requireAddress(point);
        BacnetReadPropertyResponse response = exchange(readPropertyRequest(address));
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
                readPropertyMultipleFallbackCount.incrementAndGet();
                log.warn("{} ReadPropertyMultiple failed, fallback to ReadProperty, deviceId={}, planPoints={}, error={}", protocolDisplayName(),
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
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        BacnetAddress address = requireAddress(point);
        BacnetWritePropertyRequest request = BacnetWritePropertyRequest.builder()
                .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                .objectInstance(address.getInstanceNumber())
                .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                .arrayIndex(address.getArrayIndex())
                .value(value)
                .valueType(resolveWriteValueType(point, address, null))
                .priority(resolveWritePriority(point, null))
                .invokeId(nextInvokeId())
                .remoteDeviceInstance(requireRemoteDeviceInstance())
                .build();
        exchange(request);
        return true;
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null || point.getPointId() == null) {
                continue;
            }
            try {
                results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
            } catch (Exception ex) {
                log.error("{} point write failed, deviceId={}, pointId={}", protocolDisplayName(),
                        deviceInfo.getDeviceId(), point.getPointId(), ex);
                recordException(ex, point);
                results.put(point.getPointId(), false);
            }
        }
        return results;
    }

    @Override
    protected void doSubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return;
        }
        ensureNotificationListenerRegistered();
        unsubscribeIfAlreadyBound(points);
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            BacnetAddress address = requireAddress(point);
            SubscriptionBinding binding = subscribePoint(point, address);
            subscriptionBindings.put(resolvePointCacheKey(point), binding);
            subscriptionRequestCount.incrementAndGet();
        }
    }

    @Override
    protected void doUnsubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            SubscriptionBinding binding = subscriptionBindings.remove(resolvePointCacheKey(point));
            if (binding == null) {
                continue;
            }
            try {
                unsubscribeBinding(binding);
                subscriptionCancelCount.incrementAndGet();
            } catch (Exception ex) {
                log.warn("{} unsubscribe failed, deviceId={}, pointId={}", protocolDisplayName(),
                        deviceInfo.getDeviceId(), point.getPointId(), ex);
            }
        }
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("protocol", protocolCode());
        status.put("driver", driverName());
        status.put("implemented", true);
        status.put("transport", resolveTransportName());
        status.put("readPropertyImplemented", true);
        status.put("readPropertyMultipleImplemented", true);
        status.put("writeImplemented", true);
        status.put("subscriptionImplemented", true);
        status.put("connected", connectionAdapter != null && connectionAdapter.isConnected());
        status.put("configuredPointCount", configuredAddresses.size());
        status.put("activeSubscriptions", subscriptionBindings.size());
        status.put("requestTimeoutMs", requestTimeoutMs);
        status.put("readPropertyMultipleFallbackCount", readPropertyMultipleFallbackCount.get());
        status.put("covNotificationCount", covNotificationCount.get());
        status.put("subscriptionRequestCount", subscriptionRequestCount.get());
        status.put("subscriptionCancelCount", subscriptionCancelCount.get());
        status.put("requestRetryCount", connectionAdapter != null ? connectionAdapter.getRequestRetryCount() : 0L);
        status.put("requestTimeoutCount", connectionAdapter != null ? connectionAdapter.getRequestTimeoutCount() : 0L);
        status.put("invokeIdMismatchCount", connectionAdapter != null ? connectionAdapter.getInvokeIdMismatchCount() : 0L);
        status.put("segmentedResponseCount", connectionAdapter != null ? connectionAdapter.getSegmentedResponseCount() : 0L);
        status.put("tokenReceiveCount", connectionAdapter != null ? connectionAdapter.getTokenReceiveCount() : 0L);
        status.put("tokenPassCount", connectionAdapter != null ? connectionAdapter.getTokenPassCount() : 0L);
        status.put("pollForMasterCount", connectionAdapter != null ? connectionAdapter.getPollForMasterCount() : 0L);
        status.put("replyToPollCount", connectionAdapter != null ? connectionAdapter.getReplyToPollCount() : 0L);
        status.put("frameErrorCount", connectionAdapter != null ? connectionAdapter.getFrameErrorCount() : 0L);
        status.put("crcErrorCount", connectionAdapter != null ? connectionAdapter.getCrcErrorCount() : 0L);
        status.put("bbmdActive", connectionAdapter != null && connectionAdapter.isForeignDeviceRegistrationActive());
        status.put("foreignDeviceRegistrationCount", connectionAdapter != null ? connectionAdapter.getForeignDeviceRegistrationCount() : 0L);
        status.put("foreignDeviceRenewCount", connectionAdapter != null ? connectionAdapter.getForeignDeviceRenewCount() : 0L);
        status.put("foreignDeviceRenewFailureCount", connectionAdapter != null ? connectionAdapter.getForeignDeviceRenewFailureCount() : 0L);
        status.put("foreignDeviceLeaseExpiresAt", connectionAdapter != null ? connectionAdapter.getForeignDeviceLeaseExpiresAt() : 0L);
        if (connectionAdapter != null) {
            BacnetRemoteDevice remoteDevice = connectionAdapter.getRemoteDevice();
            if (remoteDevice != null) {
                status.put("remoteDeviceInstance", remoteDevice.getDeviceInstance());
                status.put("remoteAddress", remoteDevice.getSocketAddress() != null ? remoteDevice.getSocketAddress().toString() : null);
                status.put("remoteVendorId", remoteDevice.getVendorId());
                status.put("remoteSegmentationSupported", remoteDevice.getSegmentationSupported());
                status.put("discoveredByWhoIs", remoteDevice.isDiscoveredByWhoIs());
            }
        }
        status.put("message", capabilityMessage());
        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        String normalized = normalizeCommand(command);
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        return switch (normalized) {
            case "read", "read_property", "readproperty" -> executeReadPropertyCommand(safeParams);
            case "read_multiple", "read_property_multiple", "readpropertymultiple" -> executeReadPropertyMultipleCommand(safeParams);
            case "write", "write_property", "writeproperty" -> executeWritePropertyCommand(safeParams);
            case "status", "diagnostic", "device_status" -> getDeviceStatus();
            case "device_info", "deviceinfo" -> executeDeviceInfoCommand();
            case "who_is", "whois" -> executeWhoIsCommand();
            case "discover_objects", "discoverobjects" -> executeDiscoverObjectsCommand();
            default -> throw new IllegalArgumentException("Unsupported " + protocolDisplayName() + " command: " + command);
        };
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

    @Override
    protected Object convertDataForWrite(DataPoint point, Object value) {
        if (value == null) {
            return null;
        }
        BacnetAddress address = point != null ? requireAddress(point) : null;
        String valueType = resolveWriteValueType(point, address, null);
        if (isStringType(valueType) || value instanceof String) {
            return String.valueOf(value);
        }
        if (isBooleanType(valueType) || value instanceof Boolean) {
            return toBoolean(value);
        }
        return super.convertDataForWrite(point, value);
    }

    @Override
    protected ProcessResult ingestPushedValue(DataPoint point, Object rawValue) {
        if (point == null) {
            return null;
        }
        String resolvedDeviceId = point.getDeviceId();
        if ((resolvedDeviceId == null || resolvedDeviceId.isBlank()) && deviceInfo != null) {
            resolvedDeviceId = deviceInfo.getDeviceId();
        }
        try {
            BacnetAddress address = requireAddress(point);
            Object processedValue = normalizeReadValue(point, address, rawValue);
            ProcessContext context = new ProcessContext();
            context.addAttribute("deviceId", resolvedDeviceId);
            ProcessResult processResult = dataQualityProcessor.process(context, point, processedValue);
            enrichProcessResult(processResult, address, "cov");
            lastProcessResults.put(point.getPointId(), processResult);
            if (!processResult.isSuccess()) {
                log.warn("{} pushed data quality check failed {}.{}, reason: {}", protocolDisplayName(),
                        resolvedDeviceId, point.getPointName(), processResult.getMessage());
            }
            lastActivityTime = System.currentTimeMillis();
            if (telemetryIngressService != null) {
                telemetryIngressService.append(resolvedDeviceId, point, processResult);
            }
            return processResult;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            ProcessResult error = ProcessResult.error(rawValue,
                    protocolDisplayName() + " pushed telemetry process failed: " + e.getMessage(),
                    DataQuality.PROCESS_ERROR);
            lastProcessResults.put(point.getPointId(), error);
            if (telemetryIngressService != null) {
                telemetryIngressService.append(resolvedDeviceId, point, error);
            }
            return error;
        }
    }

    private ProcessResult buildScalarProcessResult(DataPoint point,
                                                   BacnetAddress address,
                                                   Object rawValue,
                                                   String source) {
        Object processedValue = normalizeReadValue(point, address, rawValue);
        ProcessContext context = new ProcessContext();
        context.addAttribute("deviceId", deviceInfo.getDeviceId());
        ProcessResult processResult = dataQualityProcessor.process(context, point, processedValue);
        if (!processResult.isSuccess()) {
            log.warn("{} data quality check failed {}.{}, reason: {}", protocolDisplayName(),
                    deviceInfo.getDeviceId(), point.getPointName(), processResult.getMessage());
        }
        enrichProcessResult(processResult, address, source);
        return processResult;
    }

    private void enrichProcessResult(ProcessResult processResult, BacnetAddress address, String source) {
        processResult.addMetadata("address", address.getCanonicalAddress());
        processResult.addMetadata("objectType", address.getObjectType());
        processResult.addMetadata("instanceNumber", address.getInstanceNumber());
        processResult.addMetadata("propertyIdentifier", address.getPropertyIdentifier());
        processResult.addMetadata("source", source);
        processResult.addMetadata("processingMode", isStringLike(address, processResult.getRawValue())
                ? "protocol_string_passthrough"
                : "default_scalar_conversion");
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

    private void exchange(BacnetWritePropertyRequest request) throws Exception {
        try {
            requireConnection().writeProperty(request, requestTimeoutMs);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    private void exchange(BacnetSubscribeCovRequest request) throws Exception {
        try {
            requireConnection().subscribeCov(request, requestTimeoutMs);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    private void exchange(BacnetSubscribeCovPropertyRequest request) throws Exception {
        try {
            requireConnection().subscribeCovProperty(request, requestTimeoutMs);
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

    private BacnetConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException(protocolDisplayName() + " connection has not been established");
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
            throw new IllegalStateException(protocolDisplayName() + " requires remoteDeviceInstance");
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

    private int nextSubscriberProcessIdentifier() {
        return subscriberProcessSequence.updateAndGet(current -> {
            int next = current + 1;
            if (next > 65534) {
                next = 1000;
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
        log.warn("Invalidate {} connection after protocol/transport failure, deviceId={}, error={}", protocolDisplayName(),
                deviceInfo != null ? deviceInfo.getDeviceId() : null,
                cause != null ? cause.getMessage() : null);
        try {
            BacnetConnectionAdapter adapter = connectionAdapter;
            connectionAdapter = null;
            if (adapter instanceof ConnectionAdapter<?> managedAdapter) {
                managedAdapter.disconnect();
            }
        } catch (Exception disconnectError) {
            log.warn("Disconnect broken {} adapter failed, deviceId={}", protocolDisplayName(),
                    deviceInfo != null ? deviceInfo.getDeviceId() : null, disconnectError);
        } finally {
            removeManagedConnection(protocolDisplayName());
            connected = false;
            connectionStatus = "DISCONNECTED";
            lastError = cause != null ? cause.getMessage() : lastError;
        }
    }
    private SubscriptionBinding subscribePoint(DataPoint point, BacnetAddress address) throws Exception {
        int processIdentifier = nextSubscriberProcessIdentifier();
        boolean propertyLevel = shouldUsePropertySubscription(point, address);
        boolean issueConfirmedNotifications = resolveConfirmedNotifications(point);
        Integer lifetimeSeconds = resolveCovLifetimeSeconds(point);
        if (propertyLevel) {
            BacnetSubscribeCovPropertyRequest request = BacnetSubscribeCovPropertyRequest.builder()
                    .subscriberProcessIdentifier(processIdentifier)
                    .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                    .objectInstance(address.getInstanceNumber())
                    .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                    .arrayIndex(address.getArrayIndex())
                    .issueConfirmedNotifications(issueConfirmedNotifications)
                    .lifetimeSeconds(lifetimeSeconds)
                    .covIncrement(resolveCovIncrement(point))
                    .invokeId(nextInvokeId())
                    .remoteDeviceInstance(requireRemoteDeviceInstance())
                    .build();
            exchange(request);
        } else {
            BacnetSubscribeCovRequest request = BacnetSubscribeCovRequest.builder()
                    .subscriberProcessIdentifier(processIdentifier)
                    .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                    .objectInstance(address.getInstanceNumber())
                    .issueConfirmedNotifications(issueConfirmedNotifications)
                    .lifetimeSeconds(lifetimeSeconds)
                    .invokeId(nextInvokeId())
                    .remoteDeviceInstance(requireRemoteDeviceInstance())
                    .build();
            exchange(request);
        }
        return new SubscriptionBinding(processIdentifier, address, propertyLevel, issueConfirmedNotifications);
    }

    private void unsubscribeBinding(SubscriptionBinding binding) throws Exception {
        if (binding.propertyLevel) {
            BacnetSubscribeCovPropertyRequest request = BacnetSubscribeCovPropertyRequest.builder()
                    .subscriberProcessIdentifier(binding.processIdentifier)
                    .objectType(BacnetObjectType.fromId(binding.address.getObjectTypeId()))
                    .objectInstance(binding.address.getInstanceNumber())
                    .propertyIdentifier(BacnetPropertyIdentifier.fromId(binding.address.getPropertyIdentifierId()))
                    .arrayIndex(binding.address.getArrayIndex())
                    .issueConfirmedNotifications(binding.issueConfirmedNotifications)
                    .lifetimeSeconds(0)
                    .invokeId(nextInvokeId())
                    .remoteDeviceInstance(requireRemoteDeviceInstance())
                    .build();
            exchange(request);
            return;
        }
        BacnetSubscribeCovRequest request = BacnetSubscribeCovRequest.builder()
                .subscriberProcessIdentifier(binding.processIdentifier)
                .objectType(BacnetObjectType.fromId(binding.address.getObjectTypeId()))
                .objectInstance(binding.address.getInstanceNumber())
                .issueConfirmedNotifications(binding.issueConfirmedNotifications)
                .lifetimeSeconds(0)
                .invokeId(nextInvokeId())
                .remoteDeviceInstance(requireRemoteDeviceInstance())
                .build();
        exchange(request);
    }

    private void unsubscribeIfAlreadyBound(List<DataPoint> points) throws Exception {
        List<DataPoint> existing = new ArrayList<>();
        for (DataPoint point : points) {
            if (point != null && subscriptionBindings.containsKey(resolvePointCacheKey(point))) {
                existing.add(point);
            }
        }
        if (!existing.isEmpty()) {
            doUnsubscribe(existing);
        }
    }

    private void ensureNotificationListenerRegistered() {
        if (connectionAdapter != null) {
            connectionAdapter.setCovNotificationListener(this::handleCovNotification);
        }
    }

    private void handleCovNotification(BacnetCovNotification notification) {
        covNotificationCount.incrementAndGet();
        if (notification == null || notification.getPropertyValues() == null || notification.getPropertyValues().isEmpty()) {
            return;
        }
        List<DataPoint> points = new ArrayList<>(subscribedPointMap.values());
        if (points.isEmpty()) {
            return;
        }
        for (BacnetCovNotification.PropertyValue propertyValue : notification.getPropertyValues()) {
            for (DataPoint point : points) {
                try {
                    BacnetAddress address = requireAddress(point);
                    if (!matchesNotification(notification, propertyValue, address)) {
                        continue;
                    }
                    ProcessResult processResult = ingestPushedValue(point, propertyValue.getValue());
                    if (processResult != null) {
                        processResult.addMetadata("covSubscriberProcessIdentifier", notification.getSubscriberProcessIdentifier());
                        processResult.addMetadata("covTimeRemaining", notification.getTimeRemaining());
                    }
                } catch (Exception ex) {
                    log.warn("{} COV notification dispatch failed, deviceId={}, pointId={}", protocolDisplayName(),
                            deviceInfo != null ? deviceInfo.getDeviceId() : null,
                            point != null ? point.getPointId() : null,
                            ex);
                }
            }
        }
    }

    private boolean matchesNotification(BacnetCovNotification notification,
                                        BacnetCovNotification.PropertyValue propertyValue,
                                        BacnetAddress address) {
        return notification.getMonitoredObjectType() != null
                && notification.getMonitoredObjectType().getId() == address.getObjectTypeId()
                && notification.getMonitoredObjectInstance() == address.getInstanceNumber()
                && propertyValue.getPropertyIdentifier() != null
                && propertyValue.getPropertyIdentifier().getId() == address.getPropertyIdentifierId()
                && Objects.equals(propertyValue.getArrayIndex(), address.getArrayIndex());
    }

    private boolean shouldUsePropertySubscription(DataPoint point, BacnetAddress address) {
        Boolean pointEnabled = point.getAdditionalConfig("covPropertyEnabled", null);
        if (pointEnabled != null) {
            return pointEnabled;
        }
        DeviceConnection connection = currentOrRequiredConnectionConfig();
        Boolean connectionEnabled = connection.getBoolConfig("covPropertyEnabled", null);
        if (connectionEnabled != null) {
            return connectionEnabled;
        }
        return address.getArrayIndex() != null || point.getAdditionalConfig("covIncrement", null) != null;
    }

    private boolean resolveConfirmedNotifications(DataPoint point) {
        Boolean pointValue = point.getAdditionalConfig("covConfirmedNotifications", null);
        if (pointValue != null) {
            return pointValue;
        }
        return Boolean.TRUE.equals(currentOrRequiredConnectionConfig().getBoolConfig("covConfirmedNotifications", false));
    }

    private Integer resolveCovLifetimeSeconds(DataPoint point) {
        Integer pointValue = point.getAdditionalConfig("covLifetimeSeconds", null);
        if (pointValue != null && pointValue >= 0) {
            return pointValue;
        }
        Integer connectionValue = currentOrRequiredConnectionConfig().getIntConfig("defaultCovLifetimeSeconds", null);
        if (connectionValue != null && connectionValue >= 0) {
            return connectionValue;
        }
        return 60;
    }

    private Double resolveCovIncrement(DataPoint point) {
        Object value = point.getAdditionalConfig("covIncrement", null);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
    private String resolveWriteValueType(DataPoint point, BacnetAddress address, Map<String, Object> params) {
        if (params != null && params.containsKey("valueType") && params.get("valueType") != null) {
            return String.valueOf(params.get("valueType"));
        }
        Object pointSpecific = point != null ? point.getAdditionalConfig("bacnetWriteType", null) : null;
        if (pointSpecific == null && point != null) {
            pointSpecific = point.getAdditionalConfig("driverDataType", null);
        }
        if (pointSpecific != null) {
            return String.valueOf(pointSpecific);
        }
        if (address != null && address.getDriverDataType() != null && !"AUTO".equalsIgnoreCase(address.getDriverDataType())) {
            return address.getDriverDataType();
        }
        if (point != null && point.getDataType() != null && !point.getDataType().isBlank()) {
            return point.getDataType();
        }
        return "AUTO";
    }

    private Integer resolveWritePriority(DataPoint point, Map<String, Object> params) {
        Object candidate = null;
        if (params != null) {
            candidate = firstPresent(params, "priority", "writePriority", "bacnetPriority");
        }
        if (candidate == null && point != null) {
            candidate = firstPresent(point.getAdditionalConfig(), "priority", "writePriority", "bacnetPriority");
        }
        if (candidate == null) {
            return null;
        }
        if (candidate instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(candidate));
    }

    private Map<String, Object> executeReadPropertyCommand(Map<String, Object> params) throws Exception {
        BacnetAddress address = parseCommandAddress(params);
        BacnetReadPropertyResponse response = exchange(readPropertyRequest(address));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address.getCanonicalAddress());
        result.put("value", response.getValue());
        result.put("valueType", response.getValueType());
        return result;
    }

    private Map<String, Object> executeReadPropertyMultipleCommand(Map<String, Object> params) throws Exception {
        List<String> addresses = parseCommandAddresses(params);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String addressText : addresses) {
            BacnetAddress address = BacnetAddressParser.parse(addressText);
            BacnetReadPropertyResponse response = exchange(readPropertyRequest(address));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", response.getValue());
            item.put("valueType", response.getValueType());
            values.put(address.getCanonicalAddress(), item);
        }
        return values;
    }

    private Map<String, Object> executeWritePropertyCommand(Map<String, Object> params) throws Exception {
        BacnetAddress address = parseCommandAddress(params);
        if (!params.containsKey("value")) {
            throw new IllegalArgumentException("BACnet write_property command requires value");
        }
        BacnetWritePropertyRequest request = BacnetWritePropertyRequest.builder()
                .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                .objectInstance(address.getInstanceNumber())
                .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                .arrayIndex(address.getArrayIndex())
                .value(params.get("value"))
                .valueType(resolveWriteValueType(null, address, params))
                .priority(resolveWritePriority(null, params))
                .invokeId(nextInvokeId())
                .remoteDeviceInstance(requireRemoteDeviceInstance())
                .build();
        exchange(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address.getCanonicalAddress());
        result.put("success", true);
        result.put("valueType", request.getValueType());
        result.put("priority", request.getPriority());
        return result;
    }

    private Map<String, Object> executeDeviceInfoCommand() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("remoteDeviceInstance", requireRemoteDeviceInstance());
        info.put("objectName", safeReadDeviceProperty(BacnetPropertyIdentifier.OBJECT_NAME, null));
        info.put("description", safeReadDeviceProperty(BacnetPropertyIdentifier.DESCRIPTION, null));
        info.put("modelName", safeReadDeviceProperty(BacnetPropertyIdentifier.MODEL_NAME, null));
        info.put("vendorIdentifier", safeReadDeviceProperty(BacnetPropertyIdentifier.VENDOR_IDENTIFIER, null));
        info.put("protocolVersion", safeReadDeviceProperty(BacnetPropertyIdentifier.PROTOCOL_VERSION, null));
        info.put("protocolRevision", safeReadDeviceProperty(BacnetPropertyIdentifier.PROTOCOL_REVISION, null));
        info.put("maxApduLengthAccepted", safeReadDeviceProperty(BacnetPropertyIdentifier.MAX_APDU_LENGTH_ACCEPTED, null));
        info.put("segmentationSupported", safeReadDeviceProperty(BacnetPropertyIdentifier.SEGMENTATION_SUPPORTED, null));
        info.put("objectCount", safeReadDeviceProperty(BacnetPropertyIdentifier.OBJECT_LIST, 0));
        return info;
    }

    private Map<String, Object> executeWhoIsCommand() {
        Map<String, Object> result = new LinkedHashMap<>();
        BacnetRemoteDevice remoteDevice = connectionAdapter != null ? connectionAdapter.getRemoteDevice() : null;
        result.put("remoteDeviceInstance", remoteDevice != null ? remoteDevice.getDeviceInstance() : requireRemoteDeviceInstance());
        result.put("remoteAddress", remoteDevice != null && remoteDevice.getSocketAddress() != null ? remoteDevice.getSocketAddress().toString() : null);
        result.put("vendorId", remoteDevice != null ? remoteDevice.getVendorId() : null);
        result.put("maxApduLengthAccepted", remoteDevice != null ? remoteDevice.getMaxApduLengthAccepted() : null);
        result.put("segmentationSupported", remoteDevice != null ? remoteDevice.getSegmentationSupported() : null);
        result.put("discoveredByWhoIs", remoteDevice != null && remoteDevice.isDiscoveredByWhoIs());
        return result;
    }

    private List<String> executeDiscoverObjectsCommand() throws Exception {
        Object countValue = safeReadDeviceProperty(BacnetPropertyIdentifier.OBJECT_LIST, 0);
        int count = countValue instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(countValue));
        List<String> objects = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            Object value = safeReadDeviceProperty(BacnetPropertyIdentifier.OBJECT_LIST, index);
            if (value != null) {
                objects.add(String.valueOf(value));
            }
        }
        return objects;
    }

    private Object safeReadDeviceProperty(BacnetPropertyIdentifier propertyIdentifier, Integer arrayIndex) {
        try {
            BacnetReadPropertyRequest request = BacnetReadPropertyRequest.builder()
                    .objectType(BacnetObjectType.DEVICE)
                    .objectInstance(requireRemoteDeviceInstance())
                    .propertyIdentifier(propertyIdentifier)
                    .arrayIndex(arrayIndex)
                    .invokeId(nextInvokeId())
                    .remoteDeviceInstance(requireRemoteDeviceInstance())
                    .build();
            return exchange(request).getValue();
        } catch (Exception ex) {
            log.debug("Read BACnet device property failed, deviceId={}, property={}, arrayIndex={}, error={}",
                    deviceInfo != null ? deviceInfo.getDeviceId() : null,
                    propertyIdentifier.getName(),
                    arrayIndex,
                    ex.getMessage());
            return null;
        }
    }

    private BacnetReadPropertyRequest readPropertyRequest(BacnetAddress address) {
        return BacnetReadPropertyRequest.builder()
                .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                .objectInstance(address.getInstanceNumber())
                .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                .arrayIndex(address.getArrayIndex())
                .invokeId(nextInvokeId())
                .remoteDeviceInstance(requireRemoteDeviceInstance())
                .build();
    }

    private BacnetAddress parseCommandAddress(Map<String, Object> params) {
        Object address = firstPresent(params, "address", "bacnetAddress");
        if (address == null) {
            throw new IllegalArgumentException("BACnet command requires address");
        }
        return BacnetAddressParser.parse(String.valueOf(address));
    }

    private List<String> parseCommandAddresses(Map<String, Object> params) {
        Object addresses = firstPresent(params, "addresses", "bacnetAddresses");
        if (addresses instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.toList());
        }
        if (addresses instanceof String text && !text.isBlank()) {
            String[] items = text.split(",");
            List<String> result = new ArrayList<>(items.length);
            for (String item : items) {
                if (item != null && !item.isBlank()) {
                    result.add(item.trim());
                }
            }
            return result;
        }
        if (params.containsKey("address")) {
            return List.of(String.valueOf(params.get("address")));
        }
        throw new IllegalArgumentException("BACnet read_property_multiple command requires addresses");
    }

    private String normalizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        return command.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private DeviceConnection currentOrRequiredConnectionConfig() {
        DeviceConnection runtimeConfig = getCurrentConnectionConfig();
        return runtimeConfig != null ? runtimeConfig : requireConnectionConfig();
    }

    private int resolveRequestTimeout(DeviceConnection connection) {
        Integer apduTimeout = connection.getIntConfig("apduTimeout", null);
        if (apduTimeout == null || apduTimeout <= 0) {
            apduTimeout = connection.getIntConfig("apduTimeoutMs", null);
        }
        if (apduTimeout != null && apduTimeout > 0) {
            return apduTimeout;
        }
        Integer timeout = connection.getReadTimeout() != null ? connection.getReadTimeout() : connection.getTimeout();
        return timeout != null && timeout > 0 ? timeout : 5000;
    }

    private boolean isStringType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("STRING") || normalized.equals("CHAR") || normalized.equals("WCHAR");
    }

    private boolean isBooleanType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("BOOL") || normalized.equals("BOOLEAN") || normalized.equals("BINARY");
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "on".equals(text) || "active".equals(text);
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }


    protected String protocolCode() {
        return "BACNET_IP";
    }

    protected String protocolDisplayName() {
        return "BACnet/IP";
    }

    protected String defaultTransportName() {
        return "UDP";
    }

    protected String driverName() {
        return "SELF_IMPLEMENTED_MINIMAL_PLUS";
    }

    protected boolean supportsForeignDeviceRegistration() {
        return true;
    }

    protected String resolveTransportName() {
        return connectionAdapter != null ? connectionAdapter.getTransportName() : defaultTransportName();
    }

    protected String capabilityMessage() {
        StringBuilder message = new StringBuilder(protocolDisplayName())
                .append(" supports polling, segmented APDU assembly, WriteProperty and COV subscriptions over ")
                .append(resolveTransportName());
        if (supportsForeignDeviceRegistration()) {
            message.append(" with optional BBMD/Foreign Device registration");
        }
        return message.toString();
    }

    protected BacnetConnectionAdapter createBacnetConnectionAdapter(DeviceConnection desiredConfig) throws Exception {
        ConnectionAdapter<?> adapter = createManagedConnection(desiredConfig);
        if (!(adapter instanceof BacnetConnectionAdapter bacnetAdapter)) {
            removeManagedConnection(protocolDisplayName());
            throw new IllegalStateException(protocolDisplayName() + " connection adapter type mismatch");
        }
        try {
            connectManagedConnection();
            return bacnetAdapter;
        } catch (Exception ex) {
            removeManagedConnection(protocolDisplayName());
            throw ex;
        }
    }
    protected UnsupportedOperationException unsupported(String operation, String reason) {
        String message = protocolDisplayName() + " collector does not implement " + operation;
        if (reason != null && !reason.isBlank()) {
            message = message + ": " + reason;
        }
        log.warn(message);
        return new UnsupportedOperationException(message);
    }

    private record SubscriptionBinding(int processIdentifier,
                                       BacnetAddress address,
                                       boolean propertyLevel,
                                       boolean issueConfirmedNotifications) {
    }
}
