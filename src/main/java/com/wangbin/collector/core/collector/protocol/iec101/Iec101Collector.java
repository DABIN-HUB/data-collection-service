package com.wangbin.collector.core.collector.protocol.iec101;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101PointAddress;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101Sample;
import com.wangbin.collector.core.collector.protocol.iec101.transport.Iec101Session;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec101ConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * IEC60870-5-101 非平衡控制站采集器。
 */
@Slf4j
public class Iec101Collector extends ConnectionBackedCollector {

    private final Map<SampleKey, Iec101Sample> sampleCache = new ConcurrentHashMap<>();
    private final Map<SampleKey, DataPoint> subscribedPointIndex = new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Qualifier("timeSliceScheduler")
    private ScheduledExecutorService protocolScheduler;

    private Iec101Session session;
    private ScheduledFuture<?> classOneTask;
    private ScheduledFuture<?> classTwoTask;

    @Override
    public String getCollectorType() {
        return "IEC101";
    }

    @Override
    public String getProtocolType() {
        return "IEC101";
    }

    @Override
    protected void doConnect() throws Exception {
        DeviceConnection connectionConfig = requireConnectionConfig();
        try {
            ConnectionAdapter<?> adapter = createManagedConnection(connectionConfig);
            Iec101ConnectionAdapter iec101Adapter = requireAdapterType(
                    adapter, Iec101ConnectionAdapter.class, "IEC101");
            connectManagedConnection();
            session = iec101Adapter.getClient();
            if (connectionConfig.getBoolConfig("clockSyncOnConnect", false)) {
                session.synchronizeClock(System.currentTimeMillis());
            }
            if (connectionConfig.getBoolConfig("generalInterrogationOnConnect", true)) {
                cacheSamples(session.generalInterrogation(20));
            }
        } catch (Exception exception) {
            removeManagedConnection("IEC101");
            throw exception;
        }
    }

    @Override
    protected void doDisconnect() {
        cancelPollingTasks();
        removeManagedConnection("IEC101");
        session = null;
        sampleCache.clear();
        subscribedPointIndex.clear();
    }

    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        Iec101PointAddress address = resolveAddress(point);
        Iec101Sample cached = findSample(address);
        if (cached == null) {
            cacheSamples(requireSession().read(address.informationObjectAddress()));
            cached = findSample(address);
        }
        if (cached == null) {
            cacheSamples(requireSession().generalInterrogation(20));
            cached = findSample(address);
        }
        if (cached == null) {
            throw new Iec101ProtocolException("IEC101 未返回点位数据: " + point.getAddress());
        }
        return cached.value();
    }

    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        boolean hasMiss = points.stream().map(this::resolveAddress).anyMatch(address -> findSample(address) == null);
        if (hasMiss) {
            cacheSamples(requireSession().generalInterrogation(20));
        }
        Map<String, Object> results = new LinkedHashMap<>();
        for (DataPoint point : points) {
            Iec101PointAddress address = resolveAddress(point);
            Iec101Sample sample = findSample(address);
            if (sample == null) {
                cacheSamples(requireSession().read(address.informationObjectAddress()));
                sample = findSample(address);
            }
            if (sample != null) {
                results.put(point.getPointId(), sample.value());
            }
        }
        return results;
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        String writeAddress = pointConfig(point, "writeAddress", point.getAddress());
        Iec101PointAddress address = Iec101PointAddress.parse(writeAddress);
        if (address.typeId() == null || address.typeId() < 45 || address.typeId() > 64) {
            throw new Iec101ProtocolException("IEC101 写地址必须明确配置命令 TypeId");
        }
        boolean select = pointBooleanConfig(point, "writeSelect", false);
        int qualifier = pointIntConfig(point, "writeQualifier", pointIntConfig(point, "writeQl", 0));
        if (select) {
            requireSession().command(address.typeId(), address.informationObjectAddress(),
                    value, true, qualifier);
        }
        requireSession().command(address.typeId(), address.informationObjectAddress(),
                value, false, qualifier);
        return true;
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            try {
                results.put(entry.getKey().getPointId(), doWritePoint(entry.getKey(), entry.getValue()));
            } catch (Exception exception) {
                log.error("IEC101 点位写入失败，点位={}", entry.getKey().getPointId(), exception);
                results.put(entry.getKey().getPointId(), false);
            }
        }
        return results;
    }

    @Override
    protected void doSubscribe(List<DataPoint> points) {
        for (DataPoint point : points) {
            subscribedPointMap.put(point.getPointId(), point);
            indexSubscribedPoint(point);
        }
        startPollingTasks();
    }

    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            subscribedPointMap.clear();
            subscribedPointIndex.clear();
            cancelPollingTasks();
            return;
        }
        for (DataPoint point : points) {
            subscribedPointMap.remove(point.getPointId());
        }
        rebuildSubscribedPointIndex();
        if (subscribedPointMap.isEmpty()) {
            cancelPollingTasks();
        }
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("protocol", "IEC101");
        status.put("connected", session != null && session.isOpen());
        status.put("linkAddress", session != null ? session.linkAddress() : null);
        status.put("commonAddress", session != null ? session.commonAddress() : null);
        status.put("cachedPoints", sampleCache.size());
        status.put("subscribedPoints", subscribedPointMap.size());
        status.put("classOnePolling", classOneTask != null && !classOneTask.isCancelled());
        status.put("classTwoPolling", classTwoTask != null && !classTwoTask.isCancelled());
        status.put("lastActivityTime", lastActivityTime);
        status.put("totalErrorCount", totalErrorCount.get());
        status.put("lastError", lastError);
        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        Map<String, Object> safeParams = params == null ? Collections.emptyMap() : params;
        String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "general_interrogation" -> {
                List<Iec101Sample> samples = requireSession().generalInterrogation(
                        intParam(safeParams, "qualifier", 20));
                cacheSamples(samples);
                yield samples.size();
            }
            case "counter_interrogation" -> {
                List<Iec101Sample> samples = requireSession().counterInterrogation(
                        intParam(safeParams, "qualifier", 5));
                cacheSamples(samples);
                yield samples.size();
            }
            case "clock_synchronization", "synchronize_clocks" -> {
                requireSession().synchronizeClock(System.currentTimeMillis());
                yield "时钟同步命令已确认";
            }
            default -> throw new UnsupportedOperationException("不支持的 IEC101 命令: " + command);
        };
    }

    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        // IEC101 由总召唤和链路级一级/二级数据轮询聚合，不按连续 IOA 合并请求。
    }

    private void startPollingTasks() {
        if (protocolScheduler == null || session == null || subscribedPointMap.isEmpty()) {
            return;
        }
        DeviceConnection config = requireConnectionConfig();
        if (classOneTask == null || classOneTask.isCancelled()) {
            long interval = Math.max(100, config.getLongConfig("class1PollIntervalMs", 1000L));
            classOneTask = protocolScheduler.scheduleWithFixedDelay(
                    () -> pollClassData(true), interval, interval, TimeUnit.MILLISECONDS);
        }
        if (classTwoTask == null || classTwoTask.isCancelled()) {
            long interval = Math.max(200, config.getLongConfig("class2PollIntervalMs", 5000L));
            classTwoTask = protocolScheduler.scheduleWithFixedDelay(
                    () -> pollClassData(false), interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    private void pollClassData(boolean classOne) {
        try {
            List<Iec101Sample> samples = classOne
                    ? requireSession().pollClassOne() : requireSession().pollClassTwo();
            for (Iec101Sample sample : samples) {
                cacheSample(sample);
                DataPoint point = findSubscribedPoint(sample);
                if (point != null && telemetryIngressService != null) {
                    ProcessResult result = telemetryIngressService.appendRaw(
                            deviceInfo.getDeviceId(),
                            point,
                            sample.value(),
                            sample.quality(),
                            sample.sourceTimestamp(),
                            "IEC101");
                    lastProcessResults.put(point.getPointId(), result);
                }
            }
        } catch (Exception exception) {
            totalErrorCount.incrementAndGet();
            lastError = exception.getMessage();
            log.warn("IEC101 {}级数据轮询失败，设备={}",
                    classOne ? "一" : "二", deviceInfo.getDeviceId(), exception);
        }
    }

    private void cancelPollingTasks() {
        if (classOneTask != null) {
            classOneTask.cancel(true);
            classOneTask = null;
        }
        if (classTwoTask != null) {
            classTwoTask.cancel(true);
            classTwoTask = null;
        }
    }

    private void cacheSamples(List<Iec101Sample> samples) {
        samples.forEach(this::cacheSample);
    }

    private void cacheSample(Iec101Sample sample) {
        sampleCache.put(new SampleKey(sample.commonAddress(), sample.typeId(),
                sample.informationObjectAddress()), sample);
        sampleCache.put(new SampleKey(sample.commonAddress(), null,
                sample.informationObjectAddress()), sample);
    }

    private Iec101Sample findSample(Iec101PointAddress address) {
        int commonAddress = requireSession().commonAddress();
        Iec101Sample exact = address.typeId() == null ? null
                : sampleCache.get(new SampleKey(commonAddress, address.typeId(),
                address.informationObjectAddress()));
        return exact != null ? exact : sampleCache.get(
                new SampleKey(commonAddress, null, address.informationObjectAddress()));
    }

    private void indexSubscribedPoint(DataPoint point) {
        Iec101PointAddress address = resolveAddress(point);
        int commonAddress = requireSession().commonAddress();
        subscribedPointIndex.put(new SampleKey(commonAddress, address.typeId(),
                address.informationObjectAddress()), point);
        if (address.typeId() == null) {
            subscribedPointIndex.put(new SampleKey(commonAddress, null,
                    address.informationObjectAddress()), point);
        }
    }

    private DataPoint findSubscribedPoint(Iec101Sample sample) {
        DataPoint exact = subscribedPointIndex.get(new SampleKey(
                sample.commonAddress(), sample.typeId(), sample.informationObjectAddress()));
        return exact != null ? exact : subscribedPointIndex.get(new SampleKey(
                sample.commonAddress(), null, sample.informationObjectAddress()));
    }

    private void rebuildSubscribedPointIndex() {
        subscribedPointIndex.clear();
        subscribedPointMap.values().forEach(this::indexSubscribedPoint);
    }

    private Iec101PointAddress resolveAddress(DataPoint point) {
        return Iec101PointAddress.parse(point.getAddress());
    }

    private Iec101Session requireSession() {
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("IEC101 会话尚未连接");
        }
        return session;
    }

    private Map<String, Object> pointConfigMap(DataPoint point) {
        return point.getAdditionalConfig() == null ? Collections.emptyMap() : point.getAdditionalConfig();
    }

    private String pointConfig(DataPoint point, String key, String defaultValue) {
        Object value = pointConfigMap(point).get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private int pointIntConfig(DataPoint point, String key, int defaultValue) {
        Object value = pointConfigMap(point).get(key);
        return value instanceof Number number ? number.intValue()
                : value == null ? defaultValue : Integer.parseInt(value.toString());
    }

    private boolean pointBooleanConfig(DataPoint point, String key, boolean defaultValue) {
        Object value = pointConfigMap(point).get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        return value instanceof Number number ? number.intValue()
                : value == null ? defaultValue : Integer.parseInt(value.toString());
    }

    private record SampleKey(int commonAddress, Integer typeId, int informationObjectAddress) {
    }
}
