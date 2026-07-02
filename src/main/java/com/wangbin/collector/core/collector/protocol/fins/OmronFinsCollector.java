package com.wangbin.collector.core.collector.protocol.fins;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.fins.codec.FinsDataCodec;
import com.wangbin.collector.core.collector.protocol.fins.codec.FinsFrameCodec;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsByteOrder;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsConnectionConfig;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsWordOrder;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsReadPlan;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsReadPlanBuilder;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsReadPlanItem;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsWritePlan;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsWritePlanBuilder;
import com.wangbin.collector.core.collector.protocol.fins.service.FinsWritePlanItem;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
public class OmronFinsCollector extends ConnectionBackedCollector {

    private final FinsReadPlanBuilder readPlanBuilder = new FinsReadPlanBuilder();
    private final FinsWritePlanBuilder writePlanBuilder = new FinsWritePlanBuilder();
    private final Map<String, FinsAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> wordWriteLocks = new ConcurrentHashMap<>();
    private final AtomicInteger sidSequence = new AtomicInteger(1);
    private final AtomicInteger lastFallbackCount = new AtomicInteger();
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong requestSuccessCount = new AtomicLong();
    private final AtomicLong requestErrorCount = new AtomicLong();
    private final AtomicLong requestTimeoutCount = new AtomicLong();
    private final AtomicLong requestRetryCount = new AtomicLong();
    private final AtomicLong batchReadCount = new AtomicLong();
    private final AtomicLong batchWriteCount = new AtomicLong();
    private final AtomicLong batchFallbackCount = new AtomicLong();
    private final AtomicLong mergedPointCount = new AtomicLong();
    private final AtomicLong singlePointFallbackCount = new AtomicLong();

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
        resetProtocolMetrics();
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
        wordWriteLocks.clear();
        configuredReadPlans = Collections.emptyList();
        configuredReadPlanPointKeys = Collections.emptySet();
        resetProtocolMetrics();
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
        return readAddressValue(requireAddress(point));
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

        lastFallbackCount.set(0);

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
                batchFallbackCount.incrementAndGet();
                log.warn("FINS batch read fallback to single, deviceId={}, segmentKey={}", deviceInfo.getDeviceId(), plan.getSegmentKey(), e);
                for (FinsReadPlanItem item : plan.getItems()) {
                    DataPoint point = item.getPoint();
                    try {
                        requestRetryCount.incrementAndGet();
                        singlePointFallbackCount.incrementAndGet();
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
        if (address.isBitUnit()) {
            return writeProtectedBitPoint(address, value);
        }
        return writeAddressValue(address, value);
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) throws Exception {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        lastFallbackCount.set(0);

        List<PreparedWrite> preparedWrites = prepareWrites(points);
        if (preparedWrites.isEmpty()) {
            return results;
        }

        Set<String> protectedWordKeys = new LinkedHashSet<>();
        for (PreparedWrite prepared : preparedWrites) {
            if (prepared.address().isBitUnit()) {
                protectedWordKeys.add(wordLockKey(toWordContainerAddress(prepared.address())));
            }
        }

        Set<String> conflictedProtectedWordKeys = new LinkedHashSet<>();
        for (PreparedWrite prepared : preparedWrites) {
            if (prepared.address().isBitUnit()) {
                continue;
            }
            for (String wordKey : wordKeysForAddress(prepared.address())) {
                if (protectedWordKeys.contains(wordKey)) {
                    conflictedProtectedWordKeys.add(wordKey);
                }
            }
        }

        Map<DataPoint, Object> batchWordPoints = new LinkedHashMap<>();
        Map<String, List<PreparedWrite>> protectedBitGroups = new LinkedHashMap<>();
        List<PreparedWrite> sequentialWrites = new ArrayList<>();
        for (PreparedWrite prepared : preparedWrites) {
            FinsAddress address = prepared.address();
            if (address.isBitUnit()) {
                String wordKey = wordLockKey(toWordContainerAddress(address));
                if (conflictedProtectedWordKeys.contains(wordKey)) {
                    sequentialWrites.add(prepared);
                } else {
                    protectedBitGroups.computeIfAbsent(wordKey, ignored -> new ArrayList<>()).add(prepared);
                }
                continue;
            }
            if (sharesProtectedWord(address, protectedWordKeys)) {
                sequentialWrites.add(prepared);
            } else {
                batchWordPoints.put(prepared.point(), prepared.value());
            }
        }

        Map<String, Boolean> resolvedResults = new LinkedHashMap<>();
        Map<String, Object> batchWordValuesByPointKey = buildPointValueLookup(batchWordPoints);
        List<FinsWritePlan> writePlans = writePlanBuilder.build(batchWordPoints, requireFinsConfig().getMaxWordsPerRequest());
        for (FinsWritePlan writePlan : writePlans) {
            if (writePlan.getPointCount() <= 1) {
                FinsWritePlanItem item = writePlan.getItems().get(0);
                Object value = batchWordValuesByPointKey.get(resolvePointCacheKey(item.getPoint()));
                resolvedResults.put(item.getPoint().getPointId(), doWritePoint(item.getPoint(), value));
                continue;
            }
            try {
                executeWritePlan(writePlan, batchWordValuesByPointKey);
                for (FinsWritePlanItem item : writePlan.getItems()) {
                    DataPoint point = item.getPoint();
                    if (point != null && point.getPointId() != null) {
                        resolvedResults.put(point.getPointId(), true);
                    }
                }
            } catch (Exception ex) {
                batchFallbackCount.incrementAndGet();
                lastFallbackCount.incrementAndGet();
                log.warn("FINS batch write fallback to single, deviceId={}, segmentKey={}, startWord={}, unitCount={}, error={}",
                        deviceInfo.getDeviceId(),
                        writePlan.getSegmentKey(),
                        writePlan.getStartWord(),
                        writePlan.getTotalUnitCount(),
                        ex.getMessage());
                fallbackWritePlan(writePlan, batchWordValuesByPointKey, resolvedResults);
            }
        }

        for (Map.Entry<String, List<PreparedWrite>> entry : protectedBitGroups.entrySet()) {
            List<PreparedWrite> group = entry.getValue();
            if (group == null || group.isEmpty()) {
                continue;
            }
            if (group.size() == 1) {
                PreparedWrite prepared = group.get(0);
                resolvedResults.put(prepared.point().getPointId(), doWritePoint(prepared.point(), prepared.value()));
                continue;
            }
            try {
                writeProtectedBitGroup(group);
                for (PreparedWrite prepared : group) {
                    if (prepared.point().getPointId() != null) {
                        resolvedResults.put(prepared.point().getPointId(), true);
                    }
                }
            } catch (Exception ex) {
                batchFallbackCount.incrementAndGet();
                lastFallbackCount.incrementAndGet();
                log.warn("FINS protected bit group fallback to single, deviceId={}, wordKey={}, pointCount={}, error={}",
                        deviceInfo.getDeviceId(), entry.getKey(), group.size(), ex.getMessage());
                fallbackBitGroup(group, resolvedResults);
            }
        }

        for (PreparedWrite prepared : sequentialWrites) {
            if (prepared.point().getPointId() != null) {
                resolvedResults.put(prepared.point().getPointId(), doWritePoint(prepared.point(), prepared.value()));
            }
        }

        for (PreparedWrite prepared : preparedWrites) {
            String pointId = prepared.point() != null ? prepared.point().getPointId() : null;
            if (pointId != null && resolvedResults.containsKey(pointId)) {
                results.put(pointId, resolvedResults.get(pointId));
            }
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
        status.put("requestCount", requestCount.get());
        status.put("requestSuccessCount", requestSuccessCount.get());
        status.put("requestErrorCount", requestErrorCount.get());
        status.put("requestTimeoutCount", requestTimeoutCount.get());
        status.put("requestRetryCount", requestRetryCount.get());
        status.put("batchReadCount", batchReadCount.get());
        status.put("batchWriteCount", batchWriteCount.get());
        status.put("mergedPointCount", mergedPointCount.get());
        status.put("singlePointFallbackCount", singlePointFallbackCount.get());
        status.put("lastFallbackCount", lastFallbackCount.get());
        status.put("batchFallbackCount", batchFallbackCount.get());
        status.put("lastFinsEndCode", lastFinsEndCode);
        status.put("lastFinsResponseCode", lastFinsEndCode);
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
        batchReadCount.incrementAndGet();
        mergedPointCount.addAndGet(plan.getItems().size());
        lastRequestUnitCount = plan.unitCount();
        recordProtocolRequestStart();
        try {
            FinsFrameCodec.FinsResponse response = FinsFrameCodec.parseReadResponse(exchange(request), sid);
            lastFinsEndCode = response.endCode();
            if (!response.success()) {
                throw new IllegalStateException(String.format("FINS batch read end code: 0x%04X", response.endCode()));
            }
            recordProtocolRequestSuccess();
            byte[] payload = response.payload();
            Map<String, Object> results = new LinkedHashMap<>();
            for (FinsReadPlanItem item : plan.getItems()) {
                byte[] slice = new byte[item.getPayloadByteLength()];
                System.arraycopy(payload, item.getPayloadByteOffset(), slice, 0, item.getPayloadByteLength());
                results.put(item.getPoint().getPointId(), FinsDataCodec.decode(slice, item.getAddress()));
            }
            return results;
        } catch (Exception e) {
            recordProtocolRequestFailure(e);
            throw e;
        }
    }

    private void executeWritePlan(FinsWritePlan writePlan,
                                  Map<String, Object> valuesByPointKey) throws Exception {
        int sid = nextSid();
        byte[] payload = buildBatchWritePayload(writePlan, valuesByPointKey);
        byte[] request = FinsFrameCodec.buildBatchWriteRequest(requireFinsConfig(), sid,
                writePlan.getMemoryArea(), writePlan.getStartWord(), writePlan.getTotalUnitCount(), false, payload);
        batchWriteCount.incrementAndGet();
        mergedPointCount.addAndGet(writePlan.getPointCount());
        lastRequestUnitCount = writePlan.getTotalUnitCount();
        recordProtocolRequestStart();
        try {
            FinsFrameCodec.FinsResponse response = FinsFrameCodec.parseWriteResponse(exchange(request), sid);
            lastFinsEndCode = response.endCode();
            if (!response.success()) {
                throw new IllegalStateException(String.format("FINS batch write end code: 0x%04X", response.endCode()));
            }
            recordProtocolRequestSuccess();
        } catch (Exception e) {
            recordProtocolRequestFailure(e);
            throw e;
        }
    }

    private byte[] buildBatchWritePayload(FinsWritePlan writePlan,
                                          Map<String, Object> valuesByPointKey) {
        byte[] payload = new byte[writePlan.getPayloadByteLength()];
        for (FinsWritePlanItem item : writePlan.getItems()) {
            String pointKey = resolvePointCacheKey(item.getPoint());
            Object value = valuesByPointKey.get(pointKey);
            byte[] encoded = FinsDataCodec.encode(value, item.getAddress());
            System.arraycopy(encoded, 0, payload, item.getPayloadByteOffset(), encoded.length);
        }
        return payload;
    }

    private void fallbackWritePlan(FinsWritePlan writePlan,
                                   Map<String, Object> valuesByPointKey,
                                   Map<String, Boolean> results) {
        for (FinsWritePlanItem item : writePlan.getItems()) {
            DataPoint point = item.getPoint();
            if (point == null || point.getPointId() == null) {
                continue;
            }
            try {
                requestRetryCount.incrementAndGet();
                singlePointFallbackCount.incrementAndGet();
                Object value = valuesByPointKey.get(resolvePointCacheKey(point));
                results.put(point.getPointId(), doWritePoint(point, value));
            } catch (Exception singleError) {
                log.warn("FINS fallback point write failed, deviceId={}, pointId={}",
                        deviceInfo.getDeviceId(), point.getPointId(), singleError);
                results.put(point.getPointId(), false);
            }
        }
    }

    private void writeProtectedBitGroup(List<PreparedWrite> group) throws Exception {
        if (group == null || group.isEmpty()) {
            return;
        }
        FinsAddress wordAddress = toWordContainerAddress(group.get(0).address());
        ReentrantLock lock = wordWriteLocks.computeIfAbsent(wordLockKey(wordAddress), ignored -> new ReentrantLock());
        lock.lock();
        try {
            int wordValue = readWordContainerValue(wordAddress);
            for (PreparedWrite prepared : group) {
                wordValue = applyBitValue(wordValue, prepared.address().getBitOffset(), toBooleanValue(prepared.value()));
            }
            batchWriteCount.incrementAndGet();
            mergedPointCount.addAndGet(group.size());
            writeWordContainerValue(wordAddress, wordValue & 0xFFFF);
        } finally {
            lock.unlock();
        }
    }

    private void fallbackBitGroup(List<PreparedWrite> group,
                                  Map<String, Boolean> results) {
        for (PreparedWrite prepared : group) {
            DataPoint point = prepared.point();
            if (point == null || point.getPointId() == null) {
                continue;
            }
            try {
                requestRetryCount.incrementAndGet();
                singlePointFallbackCount.incrementAndGet();
                results.put(point.getPointId(), doWritePoint(point, prepared.value()));
            } catch (Exception singleError) {
                log.warn("FINS protected bit fallback write failed, deviceId={}, pointId={}",
                        deviceInfo.getDeviceId(), point.getPointId(), singleError);
                results.put(point.getPointId(), false);
            }
        }
    }

    private List<PreparedWrite> prepareWrites(Map<DataPoint, Object> points) {
        List<PreparedWrite> preparedWrites = new ArrayList<>();
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null || point.getPointId() == null) {
                continue;
            }
            preparedWrites.add(new PreparedWrite(point, requireAddress(point), entry.getValue()));
        }
        return preparedWrites;
    }

    private Map<String, Object> buildPointValueLookup(Map<DataPoint, Object> pointValues) {
        Map<String, Object> valuesByPointKey = new LinkedHashMap<>();
        for (Map.Entry<DataPoint, Object> entry : pointValues.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null) {
                continue;
            }
            valuesByPointKey.put(resolvePointCacheKey(point), entry.getValue());
        }
        return valuesByPointKey;
    }

    private boolean sharesProtectedWord(FinsAddress address, Set<String> protectedWordKeys) {
        for (String wordKey : wordKeysForAddress(address)) {
            if (protectedWordKeys.contains(wordKey)) {
                return true;
            }
        }
        return false;
    }

    private List<String> wordKeysForAddress(FinsAddress address) {
        if (address.isBitUnit()) {
            return List.of(wordLockKey(toWordContainerAddress(address)));
        }
        List<String> wordKeys = new ArrayList<>(Math.max(1, address.readUnitCount()));
        int startWord = address.getWordAddress();
        int endWordExclusive = startWord + address.readUnitCount();
        for (int word = startWord; word < endWordExclusive; word++) {
            wordKeys.add(wordLockKey(address.getMemoryArea().name(), word));
        }
        return wordKeys;
    }

    private Object readAddressValue(FinsAddress address) throws Exception {
        int sid = nextSid();
        byte[] request = FinsFrameCodec.buildReadRequest(requireFinsConfig(), sid, address);
        lastRequestUnitCount = address.readUnitCount();
        recordProtocolRequestStart();
        try {
            FinsFrameCodec.FinsResponse response = FinsFrameCodec.parseReadResponse(exchange(request), sid);
            lastFinsEndCode = response.endCode();
            if (!response.success()) {
                throw new IllegalStateException(String.format("FINS read end code: 0x%04X", response.endCode()));
            }
            recordProtocolRequestSuccess();
            return FinsDataCodec.decode(response.payload(), address);
        } catch (Exception e) {
            recordProtocolRequestFailure(e);
            throw e;
        }
    }

    private boolean writeAddressValue(FinsAddress address, Object value) throws Exception {
        int sid = nextSid();
        byte[] payload = FinsDataCodec.encode(value, address);
        byte[] request = FinsFrameCodec.buildWriteRequest(requireFinsConfig(), sid, address, payload);
        lastRequestUnitCount = address.readUnitCount();
        recordProtocolRequestStart();
        try {
            FinsFrameCodec.FinsResponse response = FinsFrameCodec.parseWriteResponse(exchange(request), sid);
            lastFinsEndCode = response.endCode();
            if (!response.success()) {
                throw new IllegalStateException(String.format("FINS write end code: 0x%04X", response.endCode()));
            }
            recordProtocolRequestSuccess();
            return true;
        } catch (Exception e) {
            recordProtocolRequestFailure(e);
            throw e;
        }
    }

    private boolean writeProtectedBitPoint(FinsAddress address, Object value) throws Exception {
        FinsAddress wordAddress = toWordContainerAddress(address);
        ReentrantLock lock = wordWriteLocks.computeIfAbsent(wordLockKey(wordAddress), ignored -> new ReentrantLock());
        lock.lock();
        try {
            int wordValue = readWordContainerValue(wordAddress);
            int updatedValue = applyBitValue(wordValue, address.getBitOffset(), toBooleanValue(value));
            writeWordContainerValue(wordAddress, updatedValue & 0xFFFF);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private int readWordContainerValue(FinsAddress wordAddress) throws Exception {
        Object rawWord = readAddressValue(wordAddress);
        return rawWord instanceof Number number
                ? number.intValue()
                : Integer.parseInt(String.valueOf(rawWord));
    }

    private void writeWordContainerValue(FinsAddress wordAddress, int value) throws Exception {
        writeAddressValue(wordAddress, value & 0xFFFF);
    }

    private FinsAddress toWordContainerAddress(FinsAddress address) {
        return new FinsAddress(
                address.getCanonicalAddress(),
                address.getMemoryArea().name() + ":" + address.getWordAddress(),
                address.getMemoryArea(),
                address.getWordAddress(),
                null,
                "UINT16",
                1,
                null,
                FinsByteOrder.BIG_ENDIAN,
                FinsWordOrder.BIG_ENDIAN
        );
    }

    private int applyBitValue(int currentWordValue, Integer bitOffset, boolean targetBit) {
        int safeBitOffset = bitOffset != null ? bitOffset : 0;
        int bitMask = 1 << safeBitOffset;
        return targetBit ? (currentWordValue | bitMask) : (currentWordValue & ~bitMask);
    }

    private boolean toBooleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        return "true".equals(normalized) || "1".equals(normalized) || "on".equals(normalized);
    }

    private String wordLockKey(FinsAddress address) {
        return wordLockKey(address.getMemoryArea().name(), address.getWordAddress());
    }

    private String wordLockKey(String memoryArea, int wordAddress) {
        return deviceInfo.getDeviceId() + ":" + memoryArea + ":" + wordAddress;
    }

    private void resetProtocolMetrics() {

        lastFallbackCount.set(0);
        requestCount.set(0);
        requestSuccessCount.set(0);
        requestErrorCount.set(0);
        requestTimeoutCount.set(0);
        requestRetryCount.set(0);
        batchReadCount.set(0);
        batchWriteCount.set(0);
        batchFallbackCount.set(0);
        mergedPointCount.set(0);
        singlePointFallbackCount.set(0);
        lastFinsEndCode = null;
        lastRequestUnitCount = null;
    }

    private void recordProtocolRequestStart() {
        requestCount.incrementAndGet();
    }

    private void recordProtocolRequestSuccess() {
        requestSuccessCount.incrementAndGet();
    }

    private void recordProtocolRequestFailure(Throwable throwable) {
        requestErrorCount.incrementAndGet();
        if (isTimeoutThrowable(throwable)) {
            requestTimeoutCount.incrementAndGet();
        }
    }

    private boolean isTimeoutThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record PreparedWrite(DataPoint point, FinsAddress address, Object value) {
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
