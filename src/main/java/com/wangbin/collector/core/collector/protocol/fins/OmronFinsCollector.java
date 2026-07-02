package com.wangbin.collector.core.collector.protocol.fins;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.fins.codec.FinsDataCodec;
import com.wangbin.collector.core.collector.protocol.fins.codec.FinsFrameCodec;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsConnectionConfig;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsReadPlan;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsReadPlanBuilder;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsReadPlanItem;
import com.wangbin.collector.core.collector.protocol.fins.util.FinsAddressParser;
import com.wangbin.collector.core.connection.adapter.OmronFinsUdpConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessContext;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class OmronFinsCollector extends ConnectionBackedCollector {

    private final FinsReadPlanBuilder readPlanBuilder = new FinsReadPlanBuilder();
    private final Map<String, FinsAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final AtomicInteger sidSequence = new AtomicInteger(1);
    private final AtomicInteger lastFallbackCount = new AtomicInteger();

    private volatile List<FinsReadPlan> configuredReadPlans = Collections.emptyList();
    private volatile Set<String> configuredReadPlanPointKeys = Collections.emptySet();
    private volatile Integer lastFinsEndCode;
    private volatile Integer lastRequestUnitCount;

    private OmronFinsUdpConnectionAdapter connectionAdapter;
    private FinsConnectionConfig finsConfig;

    @Override
    public String getCollectorType() {
        return "OMRON_FINS";
    }

    @Override
    public String getProtocolType() {
        return "OMRON_FINS";
    }

    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, OmronFinsUdpConnectionAdapter.class, "OMRON FINS");

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }
        this.finsConfig = FinsConnectionConfig.from(currentConfig);
        this.sidSequence.set(finsConfig.getServiceIdSeed() & 0xFF);
        this.lastFallbackCount.set(0);
        this.lastFinsEndCode = null;
        this.lastRequestUnitCount = null;
        this.configuredReadPlans = Collections.emptyList();
        this.configuredReadPlanPointKeys = Collections.emptySet();
        log.info("OMRON FINS collector connected, deviceId={}, host={}, port={}",
                deviceInfo.getDeviceId(), finsConfig.getHost(), finsConfig.getPort());
    }

    @Override
    protected void doDisconnect() {
        removeManagedConnection("OMRON FINS");
        connectionAdapter = null;
        finsConfig = null;
        configuredAddresses.clear();
        configuredReadPlans = Collections.emptyList();
        configuredReadPlanPointKeys = Collections.emptySet();
        lastFallbackCount.set(0);
        lastFinsEndCode = null;
        lastRequestUnitCount = null;
        log.info("OMRON FINS collector disconnected, deviceId={}", deviceInfo.getDeviceId());
    }

    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            FinsAddress address = requireAddress(point);
            Object rawValue = doReadPoint(point);
            ProcessResult processResult = address.isScalar()
                    ? buildScalarProcessResult(point, address, rawValue)
                    : buildArrayProcessResult(point, address, rawValue, "array pass-through read");
            lastProcessResults.put(point.getPointId(), processResult);
            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return processResult.getFinalValue();
        } catch (CollectorException e) {
            throw e;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            invalidateConnectionIfNeeded(e);
            throw new CollectorException("FINS point read failed", deviceInfo.getDeviceId(),
                    point != null ? point.getPointId() : null, e);
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
                    FinsAddress address = requireAddress(point);
                    Object rawValue = rawValues.get(pointId);
                    if (rawValue == null) {
                        results.put(pointId, null);
                        continue;
                    }
                    ProcessResult processResult = address.isScalar()
                            ? buildScalarProcessResult(point, address, rawValue)
                            : buildArrayProcessResult(point, address, rawValue, "array pass-through batch read");
                    lastProcessResults.put(pointId, processResult);
                    results.put(pointId, processResult.getFinalValue());
                } catch (Exception e) {
                    log.error("FINS batch point process failed, deviceId={}, pointId={}", deviceInfo.getDeviceId(), pointId, e);
                    recordException(e, point);
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
            invalidateConnectionIfNeeded(e);
            throw new CollectorException("FINS batch point read failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    @Override
    public boolean writePoint(DataPoint point, Object value) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            if (point == null || (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite()))) {
                throw new CollectorException("Point is not writable", deviceInfo.getDeviceId(), point != null ? point.getPointId() : null);
            }
            ProcessResult validation = buildWriteValidationResult(point, value);
            if (!validation.isSuccess()) {
                throw new CollectorException("FINS write quality check failed: " + validation.getMessage(),
                        deviceInfo.getDeviceId(), point.getPointId());
            }
            Object normalizedValue = normalizeWriteValue(point, value);
            boolean result = doWritePoint(point, normalizedValue);
            totalWriteCount.incrementAndGet();
            totalWriteTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return result;
        } catch (CollectorException e) {
            throw e;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            invalidateConnectionIfNeeded(e);
            throw new CollectorException("FINS point write failed", deviceInfo.getDeviceId(),
                    point != null ? point.getPointId() : null, e);
        }
    }

    @Override
    public Map<String, Boolean> writePoints(Map<DataPoint, Object> points) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        Map<String, Boolean> results = new LinkedHashMap<>();
        try {
            if (points == null || points.isEmpty()) {
                return results;
            }
            Map<DataPoint, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null || point.getPointId() == null) {
                    continue;
                }
                if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                    results.put(point.getPointId(), false);
                    continue;
                }
                ProcessResult validation = buildWriteValidationResult(point, entry.getValue());
                if (!validation.isSuccess()) {
                    results.put(point.getPointId(), false);
                    continue;
                }
                normalized.put(point, normalizeWriteValue(point, entry.getValue()));
            }
            Map<String, Boolean> writeResults = doWritePoints(normalized);
            results.putAll(writeResults);
            totalWriteCount.addAndGet(writeResults.size());
            totalWriteTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, null);
            invalidateConnectionIfNeeded(e);
            throw new CollectorException("FINS batch point write failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        FinsAddress address = requireAddress(point);
        int sid = nextSid();
        byte[] request = FinsFrameCodec.buildReadRequest(requireFinsConfig(), sid, address);
        FinsFrameCodec.FinsResponse response = FinsFrameCodec.parseReadResponse(exchange(request), sid);
        if (!response.success()) {
            throw new IllegalStateException(String.format("FINS read end code: 0x%04X", response.endCode()));
        }
        lastFinsEndCode = response.endCode();
        lastRequestUnitCount = address.readUnitCount();
        return FinsDataCodec.decode(response.payload(), address);
    }

    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        List<DataPoint> validPoints = points.stream().filter(point -> point != null && point.getPointId() != null).collect(Collectors.toList());
        if (validPoints.isEmpty()) {
            return results;
        }

        if (!requireFinsConfig().isBatchReadEnabled()) {
            for (DataPoint point : validPoints) {
                results.put(point.getPointId(), doReadPoint(point));
            }
            return results;
        }

        List<FinsReadPlan> plans = resolveReadPlans(validPoints);
        Set<String> completed = new LinkedHashSet<>();
        for (FinsReadPlan plan : plans) {
            try {
                Map<String, Object> batchResult = executePlan(plan);
                results.putAll(batchResult);
                completed.addAll(batchResult.keySet());
            } catch (Exception e) {
                lastFallbackCount.incrementAndGet();
                log.warn("FINS batch read fallback to single, deviceId={}, segmentKey={}", deviceInfo.getDeviceId(), plan.getSegmentKey(), e);
                for (FinsReadPlanItem item : plan.getItems()) {
                    DataPoint point = item.getPoint();
                    try {
                        Object value = doReadPoint(point);
                        results.put(point.getPointId(), value);
                        completed.add(point.getPointId());
                    } catch (Exception singleError) {
                        log.warn("FINS single fallback failed, deviceId={}, pointId={}", deviceInfo.getDeviceId(), point.getPointId(), singleError);
                    }
                }
            }
        }

        for (DataPoint point : validPoints) {
            if (!completed.contains(point.getPointId())) {
                results.put(point.getPointId(), doReadPoint(point));
            }
        }
        return results;
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        FinsAddress address = requireAddress(point);
        int sid = nextSid();
        byte[] payload = FinsDataCodec.encode(value, address);
        byte[] request = FinsFrameCodec.buildWriteRequest(requireFinsConfig(), sid, address, payload);
        FinsFrameCodec.FinsResponse response = FinsFrameCodec.parseWriteResponse(exchange(request), sid);
        if (!response.success()) {
            throw new IllegalStateException(String.format("FINS write end code: 0x%04X", response.endCode()));
        }
        lastFinsEndCode = response.endCode();
        lastRequestUnitCount = address.readUnitCount();
        return true;
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) throws Exception {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            boolean success = doWritePoint(point, entry.getValue());
            results.put(point.getPointId(), success);
        }
        return results;
    }

    @Override
    protected void doSubscribe(List<DataPoint> points) {
        log.info("OMRON FINS collector does not support native subscribe, deviceId={}, points={}",
                deviceInfo.getDeviceId(), points != null ? points.size() : 0);
    }

    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        log.info("OMRON FINS collector unsubscribe noop, deviceId={}", deviceInfo.getDeviceId());
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        FinsConnectionConfig config = finsConfig;
        if (config != null) {
            status.put("host", config.getHost());
            status.put("port", config.getPort());
            status.put("plcNetwork", config.getPlcNetwork());
            status.put("plcNode", config.getPlcNode());
            status.put("plcUnit", config.getPlcUnit());
            status.put("localNetwork", config.getLocalNetwork());
            status.put("localNode", config.getLocalNode());
            status.put("localUnit", config.getLocalUnit());
            status.put("batchReadEnabled", config.isBatchReadEnabled());
            status.put("maxWordsPerRequest", config.getMaxWordsPerRequest());
            status.put("maxBitsPerRequest", config.getMaxBitsPerRequest());
        }
        status.put("configuredReadPlans", configuredReadPlans.size());
        status.put("lastFallbackCount", lastFallbackCount.get());
        status.put("lastFinsEndCode", lastFinsEndCode);
        status.put("lastRequestUnitCount", lastRequestUnitCount);
        status.put("connected", connectionAdapter != null && connectionAdapter.isConnected());
        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
        throw new UnsupportedOperationException("OMRON FINS executeCommand is not implemented in P0");
    }

    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        configuredAddresses.clear();
        if (points == null || points.isEmpty()) {
            configuredReadPlans = Collections.emptyList();
            configuredReadPlanPointKeys = Collections.emptySet();
            return;
        }
        List<DataPoint> validPoints = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null || !point.isEnabled()) {
                continue;
            }
            FinsAddress address = FinsAddressParser.parse(point);
            configuredAddresses.put(resolvePointCacheKey(point), address);
            if (point.getPointId() != null) {
                configuredAddresses.put(point.getPointId(), address);
            }
            validPoints.add(point);
        }
        FinsConnectionConfig config = finsConfig != null ? finsConfig : FinsConnectionConfig.from(requireConnectionConfig());
        configuredReadPlans = readPlanBuilder.build(validPoints, config.getMaxWordsPerRequest(), config.getMaxBitsPerRequest());
        configuredReadPlanPointKeys = validPoints.stream().map(this::resolvePointCacheKey).collect(Collectors.toUnmodifiableSet());
        log.info("OMRON FINS read plans rebuilt, deviceId={}, plans={}, points={}",
                deviceId, configuredReadPlans.size(), validPoints.size());
    }

    private FinsAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        String cacheKey = resolvePointCacheKey(point);
        return configuredAddresses.computeIfAbsent(cacheKey, key -> FinsAddressParser.parse(point));
    }

    private FinsConnectionConfig requireFinsConfig() {
        if (finsConfig == null) {
            throw new IllegalStateException("FINS connection config is not initialized");
        }
        return finsConfig;
    }

    private Map<String, Object> executePlan(FinsReadPlan plan) throws Exception {
        int sid = nextSid();
        byte[] request = FinsFrameCodec.buildBatchReadRequest(requireFinsConfig(), sid,
                plan.getMemoryArea(), plan.getStartWord(), plan.unitCount(), plan.isBitUnit());
        FinsFrameCodec.FinsResponse response = FinsFrameCodec.parseReadResponse(exchange(request), sid);
        if (!response.success()) {
            throw new IllegalStateException(String.format("FINS batch read end code: 0x%04X", response.endCode()));
        }
        lastFinsEndCode = response.endCode();
        lastRequestUnitCount = plan.unitCount();
        byte[] payload = response.payload();
        Map<String, Object> results = new LinkedHashMap<>();
        for (FinsReadPlanItem item : plan.getItems()) {
            byte[] slice = new byte[item.getPayloadByteLength()];
            System.arraycopy(payload, item.getPayloadByteOffset(), slice, 0, item.getPayloadByteLength());
            results.put(item.getPoint().getPointId(), FinsDataCodec.decode(slice, item.getAddress()));
        }
        return results;
    }

    private byte[] exchange(byte[] request) throws Exception {
        OmronFinsUdpConnectionAdapter adapter = connectionAdapter;
        if (adapter == null) {
            throw new IllegalStateException("OMRON FINS connection adapter is not initialized");
        }
        return adapter.exchange(request, requireFinsConfig().getTimeoutMs());
    }

    private List<FinsReadPlan> resolveReadPlans(List<DataPoint> points) {
        if (points.isEmpty()) {
            return Collections.emptyList();
        }
        if (matchesConfiguredPoints(points)) {
            return configuredReadPlans;
        }
        return readPlanBuilder.build(points,
                requireFinsConfig().getMaxWordsPerRequest(),
                requireFinsConfig().getMaxBitsPerRequest());
    }

    private boolean matchesConfiguredPoints(List<DataPoint> points) {
        if (configuredReadPlans.isEmpty() || configuredReadPlanPointKeys.isEmpty() || points.size() != configuredReadPlanPointKeys.size()) {
            return false;
        }
        for (DataPoint point : points) {
            if (!configuredReadPlanPointKeys.contains(resolvePointCacheKey(point))) {
                return false;
            }
        }
        return true;
    }

    private ProcessResult buildScalarProcessResult(DataPoint point, FinsAddress address, Object rawValue) {
        Object processedValue = normalizeReadValue(point, rawValue);
        ProcessContext context = new ProcessContext();
        context.addAttribute("deviceId", deviceInfo.getDeviceId());
        ProcessResult processResult = dataQualityProcessor.process(context, point, processedValue);
        processResult.addMetadata("address", address.getCanonicalAddress());
        processResult.addMetadata("processingMode", address.isStringType() ? "protocol_string_passthrough" : "protocol_scalar_normalized");
        return processResult;
    }

    private ProcessResult buildArrayProcessResult(DataPoint point,
                                                  FinsAddress address,
                                                  Object rawValue,
                                                  String message) {
        if (!(rawValue instanceof Collection<?>) && !(rawValue != null && rawValue.getClass().isArray())) {
            throw new IllegalArgumentException("FINS array point did not produce collection payload: " + point.getPointId());
        }
        ProcessResult processResult = ProcessResult.success(rawValue, rawValue, message);
        processResult.addMetadata("arrayValue", true);
        processResult.addMetadata("arraySize", address.getElementCount());
        processResult.addMetadata("address", address.getCanonicalAddress());
        processResult.addMetadata("processingMode", "protocol_passthrough");
        return processResult;
    }

    private ProcessResult buildWriteValidationResult(DataPoint point, Object value) {
        ProcessContext context = new ProcessContext();
        context.addAttribute("deviceId", deviceInfo.getDeviceId());
        return dataQualityProcessor.process(context, point, value);
    }

    private Object normalizeReadValue(DataPoint point, Object rawValue) {
        if (rawValue instanceof Number number && (point.getScalingFactor() != null || point.getOffset() != null)) {
            return point.getActualValue(number.doubleValue());
        }
        return rawValue;
    }

    private Object normalizeWriteValue(DataPoint point, Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number) && !(value instanceof Boolean)) {
            return value;
        }
        double raw = value instanceof Boolean bool ? (bool ? 1.0 : 0.0) : ((Number) value).doubleValue();
        if (point.getScalingFactor() != null && point.getScalingFactor() != 0) {
            raw = raw / point.getScalingFactor();
        }
        if (point.getOffset() != null) {
            raw = raw - point.getOffset();
        }
        return raw;
    }

    private int nextSid() {
        return sidSequence.getAndUpdate(current -> (current + 1) & 0xFF);
    }

    private void invalidateConnectionIfNeeded(Throwable throwable) {
        if (throwable instanceof SocketTimeoutException || throwable instanceof SocketException) {
            log.warn("Invalidate OMRON FINS connection after transport failure, deviceId={}", deviceInfo.getDeviceId(), throwable);
            connected = false;
            connectionStatus = "ERROR";
        }
    }
}