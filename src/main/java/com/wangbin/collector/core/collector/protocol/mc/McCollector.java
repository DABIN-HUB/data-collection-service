package com.wangbin.collector.core.collector.protocol.mc;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.mc.codec.Mc3eBinaryFrameCodec;
import com.wangbin.collector.core.collector.protocol.mc.codec.Mc3eAsciiFrameCodec;
import com.wangbin.collector.core.collector.protocol.mc.codec.Mc4eBinaryFrameCodec;
import com.wangbin.collector.core.collector.protocol.mc.codec.McFrameCodec;
import com.wangbin.collector.core.collector.protocol.mc.codec.McFrameBuilder;
import com.wangbin.collector.core.collector.protocol.mc.codec.McRandomReadRequest;
import com.wangbin.collector.core.collector.protocol.mc.codec.McRandomWriteItem;
import com.wangbin.collector.core.collector.protocol.mc.codec.McRandomWriteRequest;
import com.wangbin.collector.core.collector.protocol.mc.codec.McResponseParser;
import com.wangbin.collector.core.collector.protocol.mc.codec.UnsupportedMcFrameCodec;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDriverType;
import com.wangbin.collector.core.collector.protocol.mc.plan.McReadPlan;
import com.wangbin.collector.core.collector.protocol.mc.plan.McReadPlanBuilder;
import com.wangbin.collector.core.collector.protocol.mc.plan.McReadPlanItem;
import com.wangbin.collector.core.collector.protocol.mc.plan.McWritePlan;
import com.wangbin.collector.core.collector.protocol.mc.plan.McWritePlanBuilder;
import com.wangbin.collector.core.collector.protocol.mc.plan.McWritePlanItem;
import com.wangbin.collector.core.collector.protocol.mc.util.McAddressParser;
import com.wangbin.collector.core.collector.protocol.mc.util.McByteCodec;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.MitsubishiMcConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessContext;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class McCollector extends ConnectionBackedCollector {
    private DevicePointResolver devicePointResolver;

    /**
     * 注入点位解析辅助组件。
     */
    @Autowired(required = false)
    public void setDevicePointResolver(DevicePointResolver devicePointResolver) {
        this.devicePointResolver = devicePointResolver;
    }

    private final McReadPlanBuilder readPlanBuilder = new McReadPlanBuilder();
    private final McWritePlanBuilder writePlanBuilder = new McWritePlanBuilder();
    private final Map<String, McAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final McFrameCodec defaultFrameCodec = new Mc3eBinaryFrameCodec();
    private final McFrameCodec asciiFrameCodec = new Mc3eAsciiFrameCodec();
    private final McFrameCodec binary4eFrameCodec = new Mc4eBinaryFrameCodec();
    private final Map<String, ReentrantLock> wordWriteLocks = new ConcurrentHashMap<>();

    private MitsubishiMcConnectionAdapter connectionAdapter;
    private volatile List<McReadPlan> configuredReadPlans = Collections.emptyList();
    private volatile Set<String> configuredReadPlanPointKeys = Collections.emptySet();
    private final AtomicInteger lastFallbackCount = new AtomicInteger();
    private volatile Integer lastMcEndCode;
    private volatile Integer lastRequestUnitCount;
    private boolean randomReadEnabled;
    private boolean randomWriteEnabled;
    private int maxRandomReadPoints = 8;
    private int maxRandomWritePoints = 8;
    private int timeout = 5000;
    private int maxWordsPerRequest = 120;
    private int maxBitsPerRequest = 256;

    @Override
    public String getCollectorType() {
        return "MITSUBISHI_MC";
    }

    @Override
    public String getProtocolType() {
        return "MITSUBISHI_MC";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, MitsubishiMcConnectionAdapter.class, "Mitsubishi MC");

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }

        Integer configuredTimeout = currentConfig.getReadTimeout() != null
                ? currentConfig.getReadTimeout()
                : currentConfig.getTimeout();
        this.timeout = configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : 5000;
        this.maxWordsPerRequest = Math.max(1, currentConfig.getInt("maxWordsPerRequest", 120));
        this.maxBitsPerRequest = Math.max(1, currentConfig.getInt("maxBitsPerRequest", 256));
        this.randomReadEnabled = Boolean.TRUE.equals(currentConfig.getBool("randomReadEnabled", false));
        this.maxRandomReadPoints = Math.max(1, currentConfig.getInt("maxRandomReadPoints", 8));
        this.randomWriteEnabled = Boolean.TRUE.equals(currentConfig.getBool("randomWriteEnabled", false));
        this.maxRandomWritePoints = Math.max(1, currentConfig.getInt("maxRandomWritePoints", 8));
        this.configuredReadPlans = Collections.emptyList();
        this.configuredReadPlanPointKeys = Collections.emptySet();
        this.lastFallbackCount.set(0);
        this.lastMcEndCode = null;
        this.lastRequestUnitCount = null;
        log.info("Mitsubishi MC 采集器 已连接, 设备={}, 超时={}, 单次最大字数={}, 单次最大位数={}",
                deviceInfo.getDeviceId(), timeout, maxWordsPerRequest, maxBitsPerRequest);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeManagedConnection("Mitsubishi MC");
        connectionAdapter = null;
        configuredAddresses.clear();
        configuredReadPlans = Collections.emptyList();
        configuredReadPlanPointKeys = Collections.emptySet();
        wordWriteLocks.clear();
        lastFallbackCount.set(0);
        lastMcEndCode = null;
        lastRequestUnitCount = null;
        log.info("Mitsubishi MC 采集器 已断开, 设备={}", deviceInfo.getDeviceId());
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            McAddress address = requireAddress(point);
            Object rawValue = doReadPoint(point);
            ProcessResult processResult = address.isScalar()
                    ? buildScalarProcessResult(point, address, rawValue)
                    : buildArrayReadProcessResult(point, address, rawValue, "array pass-through read");
            lastProcessResults.put(point.getPointId(), processResult);
            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return processResult.getFinalValue();
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            throw new CollectorException("MC array point read failed", deviceInfo.getDeviceId(),
                    point != null ? point.getPointId() : null, e);
        }
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        Map<String, Object> results = new LinkedHashMap<>();
        try {
            List<DataPoint> validPoints = points == null
                    ? Collections.emptyList()
                    : points.stream()
                    .filter(point -> point != null && point.isEnabled())
                    .collect(Collectors.toList());
            if (validPoints.isEmpty()) {
                return results;
            }

            Map<String, Object> rawValues = readPointsByPlan(validPoints);
            for (DataPoint point : validPoints) {
                String pointId = point.getPointId();
                if (pointId == null) {
                    continue;
                }
                try {
                    McAddress address = requireAddress(point);
                    Object rawValue = rawValues.get(pointId);
                    if (rawValue == null) {
                        results.put(pointId, null);
                        continue;
                    }
                    if (address.isScalar()) {
                        ProcessResult processResult = buildScalarProcessResult(point, address, rawValue);
                        lastProcessResults.put(pointId, processResult);
                        if (!processResult.isSuccess()) {
                            log.warn("MC 数据质量检查失败 {}.{}, 原因:{}",
                                    deviceInfo.getDeviceId(), point.getPointName(), processResult.getMessage());
                        }
                        results.put(pointId, processResult.getFinalValue());
                    } else {
                        ProcessResult processResult = buildArrayReadProcessResult(point, address, rawValue,
                                "array pass-through batch read");
                        lastProcessResults.put(pointId, processResult);
                        results.put(pointId, processResult.getFinalValue());
                    }
                } catch (Exception e) {
                    log.error("MC 批量 点位 处理失败, 设备={}, 点位={}",
                            deviceInfo.getDeviceId(), pointId, e);
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
            throw new CollectorException("MC batch point read failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public boolean writePoint(DataPoint point, Object value) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                throw new CollectorException("Point is not writable", deviceInfo.getDeviceId(), point.getPointId());
            }
            McAddress address = requireAddress(point);
            ProcessResult processResult = buildWriteValidationResult(point, address, value);
            if (!processResult.isSuccess()) {
                throw new CollectorException("MC write quality check failed: " + processResult.getMessage(),
                        deviceInfo.getDeviceId(), point.getPointId());
            }
            Object writeValue = normalizeWriteValue(point, address, value);
            boolean result = doWritePoint(point, writeValue);
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
            throw new CollectorException("MC array point write failed", deviceInfo.getDeviceId(),
                    point != null ? point.getPointId() : null, e);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public Map<String, Boolean> writePoints(Map<DataPoint, Object> points) throws CollectorException {
        checkConnection();

        long startTime = System.currentTimeMillis();
        Map<String, Boolean> results = new LinkedHashMap<>();
        try {
            Map<DataPoint, Object> normalizedValues = new LinkedHashMap<>();
            if (points != null) {
                for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                    DataPoint point = entry.getKey();
                    if (point == null || point.getPointId() == null) {
                        continue;
                    }
                    try {
                        if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                            results.put(point.getPointId(), false);
                            continue;
                        }
                        McAddress address = requireAddress(point);
                        ProcessResult processResult = buildWriteValidationResult(point, address, entry.getValue());
                        if (!processResult.isSuccess()) {
                            results.put(point.getPointId(), false);
                            continue;
                        }
                        normalizedValues.put(point, normalizeWriteValue(point, address, entry.getValue()));
                        results.put(point.getPointId(), true);
                    } catch (Exception ex) {
                        log.error("MC 批量 写入 pre处理失败, 点位={}", point.getPointId(), ex);
                        recordException(ex, point);
                        results.put(point.getPointId(), false);
                    }
                }
            }
            if (!normalizedValues.isEmpty()) {
                results.putAll(doWritePoints(normalizedValues));
            }
            totalWriteCount.addAndGet(normalizedValues.size());
            totalWriteTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, null);
            throw new CollectorException("MC batch point write failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        McAddress address = requireAddress(point);
        if (address.hasBitOffset()) {
            return readBitOffsetPoint(address);
        }
        validateRequestCapacity(address);
        lastRequestUnitCount = address.getReadUnitCount();
        McFrameCodec frameCodec = resolveFrameCodec(requireRuntimeConnectionConfig());
        byte[] request = frameCodec.buildBatchRead(address, requireRuntimeConnectionConfig());
        byte[] response = exchange(frameCodec, request);
        lastMcEndCode = frameCodec.readEndCode(response);
        byte[] payload = frameCodec.normalizeReadPayload(address, frameCodec.parseReadPayload(response));
        return McByteCodec.decode(address, payload);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        return readPointsByPlan(points);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        McAddress address = requireAddress(point);
        if (address.hasBitOffset()) {
            return writeBitOffsetPoint(address, value);
        }
        validateRequestCapacity(address);
        lastRequestUnitCount = address.getReadUnitCount();
        byte[] payload = McByteCodec.encode(address, value);
        McFrameCodec frameCodec = resolveFrameCodec(requireRuntimeConnectionConfig());
        byte[] request = frameCodec.buildBatchWrite(address, frameCodec.normalizeWritePayload(address, payload), requireRuntimeConnectionConfig());
        byte[] response = exchange(frameCodec, request);
        lastMcEndCode = frameCodec.readEndCode(response);
        frameCodec.ensureWriteSuccess(response);
        return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }
        lastFallbackCount.set(0);
        if (shouldUseRandomWrite(points) && executeRandomWrite(points, results)) {
            return results;
        }
        Map<String, Object> valuesByPointKey = buildPointValueLookup(points);
        for (McWritePlan writePlan : planWritePoints(points)) {
            try {
                executeBatchWritePlan(writePlan, valuesByPointKey);
                for (McWritePlanItem item : writePlan.getItems()) {
                    DataPoint point = item.getPoint();
                    if (point != null && point.getPointId() != null) {
                        results.put(point.getPointId(), true);
                    }
                }
            } catch (Exception ex) {
                log.warn("MC 批量 写入 plan 失败, 降级到 单点写入, 设备={}, 分段键={}, 启动={}, 单元数量={}, 错误={}",
                        deviceInfo.getDeviceId(),
                        writePlan.getSegmentKey(),
                        writePlan.getStartDeviceNumber(),
                        writePlan.getTotalUnitCount(),
                        ex.getMessage());
                fallbackWritePlan(writePlan, valuesByPointKey, results);
            }
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSubscribe(List<DataPoint> points) {
        throw unsupported("subscribe", "Mitsubishi MC P0 only supports polling");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        configuredAddresses.clear();
        configuredReadPlans = Collections.emptyList();
        configuredReadPlanPointKeys = Collections.emptySet();
        lastFallbackCount.set(0);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.PROTOCOL, getProtocolType());
        status.put(CommonMapKeys.DRIVER, "SELF_IMPLEMENTED");
        status.put("implemented", true);
        status.put(CommonMapKeys.WRITABLE, true);
        status.put(CommonMapKeys.SUBSCRIBABLE, false);
        status.put(CommonMapKeys.TRANSPORT, "TCP");
        status.put("frame", resolveFrameCodec(getCurrentConnectionConfig()).frameType());
        status.put(CommonMapKeys.IS_CONNECTED, isConnected());
        status.put(CommonMapKeys.CONFIGURED_POINT_COUNT, configuredAddresses.size());
        status.put(CommonMapKeys.PLANNED_READ_BATCH_COUNT, configuredReadPlans.size());
        status.put("configuredReadBatchCount", configuredReadPlans.size());
        status.put("maxWordsPerRequest", maxWordsPerRequest);
        status.put("maxBitsPerRequest", maxBitsPerRequest);
        status.put("randomReadEnabled", randomReadEnabled);
        status.put("maxRandomReadPoints", maxRandomReadPoints);
        status.put("randomWriteEnabled", randomWriteEnabled);
        status.put("maxRandomWritePoints", maxRandomWritePoints);
        status.put("lastFallbackCount", lastFallbackCount.get());
        status.put("lastMcEndCode", lastMcEndCode);
        status.put("lastRequestUnitCount", lastRequestUnitCount);

        DeviceConnection connection = getCurrentConnectionConfig();
        if (connection != null) {
            status.put(CommonMapKeys.HOST, connection.getHost());
            status.put(CommonMapKeys.PORT, connection.getPort());
            status.put("networkNo", connection.getInt("networkNo", 0));
            status.put("pcNo", connection.getInt("pcNo", 255));
            status.put("ioNo", connection.getInt("ioNo", 1023));
            status.put("stationNo", connection.getInt("stationNo", 0));
            status.put("monitoringTimer", connection.getInt("monitoringTimer", 16));
            status.put(CommonMapKeys.TIMEOUT, connection.getReadTimeout() != null ? connection.getReadTimeout() : connection.getTimeout());
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
            default -> throw new IllegalArgumentException("Unsupported Mitsubishi MC command: " + command);
        };
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        List<DataPoint> readablePoints = cacheAddresses(points);
        configuredReadPlans = planReadPoints(readablePoints);
        configuredReadPlanPointKeys = Collections.unmodifiableSet(buildPointKeySet(readablePoints));
    }

    /**
     * 查询并返回业务数据。
     */
    private Map<String, Object> readPointsByPlan(List<DataPoint> points) {
        Map<String, Object> results = new LinkedHashMap<>();
        List<DataPoint> readablePoints = filterPlanPoints(points);
        for (DataPoint point : points != null ? points : Collections.<DataPoint>emptyList()) {
            if (point != null && point.getPointId() != null && !readablePoints.contains(point)) {
                results.put(point.getPointId(), null);
            }
        }
        if (shouldUseRandomRead(readablePoints)) {
            executeRandomRead(readablePoints, results);
            return results;
        }
        List<McReadPlan> readPlans = resolveReadPlans(readablePoints);
        if (log.isDebugEnabled()) {
            log.debug("MC 读取 by plan, 设备={}, 计划数量={}, 点位数量={}",
                    deviceInfo.getDeviceId(), readPlans.size(), readablePoints.size());
        }
        for (McReadPlan readPlan : readPlans) {
            executeReadPlan(readPlan, results);
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldUseRandomRead(List<DataPoint> points) {
        if (!randomReadEnabled || points == null || points.isEmpty() || points.size() > maxRandomReadPoints) {
            return false;
        }
        List<McAddress> addresses = new ArrayList<>(points.size());
        for (DataPoint point : points) {
            McAddress address = requireAddress(point);
            if (!isRandomReadableAddress(address)) {
                return false;
            }
            addresses.add(address);
        }
        addresses.sort((left, right) -> Integer.compare(left.getDeviceNumber(), right.getDeviceNumber()));
        boolean hasGap = false;
        for (int i = 1; i < addresses.size(); i++) {
            McAddress previous = addresses.get(i - 1);
            McAddress current = addresses.get(i);
            if (previous.getDeviceCode() != current.getDeviceCode()) {
                return false;
            }
            if (current.getDeviceNumber() - previous.getDeviceNumber() > previous.getReadUnitCount()) {
                hasGap = true;
            }
        }
        return hasGap;
    }

    private boolean isRandomReadableAddress(McAddress address) {
        return address != null
                && !address.hasBitOffset()
                && address.isScalar()
                && address.isWordDevice()
                && address.getDriverType().isNumericType()
                && address.getDriverType().getWordLength() == 1;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldUseRandomWrite(Map<DataPoint, Object> points) {
        if (!randomWriteEnabled || points == null || points.isEmpty() || points.size() > maxRandomWritePoints) {
            return false;
        }
        List<McAddress> addresses = new ArrayList<>(points.size());
        for (DataPoint point : points.keySet()) {
            McAddress address = requireAddress(point);
            if (!isRandomReadableAddress(address)) {
                return false;
            }
            addresses.add(address);
        }
        addresses.sort((left, right) -> Integer.compare(left.getDeviceNumber(), right.getDeviceNumber()));
        boolean hasGap = false;
        for (int i = 1; i < addresses.size(); i++) {
            McAddress previous = addresses.get(i - 1);
            McAddress current = addresses.get(i);
            if (previous.getDeviceCode() != current.getDeviceCode()) {
                return false;
            }
            if (current.getDeviceNumber() - previous.getDeviceNumber() > previous.getReadUnitCount()) {
                hasGap = true;
            }
        }
        return hasGap;
    }

    /**
     * 处理当前业务流程。
     */
    private void executeRandomRead(List<DataPoint> points, Map<String, Object> results) {
        try {
            List<McAddress> addresses = new ArrayList<>(points.size());
            for (DataPoint point : points) {
                addresses.add(requireAddress(point));
            }
            McFrameCodec frameCodec = resolveFrameCodec(requireRuntimeConnectionConfig());
            McRandomReadRequest requestModel = new McRandomReadRequest(addresses);
            byte[] request = frameCodec.buildRandomRead(requestModel, requireRuntimeConnectionConfig());
            lastRequestUnitCount = requestModel.getWordAddressCount();
            byte[] response = exchange(frameCodec, request);
            lastMcEndCode = frameCodec.readEndCode(response);
            byte[] payload = frameCodec.parseReadPayload(response);
            int offset = 0;
            for (DataPoint point : points) {
                McAddress address = requireAddress(point);
                int rawLength = frameCodec.rawReadPayloadLength(address);
                byte[] slice = Arrays.copyOfRange(payload, offset, offset + rawLength);
                results.put(point.getPointId(), McByteCodec.decode(address, frameCodec.normalizeReadPayload(address, slice)));
                offset += rawLength;
            }
            if (log.isDebugEnabled()) {
                log.debug("MC 随机读取完成, 设备={}, 点位数量={}", deviceInfo.getDeviceId(), points.size());
            }
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                throw new IllegalStateException("MC random read failed", ex);
            }
            log.warn("MC 随机读取失败, 降级到 计划读取, 设备={}, 错误={}",
                    deviceInfo.getDeviceId(), ex.getMessage());
            lastFallbackCount.incrementAndGet();
            for (McReadPlan readPlan : resolveReadPlans(points)) {
                executeReadPlan(readPlan, results);
            }
        }
    }

    /**
     * 处理当前业务流程。
     */
    private boolean executeRandomWrite(Map<DataPoint, Object> points, Map<String, Boolean> results) {
        try {
            McFrameCodec frameCodec = resolveFrameCodec(requireRuntimeConnectionConfig());
            List<McRandomWriteItem> items = new ArrayList<>(points.size());
            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                McAddress address = requireAddress(entry.getKey());
                byte[] normalized = McByteCodec.encode(address, entry.getValue());
                items.add(new McRandomWriteItem(address, frameCodec.normalizeWritePayload(address, normalized)));
            }
            McRandomWriteRequest requestModel = new McRandomWriteRequest(items);
            byte[] request = frameCodec.buildRandomWrite(requestModel, requireRuntimeConnectionConfig());
            lastRequestUnitCount = requestModel.getWordItemCount();
            byte[] response = exchange(frameCodec, request);
            lastMcEndCode = frameCodec.readEndCode(response);
            frameCodec.ensureWriteSuccess(response);
            for (DataPoint point : points.keySet()) {
                if (point != null && point.getPointId() != null) {
                    results.put(point.getPointId(), true);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("MC 随机写入完成, 设备={}, 点位数量={}", deviceInfo.getDeviceId(), points.size());
            }
            return true;
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                throw new IllegalStateException("MC random write failed", ex);
            }
            log.warn("MC 随机写入失败, 降级到 计划写入, 设备={}, 错误={}",
                    deviceInfo.getDeviceId(), ex.getMessage());
            lastFallbackCount.incrementAndGet();
            return false;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private List<McReadPlan> resolveReadPlans(List<DataPoint> readablePoints) {
        if (readablePoints == null || readablePoints.isEmpty()) {
            return Collections.emptyList();
        }

        List<McReadPlan> matchingConfiguredPlans = selectConfiguredReadPlans(readablePoints);
        if (!matchingConfiguredPlans.isEmpty()) {
            return matchingConfiguredPlans;
        }
        return planReadPoints(readablePoints);
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<DataPoint> filterPlanPoints(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        List<DataPoint> readablePoints = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null || point.getPointId() == null) {
                continue;
            }
            if (safeResolveAddress(point) != null) {
                readablePoints.add(point);
            }
        }
        return readablePoints;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<McReadPlan> planReadPoints(List<DataPoint> points) {
        return readPlanBuilder.build(points, maxWordsPerRequest, maxBitsPerRequest);
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<McReadPlan> selectConfiguredReadPlans(List<DataPoint> readablePoints) {
        List<McReadPlan> plansSnapshot = configuredReadPlans;
        Set<String> configuredPointKeysSnapshot = configuredReadPlanPointKeys;
        if (plansSnapshot.isEmpty() || configuredPointKeysSnapshot.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> requestedPointKeys = buildPointKeySet(readablePoints);
        if (requestedPointKeys.isEmpty() || !configuredPointKeysSnapshot.containsAll(requestedPointKeys)) {
            return Collections.emptyList();
        }

        List<McReadPlan> selectedPlans = new ArrayList<>();
        Set<String> coveredPointKeys = new HashSet<>();
        for (McReadPlan plan : plansSnapshot) {
            List<McReadPlanItem> matchedItems = new ArrayList<>();
            for (McReadPlanItem item : plan.getItems()) {
                DataPoint point = item.getPoint();
                String pointKey = point != null ? resolvePointCacheKey(point) : null;
                if (pointKey != null && requestedPointKeys.contains(pointKey)) {
                    matchedItems.add(item);
                    coveredPointKeys.add(pointKey);
                }
            }
            if (!matchedItems.isEmpty()) {
                selectedPlans.add(rebuildPlanWithItems(plan, matchedItems));
            }
        }

        return coveredPointKeys.containsAll(requestedPointKeys) ? selectedPlans : Collections.emptyList();
    }

    /**
     * 执行当前业务逻辑。
     */
    private McReadPlan rebuildPlanWithItems(McReadPlan sourcePlan, List<McReadPlanItem> matchedItems) {
        int minOffset = matchedItems.stream()
                .mapToInt(McReadPlanItem::getUnitOffset)
                .min()
                .orElse(0);
        int maxUnitExclusive = matchedItems.stream()
                .mapToInt(item -> item.getUnitOffset() + item.getUnitCount())
                .max()
                .orElse(minOffset);
        int startDeviceNumber = sourcePlan.getStartDeviceNumber() + minOffset;
        int endDeviceNumberExclusive = sourcePlan.getStartDeviceNumber() + maxUnitExclusive;

        List<McReadPlanItem> normalizedItems = new ArrayList<>(matchedItems.size());
        for (McReadPlanItem item : matchedItems) {
            int normalizedUnitOffset = item.getUnitOffset() - minOffset;
            int normalizedPayloadByteOffset = sourcePlan.isBitUnit()
                    ? 0
                    : normalizedUnitOffset * 2;
            normalizedItems.add(new McReadPlanItem(
                    item.getPoint(),
                    item.getAddress(),
                    normalizedUnitOffset,
                    item.getUnitCount(),
                    normalizedPayloadByteOffset,
                    item.getPayloadByteLength()
            ));
        }

        return new McReadPlan(
                buildSegmentKey(sourcePlan.getDeviceCode(), startDeviceNumber, endDeviceNumberExclusive),
                sourcePlan.getDeviceCode(),
                sourcePlan.isBitUnit(),
                startDeviceNumber,
                endDeviceNumberExclusive,
                normalizedItems
        );
    }

    /**
     * 处理当前业务流程。
     */
    private void executeReadPlan(McReadPlan readPlan, Map<String, Object> results) {
        List<DataPoint> points = readPlan != null ? readPlan.getPoints() : Collections.emptyList();
        if (points.isEmpty()) {
            return;
        }
        try {
            byte[] payload = executeBatchReadPayload(readPlan);
            populateBatchReadResults(readPlan, results, payload);
        } catch (Exception ex) {
            log.warn("MC 批量 读取 plan 失败, 降级到 单点读取, 设备={}, 分段键={}, 启动={}, 单元数量={}, 错误={}",
                    deviceInfo.getDeviceId(),
                    readPlan.getSegmentKey(),
                    readPlan.getStartDeviceNumber(),
                    readPlan.getTotalUnitCount(),
                    ex.getMessage());
            fallbackReadPlan(points, results);
        }
    }

    /**
     * 处理当前业务流程。
     */
    protected byte[] executeBatchReadPayload(McReadPlan readPlan) throws Exception {
        McAddress batchAddress = buildBatchAddress(readPlan);
        validateRequestCapacity(batchAddress);
        lastRequestUnitCount = batchAddress.getReadUnitCount();
        if (log.isDebugEnabled()) {
            log.debug("MC 执行批量读取, 设备={}, 分段键={}, 启动={}, 单元数={}",
                    deviceInfo.getDeviceId(), readPlan.getSegmentKey(),
                    readPlan.getStartDeviceNumber(), batchAddress.getReadUnitCount());
        }
        McFrameCodec frameCodec = resolveFrameCodec(requireRuntimeConnectionConfig());
        byte[] request = frameCodec.buildBatchRead(batchAddress, requireRuntimeConnectionConfig());
        byte[] response = exchange(frameCodec, request);
        lastMcEndCode = frameCodec.readEndCode(response);
        return frameCodec.normalizeReadPayload(batchAddress, frameCodec.parseReadPayload(response));
    }

    /**
     * 执行当前业务逻辑。
     */
    protected void populateBatchReadResults(McReadPlan readPlan,
                                            Map<String, Object> results,
                                            byte[] payload) {
        if (readPlan.isBitUnit()) {
            List<Boolean> bitValues = decodeBitPlanValues(readPlan, payload);
            for (McReadPlanItem item : readPlan.getItems()) {
                DataPoint point = item.getPoint();
                if (point == null || point.getPointId() == null) {
                    continue;
                }
                int start = item.getUnitOffset();
                int end = start + item.getUnitCount();
                Object rawValue = item.getAddress().isScalar()
                        ? bitValues.get(start)
                        : new ArrayList<>(bitValues.subList(start, end));
                results.put(point.getPointId(), rawValue);
            }
            return;
        }

        for (McReadPlanItem item : readPlan.getItems()) {
            DataPoint point = item.getPoint();
            if (point == null || point.getPointId() == null) {
                continue;
            }
            int start = item.getPayloadByteOffset();
            int end = start + item.getPayloadByteLength();
            byte[] slice = Arrays.copyOfRange(payload, start, end);
            results.put(point.getPointId(), McByteCodec.decode(item.getAddress(), slice));
        }
    }

    /**
     * 处理当前业务流程。
     */
    protected void executeBatchWritePlan(McWritePlan writePlan,
                                         Map<String, Object> valuesByPointKey) throws Exception {
        McAddress batchAddress = buildBatchAddress(writePlan);
        validateRequestCapacity(batchAddress);
        lastRequestUnitCount = batchAddress.getReadUnitCount();
        if (log.isDebugEnabled()) {
            log.debug("MC 执行批量写入, 设备={}, 分段键={}, 启动={}, 单元数={}",
                    deviceInfo.getDeviceId(), writePlan.getSegmentKey(),
                    writePlan.getStartDeviceNumber(), batchAddress.getReadUnitCount());
        }
        byte[] payload = buildBatchWritePayload(writePlan, valuesByPointKey);
        McFrameCodec frameCodec = resolveFrameCodec(requireRuntimeConnectionConfig());
        byte[] request = frameCodec.buildBatchWrite(batchAddress,
                frameCodec.normalizeWritePayload(batchAddress, payload),
                requireRuntimeConnectionConfig());
        byte[] response = exchange(frameCodec, request);
        lastMcEndCode = frameCodec.readEndCode(response);
        frameCodec.ensureWriteSuccess(response);
    }

    /**
     * 解析或转换业务数据。
     */
    private List<Boolean> decodeBitPlanValues(McReadPlan readPlan, byte[] payload) {
        Object decoded = McByteCodec.decode(buildBatchAddress(readPlan), payload);
        if (decoded instanceof List<?> list) {
            List<Boolean> values = new ArrayList<>(list.size());
            for (Object item : list) {
                values.add(Boolean.TRUE.equals(item));
            }
            return values;
        }
        return List.of(Boolean.TRUE.equals(decoded));
    }

    /**
     * 执行当前业务逻辑。
     */
    private void fallbackReadPlan(List<DataPoint> points, Map<String, Object> results) {
        int fallbackCount = 0;
        for (DataPoint point : points) {
            if (point == null || point.getPointId() == null) {
                continue;
            }
            try {
                results.put(point.getPointId(), doReadPoint(point));
                fallbackCount++;
            } catch (Exception singleEx) {
                log.error("MC 降级 点位 读取 失败, 设备={}, 点位={}",
                        deviceInfo.getDeviceId(), point.getPointId(), singleEx);
                results.put(point.getPointId(), null);
            }
        }
        lastFallbackCount.addAndGet(fallbackCount);
    }

    /**
     * 创建并返回业务对象。
     */
    private McAddress buildBatchAddress(McReadPlan readPlan) {
        return buildBatchAddress(
                readPlan.getDeviceCode(),
                readPlan.getStartDeviceNumber(),
                readPlan.getTotalUnitCount(),
                readPlan.isBitUnit()
        );
    }

    /**
     * 创建并返回业务对象。
     */
    private McAddress buildBatchAddress(McWritePlan writePlan) {
        return buildBatchAddress(
                writePlan.getDeviceCode(),
                writePlan.getStartDeviceNumber(),
                writePlan.getTotalUnitCount(),
                writePlan.isBitUnit()
        );
    }

    /**
     * 创建并返回业务对象。
     */
    private McAddress buildBatchAddress(com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode deviceCode,
                                        int startDeviceNumber,
                                        int totalUnitCount,
                                        boolean bitUnit) {
        StringBuilder canonical = new StringBuilder(deviceCode.getSymbol())
                .append(Integer.toString(startDeviceNumber, deviceCode.getRadix()).toUpperCase(Locale.ROOT));
        if (totalUnitCount > 1) {
            canonical.append('[').append(totalUnitCount).append(']');
        }
        return new McAddress(
                canonical.toString(),
                canonical.toString(),
                deviceCode,
                startDeviceNumber,
                bitUnit ? McDriverType.BOOL : McDriverType.UINT16,
                totalUnitCount,
                null,
                null
        );
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildSegmentKey(com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode deviceCode,
                                   int startDeviceNumber,
                                   int endDeviceNumberExclusive) {
        int radix = deviceCode.getRadix();
        String start = Integer.toString(startDeviceNumber, radix).toUpperCase(Locale.ROOT);
        String endExclusive = Integer.toString(endDeviceNumberExclusive, radix).toUpperCase(Locale.ROOT);
        return deviceCode.getSymbol() + ":" + start + "-" + endExclusive;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<DataPoint> cacheAddresses(List<DataPoint> points) {
        configuredAddresses.clear();
        List<DataPoint> readablePoints = new ArrayList<>();
        if (points == null) {
            return readablePoints;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                configuredAddresses.put(resolvePointCacheKey(point), McAddressParser.parse(point));
                if (point.getPointId() != null) {
                    readablePoints.add(point);
                }
            } catch (Exception ex) {
                log.warn("缓存 MC address 失败, 设备={}, 点位={}, 错误={}",
                        deviceInfo != null ? deviceInfo.getDeviceId() : null,
                        point.getPointId(),
                        ex.getMessage());
            }
        }
        return readablePoints;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<McWritePlan> planWritePoints(Map<DataPoint, Object> pointValues) {
        return writePlanBuilder.build(pointValues, maxWordsPerRequest, maxBitsPerRequest);
    }

    /**
     * 创建并返回业务对象。
     */
    private Set<String> buildPointKeySet(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> pointKeys = new LinkedHashSet<>();
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            pointKeys.add(resolvePointCacheKey(point));
        }
        return pointKeys;
    }

    /**
     * 创建并返回业务对象。
     */
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

    /**
     * 创建并返回业务对象。
     */
    private byte[] buildBatchWritePayload(McWritePlan writePlan,
                                          Map<String, Object> valuesByPointKey) {
        if (writePlan.isBitUnit()) {
            return buildBitWritePayload(writePlan, valuesByPointKey);
        }
        byte[] payload = new byte[writePlan.getPayloadByteLength()];
        for (McWritePlanItem item : writePlan.getItems()) {
            String pointKey = resolvePointCacheKey(item.getPoint());
            Object value = valuesByPointKey.get(pointKey);
            byte[] encoded = McByteCodec.encode(item.getAddress(), value);
            System.arraycopy(encoded, 0, payload, item.getPayloadByteOffset(), encoded.length);
        }
        return payload;
    }

    /**
     * 创建并返回业务对象。
     */
    private byte[] buildBitWritePayload(McWritePlan writePlan,
                                        Map<String, Object> valuesByPointKey) {
        List<Boolean> bitValues = new ArrayList<>(Collections.nCopies(writePlan.getTotalUnitCount(), Boolean.FALSE));
        for (McWritePlanItem item : writePlan.getItems()) {
            String pointKey = resolvePointCacheKey(item.getPoint());
            Object value = valuesByPointKey.get(pointKey);
            List<Boolean> encodedValues = extractBitValues(item.getAddress(), value);
            for (int i = 0; i < encodedValues.size(); i++) {
                bitValues.set(item.getUnitOffset() + i, encodedValues.get(i));
            }
        }
        return McByteCodec.encode(buildBatchAddress(writePlan), bitValues);
    }

    /**
     * 解析或转换业务数据。
     */
    private List<Boolean> extractBitValues(McAddress address, Object value) {
        if (address.isScalar()) {
            return List.of(toBooleanValue(value));
        }
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException("MC bit batch write requires collection payload: " + address.getCanonicalAddress());
        }
        List<Boolean> values = new ArrayList<>(collection.size());
        for (Object item : collection) {
            values.add(toBooleanValue(item));
        }
        if (values.size() != address.getElementCount()) {
            throw new IllegalArgumentException("MC bit batch write size mismatch: expected="
                    + address.getElementCount() + ", actual=" + values.size());
        }
        return values;
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean toBooleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "on".equals(normalized);
    }

    /**
     * 执行当前业务逻辑。
     */
    private void fallbackWritePlan(McWritePlan writePlan,
                                   Map<String, Object> valuesByPointKey,
                                   Map<String, Boolean> results) {
        int fallbackCount = 0;
        for (McWritePlanItem item : writePlan.getItems()) {
            DataPoint point = item.getPoint();
            if (point == null || point.getPointId() == null) {
                continue;
            }
            try {
                Object value = valuesByPointKey.get(resolvePointCacheKey(point));
                Object normalized = normalizeWriteValue(point, item.getAddress(), value);
                results.put(point.getPointId(), doWritePoint(point, normalized));
                fallbackCount++;
            } catch (Exception singleEx) {
                log.error("MC 降级 点位 写入 失败, 设备={}, 点位={}",
                        deviceInfo.getDeviceId(), point.getPointId(), singleEx);
                results.put(point.getPointId(), false);
            }
        }
        lastFallbackCount.addAndGet(fallbackCount);
    }

    /**
     * 查询并返回业务数据。
     */
    private Object readBitOffsetPoint(McAddress address) throws Exception {
        McAddress wordAddress = toWordContainerAddress(address);
        Object rawWord = readWordContainerValue(wordAddress);
        int wordValue = rawWord instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(rawWord));
        return ((wordValue >> address.getBitIndex()) & 0x01) == 1;
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeBitOffsetPoint(McAddress address, Object value) throws Exception {
        McAddress wordAddress = toWordContainerAddress(address);
        boolean targetBit = toBooleanValue(value);
        McFrameCodec frameCodec = resolveFrameCodec(requireRuntimeConnectionConfig());
        ReentrantLock lock = wordWriteLocks.computeIfAbsent(wordLockKey(wordAddress), ignored -> new ReentrantLock());
        lock.lock();
        try {
            Object rawWord = readWordContainerValue(wordAddress);
            int wordValue = rawWord instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(rawWord));

            int bitMask = 1 << address.getBitIndex();
            int updatedValue = targetBit ? (wordValue | bitMask) : (wordValue & ~bitMask);
            writeWordContainerValue(wordAddress, updatedValue & 0xFFFF, frameCodec);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private McAddress toWordContainerAddress(McAddress address) {
        return new McAddress(
                address.getDeviceCode().getSymbol() + Integer.toString(address.getDeviceNumber(), address.getDeviceCode().getRadix()).toUpperCase(Locale.ROOT),
                address.getDeviceCode().getSymbol() + Integer.toString(address.getDeviceNumber(), address.getDeviceCode().getRadix()).toUpperCase(Locale.ROOT),
                address.getDeviceCode(),
                address.getDeviceNumber(),
                McDriverType.UINT16,
                1,
                null,
                null
        );
    }

    /**
     * 解析或转换业务数据。
     */
    private McFrameCodec resolveFrameCodec(DeviceConnection connection) {
        if (connection == null) {
            return defaultFrameCodec;
        }
        String frameType = connection.getString("frameType", "3E_BINARY");
        if (frameType == null || frameType.isBlank()) {
            return defaultFrameCodec;
        }
        String normalized = frameType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "3E_BINARY" -> defaultFrameCodec;
            case "3E_ASCII" -> asciiFrameCodec;
            case "4E_BINARY" -> binary4eFrameCodec;
            default -> new UnsupportedMcFrameCodec(normalized);
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    private McAddress safeResolveAddress(DataPoint point) {
        try {
            return requireAddress(point);
        } catch (Exception ex) {
            log.error("解析 MC 地址 失败, 设备={}, 点位={}, 地址={}, 错误={}",
                    deviceInfo != null ? deviceInfo.getDeviceId() : null,
                    point != null ? point.getPointId() : null,
                    point != null ? point.getAddress() : null,
                    ex.getMessage());
            return null;
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private McAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(resolvePointCacheKey(point), ignored -> McAddressParser.parse(point));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private MitsubishiMcConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("Mitsubishi MC connection has not been established");
        }
        return connectionAdapter;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private DeviceConnection requireRuntimeConnectionConfig() {
        DeviceConnection current = getCurrentConnectionConfig();
        return current != null ? current : requireConnectionConfig();
    }

    /**
     * 查询并返回业务数据。
     */
    protected Object readWordContainerValue(McAddress wordAddress) throws Exception {
        validateRequestCapacity(wordAddress);
        lastRequestUnitCount = wordAddress.getReadUnitCount();
        McFrameCodec frameCodec = resolveFrameCodec(requireRuntimeConnectionConfig());
        byte[] request = frameCodec.buildBatchRead(wordAddress, requireRuntimeConnectionConfig());
        byte[] response = exchange(frameCodec, request);
        lastMcEndCode = frameCodec.readEndCode(response);
        byte[] payload = frameCodec.normalizeReadPayload(wordAddress, frameCodec.parseReadPayload(response));
        return McByteCodec.decode(wordAddress, payload);
    }

    /**
     * 写入或持久化业务数据。
     */
    protected void writeWordContainerValue(McAddress wordAddress, int value, McFrameCodec frameCodec) throws Exception {
        byte[] writePayload = McByteCodec.encode(wordAddress, value);
        byte[] writeRequest = frameCodec.buildBatchWrite(wordAddress,
                frameCodec.normalizeWritePayload(wordAddress, writePayload),
                requireRuntimeConnectionConfig());
        byte[] writeResponse = exchange(frameCodec, writeRequest);
        lastMcEndCode = frameCodec.readEndCode(writeResponse);
        frameCodec.ensureWriteSuccess(writeResponse);
    }

    /**
     * 执行当前业务逻辑。
     */
    private byte[] exchange(McFrameCodec frameCodec, byte[] request) throws Exception {
        try {
            byte[] response = requireConnection().exchange(request, timeout);
            return frameCodec.validateResponse(request, response);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateRequestCapacity(McAddress address) {
        int units = address.getReadUnitCount();
        if (address.isBitDevice() && units > maxBitsPerRequest) {
            throw new IllegalArgumentException("MC bit request exceeds maxBitsPerRequest: " + units);
        }
        if (address.isWordDevice() && units > maxWordsPerRequest) {
            throw new IllegalArgumentException("MC word request exceeds maxWordsPerRequest: " + units);
        }
    }

    private boolean isScalarPoint(DataPoint point) {
        return requireAddress(point).isScalar();
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateArrayPointConfiguration(DataPoint point, McAddress address, String operation) {
        if (address == null || address.isScalar()) {
            return;
        }
        if (point.getScalingFactor() != null || point.getOffset() != null) {
            throw new IllegalArgumentException("MC " + operation + " array point does not support scaling or offset: "
                    + point.getPointId());
        }
        if (point.getMinValue() != null || point.getMaxValue() != null) {
            throw new IllegalArgumentException("MC " + operation + " array point does not support min/max validation: "
                    + point.getPointId());
        }
        if (point.getAlarmEnabled() != null && point.getAlarmEnabled() == 1) {
            throw new IllegalArgumentException("MC " + operation + " array point does not support alarm processing: "
                    + point.getPointId());
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildScalarProcessResult(DataPoint point,
                                                   McAddress address,
                                                   Object rawValue) {
        Object processedValue = normalizeReadValue(point, address, rawValue);
        ProcessContext context = new ProcessContext();
        context.addAttribute(CommonMapKeys.DEVICE_ID, deviceInfo.getDeviceId());
        ProcessResult processResult = dataQualityProcessor.process(context, point, processedValue);
        if (!processResult.isSuccess()) {
            log.warn("MC 数据质量检查失败 {}.{}, 原因:{}",
                    deviceInfo.getDeviceId(), point.getPointName(), processResult.getMessage());
        }
        if (point != null && point.getAddress() != null) {
            processResult.addMetadata(CommonMapKeys.ADDRESS, point.getAddress());
        }
        processResult.addMetadata("processingMode", address.getDriverType().isStringType()
                ? "protocol_string_passthrough"
                : "default_scalar_conversion");
        return processResult;
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildArrayReadProcessResult(DataPoint point,
                                                      McAddress address,
                                                      Object rawValue,
                                                      String message) {
        validateArrayPointConfiguration(point, address, "read");
        return buildArrayProcessResult(point, address, rawValue, message);
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildWriteValidationResult(DataPoint point,
                                                     McAddress address,
                                                     Object value) {
        if (!address.isScalar()) {
            validateArrayPointConfiguration(point, address, "write");
        }
        ProcessContext context = new ProcessContext();
        context.addAttribute(CommonMapKeys.DEVICE_ID, deviceInfo.getDeviceId());
        return dataQualityProcessor.process(context, point, value);
    }

    /**
     * 解析或转换业务数据。
     */
    private Object normalizeReadValue(DataPoint point, McAddress address, Object rawValue) {
        if (address.getDriverType().isStringType()) {
            return rawValue;
        }
        return convertData(point, rawValue);
    }

    /**
     * 解析或转换业务数据。
     */
    private Object normalizeWriteValue(DataPoint point, McAddress address, Object value) {
        if (address.getDriverType().isStringType() || !address.isScalar()) {
            return value;
        }
        return convertDataForWrite(point, value);
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildArrayProcessResult(DataPoint point,
                                                  McAddress address,
                                                  Object rawValue,
                                                  String message) {
        if (!(rawValue instanceof Collection<?>) && !(rawValue != null && rawValue.getClass().isArray())) {
            throw new IllegalArgumentException("MC array point did not produce collection payload: " + point.getPointId());
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

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldInvalidateConnection(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CollectorException collectorException) {
                return collectorException.getCause() != null && shouldInvalidateConnection(collectorException.getCause());
            }
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.io.EOFException
                    || current instanceof java.net.SocketException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Unexpected MC")
                    || message.contains("Unexpected Mitsubishi MC")
                    || message.contains("response length mismatch")
                    || message.contains("response is too short")
                    || message.contains("socket closed")
                    || message.contains("serial mismatch")
                    || message.contains("receive failed")
                    || message.contains("send failed")
                    || message.contains("timed out"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 执行当前业务逻辑。
     */
    private void invalidateConnection(Throwable cause) {
        if (!connected && connectionAdapter == null) {
            return;
        }
        log.warn("作废Mitsubishi MC 连接，原因=协议或传输失败, 设备={}, 错误={}",
                deviceInfo != null ? deviceInfo.getDeviceId() : null,
                cause != null ? cause.getMessage() : null);
        try {
            MitsubishiMcConnectionAdapter adapter = connectionAdapter;
            connectionAdapter = null;
            if (adapter != null) {
                adapter.disconnect();
            }
        } catch (Exception disconnectError) {
            log.warn("断开异常 Mitsubishi MC 适配器 失败, 设备={}",
                    deviceInfo != null ? deviceInfo.getDeviceId() : null, disconnectError);
        } finally {
            removeManagedConnection("Mitsubishi MC");
            connected = false;
            connectionStatus = "DISCONNECTED";
            lastError = cause != null ? cause.getMessage() : lastError;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private String wordLockKey(McAddress address) {
        return deviceInfo.getDeviceId() + ":" + address.getDeviceCode().name() + ":" + address.getDeviceNumber();
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupported(String operation, String reason) {
        String message = String.format("Mitsubishi MC collector does not implement %s", operation);
        if (reason != null && !reason.isBlank()) {
            message = message + ": " + reason;
        }
        log.warn(message);
        return new UnsupportedOperationException(message);
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
            throw new IllegalArgumentException("No configured MC points found for device: "
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
                    .filter(candidate -> candidate != null && hasText(candidate.getAddress())
                            && normalize(candidate.getAddress()).equals(normalize(address)))
                    .findFirst()
                    .orElse(null);
            if (point != null) {
                return point;
            }
        }

        throw new IllegalArgumentException("Unable to resolve MC point from command params");
    }

    /**
     * 解析或转换业务数据。
     */
    private DataPoint resolveConfiguredPoint(List<DataPoint> points, String pointRef) {
        if (devicePointResolver != null) {
            return devicePointResolver.resolve(points, pointRef).orElse(null);
        }
        String normalizedRef = normalize(pointRef);
        return points.stream()
                .filter(point -> matchesPointRef(point, normalizedRef))
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
        return value != null ? value.toString() : null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
