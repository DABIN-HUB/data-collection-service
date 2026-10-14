package com.wangbin.collector.core.collector.protocol.iec;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.iec.base.AbstractIce104Collector;
import com.wangbin.collector.core.collector.protocol.iec.domain.Iec104Address;
import com.wangbin.collector.core.collector.protocol.iec.domain.Iec104Type;
import com.wangbin.collector.core.collector.protocol.iec.util.Iec104Utils;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec104ConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.openmuc.j60870.*;
import org.openmuc.j60870.ie.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * IEC-60870-5-104 采集器.
 */
@Slf4j
public class Iec104Collector extends AbstractIce104Collector {
    private final ThreadLocal<Map<String, CacheEntry>> cachedReadSamples = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Set<String>> wireReadPoints = ThreadLocal.withInitial(HashSet::new);
    private final ThreadLocal<Map<String, Throwable>> readFailures = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<ReadScope> readScope = new ThreadLocal<>();
    private final ThreadLocal<CacheEntry> telemetrySample = new ThreadLocal<>();
    private final ThreadLocal<List<PushSample>> pendingPushSamples = ThreadLocal.withInitial(ArrayList::new);
    private final Object protocolStateLock = new Object();
    private volatile Iec104ConnectionAdapter currentAdapter;
    private volatile boolean readSessionExpired;
    private Connection readyConnection;

    private record ReadScope(Iec104ConnectionAdapter adapter, Connection client, long generation, long version) {}
    private record PushSample(DataPoint point, CacheEntry sample, ReadScope scope) {}

    private ReadScope captureReadScope() {
        synchronized (protocolStateLock) {
            return new ReadScope(currentAdapter, connection, runtimeGeneration, runtimeConfigVersion);
        }
    }

    private boolean commitIfSourceCurrent(ReadScope scope, Runnable action) {
        synchronized (protocolStateLock) {
            if (scope == null || scope.adapter() == null || scope.client() == null
                    || currentAdapter != scope.adapter() || connection != scope.client()
                    || scope.adapter().getClient() != scope.client() || scope.client().isClosed()
                    || runtimeGeneration != scope.generation() || runtimeConfigVersion != scope.version()
                    || (connectionManager != null && connectionManager.getConnection(getDeviceId()) != scope.adapter())) {
                return false;
            }
            action.run();
            return true;
        }
    }

    @Override
    public boolean isConnected() {
        Connection client = connection;
        return super.isConnected() && !readSessionExpired && client != null && !client.isClosed()
                && !client.isStopped();
    }

    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        resetInvocationProcessResults();
        readFailures.get().clear();
        cachedReadSamples.get().clear();
        wireReadPoints.get().clear();
        readScope.set(captureReadScope());
        try {
            validateReadPointOwnership(point);
            return super.readPoint(point);
        } catch (Exception failure) {
            Throwable protocolFailure = point != null ? readFailures.get().getOrDefault(point.getPointId(), failure) : failure;
            telemetrySample.remove();
            ProcessResult result = failedProtocolReadResult(protocolFailure);
            enrichTelemetryMetadata(result, null, null, System.currentTimeMillis(), "POLLING");
            if (point != null && Objects.equals(getDeviceId(), point.getDeviceId()) && telemetryIngressService != null) {
                telemetryIngressService.append(getDeviceId(), point, result, runtimeGeneration);
            }
            clearInvocationProcessResults();
            throw failure;
        } finally {
            readScope.remove();
            cachedReadSamples.remove();
            wireReadPoints.remove();
            telemetrySample.remove();
            readFailures.remove();
            finishInvocationProcessResults();
        }
    }

    @Override
    public Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException {
        resetInvocationProcessResults();
        readFailures.get().clear();
        wireReadPoints.get().clear();
        cachedReadSamples.get().clear();
        readScope.set(captureReadScope());
        try {
            List<DataPoint> readablePoints = points == null ? Collections.emptyList()
                    : points.stream().filter(point -> point != null && point.isEnabled()).toList();
            for (DataPoint point : readablePoints) validateReadPointOwnership(point);
            Map<String, Object> values = super.readPoints(readablePoints);
            Map<String, ProcessResult> invocation = new LinkedHashMap<>(takeInvocationProcessResults());
            for (DataPoint point : readablePoints) {
                Throwable failure = readFailures.get().get(point.getPointId());
                if (failure != null) {
                    telemetrySample.remove();
                    ProcessResult result = failedProtocolReadResult(failure);
                    enrichTelemetryMetadata(result, null, null, System.currentTimeMillis(), "POLLING");
                    lastProcessResults.put(point.getPointId(), result);
                    invocation.put(point.getPointId(), result.snapshot());
                }
            }
            setInvocationProcessResults(invocation);
            return values;
        } catch (Exception failure) {
            clearInvocationProcessResults();
            throw failure;
        } finally {
            readScope.remove();
            readFailures.remove();
            wireReadPoints.remove();
            cachedReadSamples.remove();
            telemetrySample.remove();
            finishInvocationProcessResults();
        }
    }

    @Override
    protected void enrichTelemetryMetadata(ProcessResult result, Object rawValue, Object processedValue,
                                           long collectTime, String source) {
        super.enrichTelemetryMetadata(result, rawValue, processedValue, collectTime, source);
        ReadScope scope = readScope.get();
        result.bindSourceCommitGuard(action -> commitIfSourceCurrent(scope, action));
        if (scope != null) result.addMetadata("configVersion", scope.version());
        CacheEntry sample = telemetrySample.get();
        if (sample != null) {
            result.addMetadata("iec104QualityBits", sample.qualityBits());
            result.addMetadata("iec104TypeId", sample.rawTypeId());
            result.addMetadata("iec104ObservedAt", sample.observedAt());
            result.addMetadata("iec104TimeOrigin", sample.sourceTime() != null ? "CP56_SOURCE" : "LOCAL_RECEIVE");
            if (sample.sourceTime() != null) result.addMetadata("iec104SourceTime", sample.sourceTime());
            if (sample.qualityBits() != 0) {
                result.setSuccess(false);
                result.setQuality(Math.min(result.getQuality(), DataQuality.BAD.getCode()));
                result.setErrorCode("IEC104_WIRE_QUALITY");
                result.setMessage("IEC104 测量质量标志不可用");
            }
        }
    }

    @Override
    protected long readSampleAt(DataPoint point, long requestedAt) {
        CacheEntry cached = cachedReadSamples.get().get(point.getPointId());
        telemetrySample.set(cached);
        return cached != null ? cached.timestamp() : requestedAt;
    }

    @Override
    protected String readSampleSource(DataPoint point) {
        return cachedReadSamples.get().containsKey(point.getPointId())
                && !wireReadPoints.get().contains(point.getPointId()) ? "CACHE_READ" : "POLLING";
    }

    private final Map<Iec104Key, DataPoint> 突发上送PointIndex = new ConcurrentHashMap<>();

    @Override
    public String getCollectorType() {
        return "IEC104";
    }

    @Override
    public String getProtocolType() {
        return "IEC104";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        log.info("正在连接 IEC 104 设备:{}", getDeviceId());
        DeviceConnection connectionConfig = requireConnectionConfig();
        if (readSessionExpired) removeConnectionSilently();
        synchronized (protocolStateLock) {
            clearProtocolState();
            readSessionExpired = false;
            readyConnection = null;
        }
        initIec104Config(deviceInfo);
        try {
            ConnectionAdapter<?> adapter = createManagedConnection(connectionConfig);
            Iec104ConnectionAdapter iec104Adapter = requireAdapterType(
                    adapter,
                    Iec104ConnectionAdapter.class,
                    "IEC104");
            synchronized (protocolStateLock) {
                currentAdapter = iec104Adapter;
            }
            iec104Adapter.setConnectionEventListener(createConnectionEventListener(iec104Adapter));
            connectManagedConnection();
            synchronized (protocolStateLock) {
                this.connection = iec104Adapter.getClient();
                dataTransferStopped = connection == null || connection.isStopped();
            }
            initializeReadyConnection(iec104Adapter.getClient(), iec104Adapter);
        } catch (Exception e) {
            try {
                removeConnectionSilently();
            } catch (Exception cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void doDisconnect() {
        synchronized (protocolStateLock) {
            currentAdapter = null;
            connection = null;
            readyConnection = null;
            clearProtocolState();
        }
        removeConnectionSilently();
        突发上送PointIndex.clear();
        log.info("IEC104 连接 已关闭");
    }

    /**
     * 创建并返回业务对象。
     */
    private ConnectionEventListener createConnectionEventListener(Iec104ConnectionAdapter adapter) {
        return new ConnectionEventListener() {
            /**
             * 创建并返回业务对象。
             */
            @Override
            public void newASdu(Connection conn, ASdu asdu) {
                pendingPushSamples.get().clear();
                try {
                    synchronized (protocolStateLock) {
                        if (!bindCurrentConnection(conn, adapter) || readSessionExpired) return;
                        handleResponse(conn, asdu);
                        lastActivityTime = System.currentTimeMillis();
                    }
                    for (PushSample push : pendingPushSamples.get()) publishPushSample(push);
                } catch (Exception e) {
                    handleError("ASDU handling failed", e);
                } finally {
                    pendingPushSamples.remove();
                }
            }

            /**
             * 处理连接生命周期。
             */
            @Override
            public void connectionClosed(Connection conn, IOException e) {
                synchronized (protocolStateLock) {
                    if (isCurrentConnection(conn, adapter)) handleConnectionClosed(e);
                }
            }

            /**
             * 执行当前业务逻辑。
             */
            @Override
            public void dataTransferStateChanged(Connection conn, boolean stopped) {
                synchronized (protocolStateLock) {
                    if (!bindCurrentConnection(conn, adapter)) return;
                    dataTransferStopped = stopped;
                    if (stopped) {
                        readyConnection = null;
                        connectionStatus = "CONNECTED_STOPPED";
                    } else {
                        connectionStatus = connected ? "CONNECTED" : connectionStatus;
                        reportProtocolReady();
                    }
                }
                if (!stopped && connection == conn) initializeReadyConnection(conn, adapter);
            }
        };
    }

    private boolean isCurrentConnection(Connection candidate, Iec104ConnectionAdapter adapter) {
        return candidate != null
                && currentAdapter == adapter
                && candidate == adapter.getClient()
                && (connectionManager == null || connectionManager.getConnection(getDeviceId()) == adapter)
                && (connection == null || candidate == connection);
    }

    /**
     * j60870 可能在 Collector 写入 connection 字段之前回调真实 ASDU；在协议锁内绑定该回调的真实客户端。
     */
    private boolean bindCurrentConnection(Connection candidate, Iec104ConnectionAdapter adapter) {
        if (!isCurrentConnection(candidate, adapter)) return false;
        if (connection == null) {
            connection = candidate;
        }
        return true;
    }

    private void initializeReadyConnection(Connection client, Iec104ConnectionAdapter adapter) {
        synchronized (protocolStateLock) {
            if (!isCurrentConnection(client, adapter) || dataTransferStopped || readyConnection == client) return;
            readyConnection = client;
        }
        // 网络发送不占协议状态锁，STARTDT回调和赋值后初始化共享一次性身份。
        onConnectionReady();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        cachedReadSamples.get().remove(point.getPointId());
        Iec104Address address = resolveReadAddress(point);
        int ioa = address.getIoAddress();
        int ca = address.getCommonAddress();
        Integer typeId = resolvePointTypeId(point, address);

        CacheEntry cached = getCachedSample(ca, typeId, ioa);
        if (cached != null) {
            cachedReadSamples.get().put(point.getPointId(), cached);
            return cached.value();
        }

        CompletableFuture<Object> future = registerPendingRequest(ca, typeId, ioa);
        try {
            if (!maybeTriggerSingleInterrogation(point, address)) {
                readScope.get().client().readCommand(ca, toWireIoa(ioa));
            }
            return acceptReadSample(point, future.get(timeout, TimeUnit.MILLISECONDS));
        } catch (Exception failure) {
            readFailures.get().put(point.getPointId(), failure);
            markReadTimeout(readScope.get(), failure);
            throw failure;
        } finally {
            future.cancel(false);
        }
    }

    private Object acceptReadSample(DataPoint point, Object response) {
        if (!(response instanceof CacheEntry sample)) throw new IllegalStateException("IEC104 未返回真实测量样本");
        cachedReadSamples.get().put(point.getPointId(), sample);
        wireReadPoints.get().add(point.getPointId());
        return sample.value();
    }

    private void markReadTimeout(ReadScope scope, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                // Read Command没有事务号；超时后必须新建会话，不能让迟到响应完成下次请求。
                synchronized (protocolStateLock) {
                    if (scope == null || !isCurrentConnection(scope.client(), scope.adapter())
                            || currentAdapter != scope.adapter()
                            || runtimeGeneration != scope.generation()
                            || runtimeConfigVersion != scope.version()) {
                        return;
                    }
                    readSessionExpired = true;
                    connectionStatus = "ERROR";
                }
                return;
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        cachedReadSamples.get().clear();
        Map<String, Object> results = new HashMap<>();
        Map<String, PendingRead> pendingReads = new LinkedHashMap<>();
        Map<Iec104Key, PendingRead> deduplicatedRequests = new LinkedHashMap<>();

        for (DataPoint point : points) {
            try {
                Iec104Address address = resolveReadAddress(point);
                Integer typeId = resolvePointTypeId(point, address);
                CacheEntry cached = getCachedSample(address.getCommonAddress(), typeId, address.getIoAddress());
                if (cached != null) {
                    cachedReadSamples.get().put(point.getPointId(), cached);
                    results.put(point.getPointId(), cached.value());
                    continue;
                }
                PendingRead pendingRead = new PendingRead(point, address,
                        registerPendingRequest(address.getCommonAddress(), typeId, address.getIoAddress()));
                pendingReads.put(point.getPointId(), pendingRead);
                deduplicatedRequests.putIfAbsent(resolvePointKey(point, address), pendingRead);
            } catch (Exception failure) {
                results.put(point.getPointId(), null);
                readFailures.get().put(point.getPointId(), failure);
            }
        }

        Set<InterrogationKey> triggeredQualifiers = new HashSet<>();
        for (Map.Entry<Iec104Key, PendingRead> entry : deduplicatedRequests.entrySet()) {
            Iec104Key key = entry.getKey();
            PendingRead pendingRead = entry.getValue();
            try {
                boolean triggered = maybeTriggerSingleInterrogation(pendingRead.point(),
                        pendingRead.address(), triggeredQualifiers);
                if (!triggered) {
                    readScope.get().client().readCommand(key.commonAddress(), toWireIoa(key.ioAddress()));
                }
            } catch (Exception failure) {
                // 同一地址可能绑定多个点；仅使这次发送关联的Future失败。
                for (PendingRead waiting : pendingReads.values()) {
                    if (resolvePointKey(waiting.point(), waiting.address()).equals(key)) {
                        waiting.future().completeExceptionally(failure);
                    }
                }
            }
        }

        for (Map.Entry<String, PendingRead> entry : pendingReads.entrySet()) {
            try {
                Object value = entry.getValue().future().get(timeout, TimeUnit.MILLISECONDS);
                results.put(entry.getKey(), acceptReadSample(entry.getValue().point(), value));
            } catch (Exception e) {
                results.put(entry.getKey(), null);
                readFailures.get().put(entry.getKey(), e);
                markReadTimeout(readScope.get(), e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally {
                entry.getValue().future().cancel(false);
            }
        }

        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        WriteTarget target = resolveWriteTarget(point);
        log.debug("写入 IEC104 点位:type={}, typeId={}, ca={}, 地址={}, 值={}",
                target.type().typeName(), target.type().typeId(),
                target.address().getCommonAddress(), target.address().getIoAddress(), value);
        return writeValueByType(target, value);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new HashMap<>();
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            try {
                boolean success = doWritePoint(entry.getKey(), entry.getValue());
                results.put(entry.getKey().getPointId(), success);
            } catch (Exception e) {
                log.error("写入 IEC104 点位 失败:{}", entry.getKey().getPointName(), e);
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
        for (DataPoint point : points) {
            subscribedPointMap.put(point.getPointId(), point);
        }
        log.info("IEC104 点位订阅完成, 数量={}", points.size());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        for (DataPoint point : points) {
            subscribedPointMap.remove(point.getPointId());
        }
        log.info("IEC104 点位取消订阅完成, 数量={}", points.size());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.PROTOCOL, "IEC 104");
        status.put(CommonMapKeys.HOST, host);
        status.put(CommonMapKeys.PORT, port);
        status.put("commonAddress", commonAddress);
        status.put(CommonMapKeys.TIMEOUT, connectionTimeout);
        status.put("requestTimeout", timeout);
        status.put("ioaFieldLength", ioaFieldLength);
        status.put("ioaEncodingMode", ioaEncodingMode.name());
        status.put(CommonMapKeys.CONNECTED, isConnected());
        status.put("dataTransferStopped", dataTransferStopped);
        status.put(CommonMapKeys.CONNECTION_STATUS, connectionStatus);
        status.put(CommonMapKeys.SUBSCRIBED_POINTS, subscribedPointsSet.size());
        status.put("lastConnectTime", lastConnectTime);
        status.put(CommonMapKeys.LAST_ACTIVITY_TIME, lastActivityTime);
        status.put(CommonMapKeys.TOTAL_ERROR_COUNT, totalErrorCount.get());
        status.put("lastError", lastError);
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        int ca = resolveCommandCommonAddress(unitId, params);
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        int ql = getIntParameter(safeParams, "ql", 0);
        boolean select = getBooleanParameter(safeParams, "select", false);

        return switch (command.toLowerCase(Locale.ROOT)) {
            case "single_command", "single_command_with_timetag" -> {
                boolean withTimeTag = command.toLowerCase(Locale.ROOT).endsWith("_with_timetag");
                writeSingleCommand(commandWriteTarget(ca, safeParams,
                                withTimeTag ? Iec104Type.C_SC_TA_1 : Iec104Type.C_SC_NA_1, ql, select),
                        requireFirstValue(safeParams, "state", "value"));
                yield withTimeTag ? "single command with time tag sent" : "single command sent";
            }
            case "double_command", "double_command_with_timetag" -> {
                boolean withTimeTag = command.toLowerCase(Locale.ROOT).endsWith("_with_timetag");
                writeDoubleCommand(commandWriteTarget(ca, safeParams,
                                withTimeTag ? Iec104Type.C_DC_TA_1 : Iec104Type.C_DC_NA_1, ql, select),
                        requireFirstValue(safeParams, "state", "value"));
                yield withTimeTag ? "double command with time tag sent" : "double command sent";
            }
            case "regulating_step_command", "regulating_step_command_with_timetag" -> {
                boolean withTimeTag = command.toLowerCase(Locale.ROOT).endsWith("_with_timetag");
                writeRegulatingStepCommand(commandWriteTarget(ca, safeParams,
                                withTimeTag ? Iec104Type.C_RC_TA_1 : Iec104Type.C_RC_NA_1, ql, select),
                        requireFirstValue(safeParams, "state", "value"));
                yield withTimeTag ? "regulating step with time tag sent" : "regulating step sent";
            }
            case "set_normalized_value_command", "set_normalized_value_command_with_timetag" -> {
                boolean withTimeTag = command.toLowerCase(Locale.ROOT).endsWith("_with_timetag");
                writeNormalizedSetpoint(commandWriteTarget(ca, safeParams,
                                withTimeTag ? Iec104Type.C_SE_TA_1 : Iec104Type.C_SE_NA_1, ql, select),
                        Iec104Utils.parseFloatValue(requireValue(safeParams, "value")));
                yield withTimeTag ? "normalized set point with time tag sent" : "normalized set point sent";
            }
            case "set_scaled_value_command", "set_scaled_value_command_with_timetag" -> {
                boolean withTimeTag = command.toLowerCase(Locale.ROOT).endsWith("_with_timetag");
                writeScaledSetPoint(commandWriteTarget(ca, safeParams,
                                withTimeTag ? Iec104Type.C_SE_TB_1 : Iec104Type.C_SE_NB_1, ql, select),
                        Iec104Utils.parseIntegerValue(requireValue(safeParams, "value")));
                yield withTimeTag ? "scaled set point with time tag sent" : "scaled set point sent";
            }
            case "set_short_float_command", "set_short_float_command_with_timetag" -> {
                boolean withTimeTag = command.toLowerCase(Locale.ROOT).endsWith("_with_timetag");
                writeShortFloatSetPoint(commandWriteTarget(ca, safeParams,
                                withTimeTag ? Iec104Type.C_SE_TC_1 : Iec104Type.C_SE_NC_1, ql, select),
                        Iec104Utils.parseFloatValue(requireValue(safeParams, "value")));
                yield withTimeTag ? "short float set point with time tag sent" : "short float set point sent";
            }
            case "bit_string_command", "bit_string_command_with_timetag" -> {
                boolean withTimeTag = command.toLowerCase(Locale.ROOT).endsWith("_with_timetag");
                writeBitStringCommand(commandWriteTarget(ca, safeParams,
                                withTimeTag ? Iec104Type.C_BO_TA_1 : Iec104Type.C_BO_NA_1, ql, select),
                        requireValue(safeParams, "value"));
                yield withTimeTag ? "bit string command with time tag sent" : "bit string command sent";
            }
            case "interrogation", "general_interrogation" -> {
                int qualifierValue = getIntParameter(safeParams, "qualifier", 20);
                connection.interrogation(ca, CauseOfTransmission.ACTIVATION,
                        new IeQualifierOfInterrogation(qualifierValue));
                yield "general interrogation sent";
            }
            case "counter_interrogation" -> {
                int qualifierValue = getIntParameter(safeParams, "qualifier", 5);
                int freeze = getIntParameter(safeParams, "freeze", 0);
                connection.counterInterrogation(ca, CauseOfTransmission.ACTIVATION,
                        new IeQualifierOfCounterInterrogation(qualifierValue, freeze));
                yield "counter interrogation sent";
            }
            case "read_command" -> {
                connection.readCommand(ca, toWireIoa(requireIntParameter(safeParams, "address")));
                yield "read command sent";
            }
            case "synchronize_clocks", "clock_synchronization" -> {
                connection.synchronizeClocks(ca, new IeTime56(System.currentTimeMillis()));
                yield "clock synchronization sent";
            }
            case "test_command" -> {
                connection.testCommand(ca);
                yield "test command sent";
            }
            case "test_command_with_timetag" -> {
                int sequence = getIntParameter(safeParams, "sequence", 0);
                connection.testCommandWithTimeTag(ca,
                        new IeTestSequenceCounter(sequence),
                        new IeTime56(System.currentTimeMillis()));
                yield "test command with time tag sent";
            }
            case "reset_process_command" -> {
                int qualifierValue = getIntParameter(safeParams, "qualifier", 0);
                connection.resetProcessCommand(ca, new IeQualifierOfResetProcessCommand(qualifierValue));
                yield "reset process command sent";
            }
            case "delay_acquisition_command" -> {
                int delay = getIntParameter(safeParams, "delay", 0);
                connection.delayAcquisitionCommand(ca, CauseOfTransmission.ACTIVATION, new IeTime16(delay));
                yield "delay acquisition command sent";
            }
            default -> throw new IllegalArgumentException("Unsupported IEC104 command: " + command);
        };
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        突发上送PointIndex.clear();
        for (DataPoint point : points) {
            try {
                Iec104Address address = resolveReadAddress(point);
                突发上送PointIndex.put(resolvePointKey(point, address), point);
            } catch (Exception e) {
                log.debug("跳过 IEC104 推送索引 for 无效 点位:{}", point != null ? point.getPointId() : null, e);
            }
        }
        log.info("IEC104 点位 已加载，数量={}", points.size());
    }

    /**
     * 处理当前业务流程。
     */
    @Override
    protected void handleSpontaneous(int commonAddress, Integer typeId, int ioa, ASduType type, Object value, ASdu asdu) {
        super.handleSpontaneous(commonAddress, typeId, ioa, type, value, asdu);
        DataPoint point = findSpontaneousPoint(commonAddress, typeId, ioa);
        log.debug("IEC104 测量映射：CA={}，ASDU={}，逻辑IOA={}，点位={}，匹配={}",
                commonAddress, type, ioa, point != null ? point.getPointId() : null, point != null);
        if (point != null) {
            CacheEntry sample = valueCache.get(new Iec104Key(commonAddress, typeId, ioa));
            if (sample != null) pendingPushSamples.get().add(new PushSample(point, sample, captureReadScope()));
        }
    }

    /** 映射在收包状态锁内完成，遥测提交在锁外执行并携带原物理源许可。 */
    private void publishPushSample(PushSample push) {
        ProcessResult[] accepted = new ProcessResult[1];
        readScope.set(push.scope());
        telemetrySample.set(push.sample());
        try {
            Runnable process = () -> commitIfSourceCurrent(push.scope(), () -> {
                Object raw = push.sample().value();
                Object converted = convertData(push.point(), raw);
                com.wangbin.collector.core.processor.ProcessContext context = new com.wangbin.collector.core.processor.ProcessContext();
                context.addAttribute(CommonMapKeys.DEVICE_ID, getDeviceId());
                ProcessResult result = dataQualityProcessor.process(context, push.point(), converted);
                enrichTelemetryMetadata(result, raw, converted, push.sample().timestamp(), "PUSH");
                lastProcessResults.put(push.point().getPointId(), result);
                accepted[0] = result;
            });
            if (configManager != null) configManager.runIfConfigurationCurrent(getDeviceId(), push.scope().version(), process);
            else process.run();
            if (accepted[0] != null && telemetryIngressService != null) {
                telemetryIngressService.append(getDeviceId(), push.point(), accepted[0], push.scope().generation());
            }
        } finally {
            readScope.remove();
            telemetrySample.remove();
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void handleConnectionClosed(IOException e) {
        connected = false;
        connectionStatus = "DISCONNECTED";
        lastDisconnectTime = System.currentTimeMillis();
        connection = null;
        currentAdapter = null;
        readyConnection = null;
        clearProtocolState();

        if (e != null) {
            handleError("Connection closed with error", e);
        } else {
            log.info("IEC104 连接 已关闭");
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void handleError(String message, Exception e) {
        totalErrorCount.incrementAndGet();
        lastError = e.getMessage();
        log.error(message, e);
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean maybeTriggerSingleInterrogation(DataPoint point, Iec104Address address) {
        return maybeTriggerSingleInterrogation(point, address, null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean maybeTriggerSingleInterrogation(DataPoint point,
                                                    Iec104Address address,
                                                    Set<InterrogationKey> triggered) {
        if (iec104Config == null || !iec104Config.isSingleInterrogationOnReadMiss()) {
            return false;
        }
        if (address == null) {
            return false;
        }

        Integer qualifier = resolveQualifier(point);
        if (qualifier == null) {
            return false;
        }

        InterrogationKey key = new InterrogationKey(address.getCommonAddress(), qualifier);
        if (triggered != null) {
            if (!triggered.add(key)) {
                return true;
            }
        }

        triggerSingleInterrogation(key.commonAddress(), key.qualifier(), "read-miss");
        return true;
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolveQualifier(DataPoint point) {
        if (point == null || iec104Config == null) {
            return null;
        }
        String field = Optional.ofNullable(iec104Config.getSingleInterrogationGroupField())
                .filter(s -> !s.isBlank())
                .orElse("groupId");
        String raw;
        switch (field) {
            case "unit":
                raw = point.getUnit();
                break;
            case "unitId":
                raw = point.getUnitId() != null ? point.getUnitId().toString() : null;
                break;
            case "pointCode":
                raw = point.getPointCode();
                break;
            case "groupId":
            default:
                raw = point.getGroupId();
                break;
        }
        return resolveSingleInterrogationQualifier(raw).orElse(null);
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveCommonAddress(DataPoint point) {
        Object cfg = point.getAdditionalConfig("commonAddress");
        if (cfg != null) {
            return cfg.toString();
        }
        if (point.getCommonAddress() != null) {
            return point.getCommonAddress().toString();
        }
        if (point.getUnitId() != null) {
            return point.getUnitId().toString();
        }
        return String.valueOf(commonAddress);
    }

    /**
     * 解析或转换业务数据。
     */
    private Iec104Address resolveReadAddress(DataPoint point) {
        return Iec104Utils.parseAddress(resolveCommonAddress(point), point.getAddress());
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveWriteCommonAddress(DataPoint point) {
        Object cfg = point.getAdditionalConfig("writeCommonAddress");
        if (cfg != null) {
            return cfg.toString();
        }
        return resolveCommonAddress(point);
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveWriteAddressValue(DataPoint point) {
        Object cfg = point.getAdditionalConfig("writeAddress");
        if (cfg instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return point.getAddress();
    }

    /**
     * 解析或转换业务数据。
     */
    private Iec104Address resolveWriteAddress(DataPoint point) {
        return Iec104Utils.parseTypedAddress(resolveWriteCommonAddress(point), resolveWriteAddressValue(point), "write");
    }

    /**
     * 解析或转换业务数据。
     */
    private WriteTarget resolveWriteTarget(DataPoint point) {
        rejectWriteTimeTagConfig(point);
        Iec104Address address = resolveWriteAddress(point);
        Iec104Type type = resolveWriteType(point, address);
        boolean select = getAdditionalBoolean(point, "writeSelect", false);
        rejectUnsupportedSelect(select);
        return new WriteTarget(
                address,
                type,
                getAdditionalInt(point, "writeQl", 0),
                select);
    }

    private void rejectUnsupportedSelect(boolean select) {
        if (select) {
            throw new IllegalArgumentException("IEC104 select-before-execute requires confirmed SBO and is not supported");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void rejectWriteTimeTagConfig(DataPoint point) {
        if (point != null && point.getAdditionalConfig("writeTimeTag") != null) {
            String pointName = point != null ? point.getPointName() : "unknown";
            throw new IllegalArgumentException(
                    "IEC104 writeTimeTag is not supported, point=" + pointName
                            + ". Use writeAddress C_SE_NC_1/C_SE_TC_1 to select non-timed or timed command.");
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Iec104Type resolveWriteType(DataPoint point, Iec104Address address) {
        Iec104Type type = Iec104Utils.resolveType(address.getTypeId());
        if (type == null) {
            String pointName = point != null ? point.getPointName() : "unknown";
            throw new IllegalArgumentException(
                    "IEC104 write type is required, point=" + pointName
                            + ". Use writeAddress like C_SE_NC_1:101 or C_SE_TC_1:101.");
        }
        Integer configuredTypeId = extractReadConfiguredTypeId(point);
        if (configuredTypeId != null
                && Iec104Type.canonicalTypeId(configuredTypeId)
                != Iec104Type.canonicalTypeId(type.typeId())) {
            throw new IllegalArgumentException("IEC104 write address type conflicts with point type configuration: "
                    + point.getPointId());
        }
        if (!type.writeSupported()) {
            String pointName = point != null ? point.getPointName() : "unknown";
            throw new IllegalArgumentException(
                    "Unsupported IEC104 write type " + type.typeName()
                            + "(" + type.typeId() + "), point=" + pointName);
        }
        return type;
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer extractReadConfiguredTypeId(DataPoint point) {
        if (point == null) {
            return null;
        }
        Integer configured = null;
        for (String key : List.of("typeId", "iecTypeId", "registerType")) {
            Integer candidate = Iec104Utils.resolveTypeIdToken(point.getAdditionalConfig(key));
            if (candidate == null) {
                continue;
            }
            if (configured != null && !configured.equals(candidate)) {
                throw new IllegalArgumentException("Conflicting IEC104 type configuration for point "
                        + point.getPointId() + ": " + key);
            }
            configured = candidate;
        }
        return configured;
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolvePointTypeId(DataPoint point, Iec104Address address) {
        Integer configured = extractReadConfiguredTypeId(point);
        if (address == null || address.getTypeId() == null) {
            return configured;
        }
        Integer addressType = Iec104Type.canonicalTypeId(address.getTypeId());
        if (configured != null && !configured.equals(addressType)) {
            throw new IllegalArgumentException("IEC104 address type conflicts with point type configuration: "
                    + point.getPointId());
        }
        return addressType;
    }

    /**
     * 解析或转换业务数据。
     */
    private Iec104Key resolvePointKey(DataPoint point, Iec104Address address) {
        return new Iec104Key(
                address.getCommonAddress(),
                resolvePointTypeId(point, address),
                address.getIoAddress());
    }

    /**
     * 查询并返回业务数据。
     */
    private DataPoint findSpontaneousPoint(int commonAddress, Integer typeId, int ioa) {
        if (typeId != null) {
            DataPoint exact = 突发上送PointIndex.get(new Iec104Key(commonAddress, typeId, ioa));
            if (exact != null) {
                return exact;
            }
        }
        return 突发上送PointIndex.get(new Iec104Key(commonAddress, null, ioa));
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveCommandCommonAddress(int unitId, Map<String, Object> params) {
        Integer override = null;
        if (params != null) {
            override = parseInteger(params.get("slaveId"));
            if (override == null) {
                override = parseInteger(params.get("commonAddress"));
            }
            if (override == null) {
                override = parseInteger(params.get("ca"));
            }
            if (override == null) {
                override = parseInteger(params.get("unitId"));
            }
        }
        if (override != null) {
            return override;
        }
        return commonAddress;
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer parseInteger(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Iec104Utils.parseIntegerValue(raw);
        } catch (IllegalArgumentException e) {
            log.debug("IEC104 整数值无效：{}", raw);
            return null;
        }
    }

    private int getIntParameter(Map<String, Object> params, String key, int defaultValue) {
        Integer parsed = parseInteger(params.get(key));
        return parsed != null ? parsed : defaultValue;
    }

    private int getAdditionalInt(DataPoint point, String key, int defaultValue) {
        if (point == null) {
            return defaultValue;
        }
        Integer parsed = parseInteger(point.getAdditionalConfig(key));
        return parsed != null ? parsed : defaultValue;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private int requireIntParameter(Map<String, Object> params, String key) {
        Integer parsed = parseInteger(params.get(key));
        if (parsed == null) {
            throw new IllegalArgumentException("Missing or invalid IEC104 integer parameter: " + key);
        }
        return parsed;
    }

    private boolean getBooleanParameter(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Iec104Utils.parseBooleanValue(value);
    }

    private boolean getAdditionalBoolean(DataPoint point, String key, boolean defaultValue) {
        if (point == null) {
            return defaultValue;
        }
        Object value = point.getAdditionalConfig(key);
        if (value == null) {
            return defaultValue;
        }
        return Iec104Utils.parseBooleanValue(value);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Object requireValue(Map<String, Object> params, String key) {
        if (!params.containsKey(key) || params.get(key) == null) {
            throw new IllegalArgumentException("Missing IEC104 parameter: " + key);
        }
        return params.get(key);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Object requireFirstValue(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            if (params.containsKey(key) && params.get(key) != null) {
                return params.get(key);
            }
        }
        throw new IllegalArgumentException("Missing IEC104 parameter, expected one of: " + String.join(", ", keys));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Iec104Address requireCommandAddress(int commonAddress, Map<String, Object> params) {
        return new Iec104Address(commonAddress, requireIntParameter(params, "address"), null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private WriteTarget commandWriteTarget(int commonAddress,
                                           Map<String, Object> params,
                                           Iec104Type type,
                                           int ql,
                                           boolean select) {
        rejectUnsupportedSelect(select);
        Iec104Address address = requireCommandAddress(commonAddress, params);
        return new WriteTarget(
                new Iec104Address(address.getCommonAddress(), address.getIoAddress(), type.typeId()),
                type,
                ql,
                select);
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeValueByType(WriteTarget target, Object value) throws Exception {
        return switch (target.type().valueKind()) {
            case SINGLE_COMMAND -> writeSingleCommand(target, value);
            case DOUBLE_COMMAND -> writeDoubleCommand(target, value);
            case REGULATING_STEP_COMMAND -> writeRegulatingStepCommand(target, value);
            case SETPOINT_NORMALIZED, SETPOINT_SCALED, SETPOINT_SHORT_FLOAT -> writeSetPointCommand(target, value);
            case BIT_STRING_COMMAND -> writeBitStringCommand(target, value);
            default -> throw new IllegalArgumentException(
                    "Unsupported IEC104 write type: " + target.type().typeName());
        };
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeSingleCommand(WriteTarget target, Object value) throws Exception {
        boolean commandValue = Iec104Utils.parseBooleanValue(value);
        IeSingleCommand command = new IeSingleCommand(commandValue, target.ql(), target.select());
        sendPointCommand(target.address(), command, target.type().timed(),
                (ca, ioa, payload) -> connection.singleCommand(ca, CauseOfTransmission.ACTIVATION, ioa, payload),
                (ca, ioa, payload, time) -> connection.singleCommandWithTimeTag(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, time));
        return true;
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeDoubleCommand(WriteTarget target, Object value) throws Exception {
        IeDoubleCommand.DoubleCommandState state = Iec104Utils.parseDoubleCommandState(value);
        IeDoubleCommand command = new IeDoubleCommand(state, target.ql(), target.select());
        sendPointCommand(target.address(), command, target.type().timed(),
                (ca, ioa, payload) -> connection.doubleCommand(ca, CauseOfTransmission.ACTIVATION, ioa, payload),
                (ca, ioa, payload, time) -> connection.doubleCommandWithTimeTag(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, time));
        return true;
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeSetPointCommand(WriteTarget target, Object value) throws Exception {
        return switch (target.type().valueKind()) {
            case SETPOINT_NORMALIZED -> writeNormalizedSetpoint(target, Iec104Utils.parseFloatValue(value));
            case SETPOINT_SCALED -> writeScaledSetPoint(target, Iec104Utils.parseIntegerValue(value));
            case SETPOINT_SHORT_FLOAT -> writeShortFloatSetPoint(target, Iec104Utils.parseFloatValue(value));
            default -> throw new IllegalArgumentException(
                    "Unsupported IEC104 set point type: " + target.type().typeName());
        };
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeNormalizedSetpoint(WriteTarget target, float value) throws Exception {
        IeNormalizedValue normalizedValue = new IeNormalizedValue(value);
        IeQualifierOfSetPointCommand qualifier = new IeQualifierOfSetPointCommand(target.ql(), target.select());
        sendSetPointCommand(target.address(), normalizedValue, qualifier, target.type().timed(),
                (ca, ioa, payload, q) -> connection.setNormalizedValueCommand(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, q),
                (ca, ioa, payload, q, time) -> connection.setNormalizedValueCommandWithTimeTag(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, q, time));
        return true;
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeScaledSetPoint(WriteTarget target, int scaledValue) throws Exception {
        IeScaledValue scaled = new IeScaledValue(scaledValue);
        IeQualifierOfSetPointCommand qualifier = new IeQualifierOfSetPointCommand(target.ql(), target.select());
        sendSetPointCommand(target.address(), scaled, qualifier, target.type().timed(),
                (ca, ioa, payload, q) -> connection.setScaledValueCommand(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, q),
                (ca, ioa, payload, q, time) -> connection.setScaledValueCommandWithTimeTag(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, q, time));
        return true;
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeShortFloatSetPoint(WriteTarget target, float value) throws Exception {
        IeShortFloat shortFloat = new IeShortFloat(value);
        IeQualifierOfSetPointCommand qualifier = new IeQualifierOfSetPointCommand(target.ql(), target.select());
        sendSetPointCommand(target.address(), shortFloat, qualifier, target.type().timed(),
                (ca, ioa, payload, q) -> connection.setShortFloatCommand(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, q),
                (ca, ioa, payload, q, time) -> connection.setShortFloatCommandWithTimeTag(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, q, time));
        return true;
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeRegulatingStepCommand(WriteTarget target, Object value) throws Exception {
        IeRegulatingStepCommand.StepCommandState state = Iec104Utils.parseStepCommandState(value);
        IeRegulatingStepCommand command = new IeRegulatingStepCommand(state, target.ql(), target.select());
        sendPointCommand(target.address(), command, target.type().timed(),
                (ca, ioa, payload) -> connection.regulatingStepCommand(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload),
                (ca, ioa, payload, time) -> connection.regulatingStepCommandWithTimeTag(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, time));
        return true;
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeBitStringCommand(WriteTarget target, Object value) throws Exception {
        int bitString = Iec104Utils.parseIntegerValue(value);
        IeBinaryStateInformation bits = new IeBinaryStateInformation(bitString);
        sendPointCommand(target.address(), bits, target.type().timed(),
                (ca, ioa, payload) -> connection.bitStringCommand(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload),
                (ca, ioa, payload, time) -> connection.bitStringCommandWithTimeTag(
                        ca, CauseOfTransmission.ACTIVATION, ioa, payload, time));
        return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> void sendPointCommand(Iec104Address address,
                                      T payload,
                                      boolean timeTag,
                                      PlainPointCommand<T> plainSender,
                                      TimedPointCommand<T> timedSender) throws Exception {
        if (timeTag) {
            timedSender.send(address.getCommonAddress(), toWireIoa(address.getIoAddress()), payload, currentTime());
        } else {
            plainSender.send(address.getCommonAddress(), toWireIoa(address.getIoAddress()), payload);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> void sendSetPointCommand(Iec104Address address,
                                         T payload,
                                         IeQualifierOfSetPointCommand qualifier,
                                         boolean timeTag,
                                         PlainSetPointCommand<T> plainSender,
                                         TimedSetPointCommand<T> timedSender) throws Exception {
        if (timeTag) {
            timedSender.send(address.getCommonAddress(), toWireIoa(address.getIoAddress()), payload, qualifier, currentTime());
        } else {
            plainSender.send(address.getCommonAddress(), toWireIoa(address.getIoAddress()), payload, qualifier);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private IeTime56 currentTime() {
        return new IeTime56(System.currentTimeMillis());
    }

    private String getDeviceId() {
        return deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN";
    }

    /**
     * 清理或删除业务数据。
     */
    private void removeConnectionSilently() {
        removeManagedConnection("IEC104");
        connection = null;
    }

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    private interface PlainPointCommand<T> {
        /**
         * 执行当前业务逻辑。
         */
        void send(int commonAddress, int ioAddress, T payload) throws Exception;
    }

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    private interface TimedPointCommand<T> {
        /**
         * 执行当前业务逻辑。
         */
        void send(int commonAddress, int ioAddress, T payload, IeTime56 time) throws Exception;
    }

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    private interface PlainSetPointCommand<T> {
        /**
         * 执行当前业务逻辑。
         */
        void send(int commonAddress, int ioAddress, T payload, IeQualifierOfSetPointCommand qualifier) throws Exception;
    }

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    private interface TimedSetPointCommand<T> {
        /**
         * 执行当前业务逻辑。
         */
        void send(int commonAddress,
                  int ioAddress,
                  T payload,
                  IeQualifierOfSetPointCommand qualifier,
                  IeTime56 time) throws Exception;
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record PendingRead(DataPoint point,
                               Iec104Address address,
                               CompletableFuture<Object> future) {
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record WriteTarget(Iec104Address address, Iec104Type type, int ql, boolean select) {
    }
}
