package com.wangbin.collector.core.collector.protocol.modbus;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.common.enums.DataType;
import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.base.AbstractModbusCollector;
import com.wangbin.collector.core.collector.protocol.modbus.base.ModbusTransport;
import com.wangbin.collector.core.collector.protocol.modbus.domain.ModbusAddress;
import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;
import com.wangbin.collector.core.collector.protocol.modbus.plan.ModbusReadPlan;
import com.wangbin.collector.core.collector.protocol.modbus.plan.PointOffset;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusTcpConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessContext;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.exceptions.PlcIoException;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcTagResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 基于 PLC4X 的 Modbus TCP 采集器，复用现有批量计划和数据处理流程。
 */
@Slf4j
@Component
public class Plc4xModbusTcpCollector extends AbstractModbusCollector {

    private static final String FIELD_NAME = "value";

    private volatile Plc4xModbusTcpConnectionAdapter connectionAdapter;
    private volatile boolean acceptingRequests;
    private volatile boolean acceptingConnections;
    private volatile boolean retired;
    private final ThreadLocal<RequestScope> requestScope = new ThreadLocal<>();
    private AcquisitionRuntimeTracker requestRuntimeTracker;
    private CollectionTaskGuard requestTaskGuard;
    private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
    private Parity parity = Parity.none;

    private final ModbusTransport transport = new ModbusTransport() {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public byte[] read(int unitId, RegisterType registerType, int startAddress, int quantity) throws Exception {
            if (registerType == RegisterType.COIL || registerType == RegisterType.DISCRETE_INPUT) {
                return readBitArea(unitId, registerType, startAddress, quantity);
            }

            PlcReadResponse response = request(false, client -> client
                    .readRequestBuilder()
                    .addTagAddress(FIELD_NAME, Plc4xModbusTagBuilder.build(
                            registerType, startAddress, quantity, unitId))
                    .build()
                    .execute());

            return switch (registerType) {
                case COIL, DISCRETE_INPUT -> throw new IllegalStateException("bit 区域已在前置分支处理");
                case HOLDING_REGISTER, INPUT_REGISTER -> Plc4xModbusValueExtractor.registerBytes(
                        response, FIELD_NAME, quantity);
            };
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public boolean writeMultipleCoils(
                int unitId, int startAddress, int quantity, byte[] coilBytes) throws Exception {
            Object[] values = ModbusUtils.getCoilValues(coilBytes, quantity, parity).toArray();
            request(true, client -> client
                    .writeRequestBuilder()
                    .addTagAddress(
                            FIELD_NAME,
                            Plc4xModbusTagBuilder.build(RegisterType.COIL, startAddress, quantity, unitId),
                            values)
                    .build()
                    .execute());
            return true;
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public boolean writeMultipleRegisters(int unitId, int startAddress, short[] registers) throws Exception {
            request(true, client -> client
                    .writeRequestBuilder()
                    .addTagAddress(
                            FIELD_NAME,
                            Plc4xModbusTagBuilder.build(
                                    RegisterType.HOLDING_REGISTER, startAddress, registers.length, unitId),
                            toRegisterWriteValues(registers))
                    .build()
                    .execute());
            return true;
        }
    };

    @Override
    public String getCollectorType() {
        return "ModbusTCP";
    }

    @Override
    public String getProtocolType() {
        return "MODBUS_TCP";
    }

    /**
     * PLC4X 对部分 Modbus bit 区域批量 BOOL 标签只返回单个值，逐点读取后重新打包。
     */
    private byte[] readBitArea(int unitId, RegisterType registerType, int startAddress, int quantity) throws Exception {
        boolean scoped = requestScope.get() != null;
        if (!scoped) requestScope.set(captureRequestScope());
        try {
            List<Boolean> values = new ArrayList<>(quantity);
            for (int offset = 0; offset < quantity; offset++) {
                int address = startAddress + offset;
                PlcReadResponse response = request(false, client -> client
                        .readRequestBuilder()
                        .addTagAddress(FIELD_NAME, Plc4xModbusTagBuilder.build(
                                registerType, address, 1, unitId))
                        .build()
                        .execute());
                byte[] coilBytes = Plc4xModbusValueExtractor.coilBytes(response, FIELD_NAME, 1, parity);
                values.add(Boolean.TRUE.equals(ModbusUtils.getCoilValue(coilBytes, 1, parity)));
            }
            return ModbusUtils.buildCoilBytes(values, parity);
        } finally {
            if (!scoped) requestScope.remove();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        log.info("Starting PLC4X Modbus TCP 连接:{}", deviceInfo.getDeviceId());
        DeviceConnection desiredConfig = requireConnectionConfig();
        if (retired) throw new CancellationException("采集器已退役");
        acceptingConnections = true;
        try {
            Plc4xModbusTcpConnectionAdapter adapter = requireAdapterType(createManagedConnection(desiredConfig),
                    Plc4xModbusTcpConnectionAdapter.class, "PLC4X Modbus TCP");
            long generation = runtimeGeneration;
            // 初次建连也先绑定叶子身份；许可只读索引/volatile，CM 重连 sleep 后 STOP 不得再开 socket。
            connectionAdapter = adapter;
            adapter.setConnectionPermit(() -> !retired && acceptingConnections && connectionAdapter == adapter
                    && runtimeGeneration == generation
                    && (connectionManager == null || connectionManager.getConnection(deviceInfo.getDeviceId()) == adapter)
                    && (requestTaskGuard == null || generation == 0L
                        || requestTaskGuard.isCurrent(deviceInfo.getDeviceId(), generation)));
            connectManagedConnection();
        } catch (Exception error) {
            acceptingConnections = false;
            removeManagedConnection("PLC4X Modbus TCP");
            connectionAdapter = null;
            throw error;
        }

        DeviceConnection connectionConfig = getCurrentConnectionConfig();
        if (connectionConfig == null) {
            connectionConfig = desiredConfig;
        }

        this.timeout = connectionConfig.getReadTimeout() != null
                ? connectionConfig.getReadTimeout()
                : connectionConfig.getTimeout();
        if (this.timeout <= 0) {
            CollectorProperties.ModbusConfig defaults = collectorProperties != null
                    ? collectorProperties.getModbus()
                    : new CollectorProperties.ModbusConfig();
            this.timeout = defaults.getTimeout();
        }

        byteOrder = ModbusUtils.parseByteOrder(connectionConfig.getString("byteOrder", "BIG_ENDIAN"));
        String parityName = connectionConfig.getString("parity", Parity.none.name());
        parity = Parity.fromName(parityName != null ? parityName.toLowerCase() : Parity.none.name());
        acceptingRequests = true;

        log.info("PLC4X Modbus TCP 已连接:{}:{} byteOrder={} parity={}",
                connectionConfig.getHost(),
                connectionConfig.getPort(),
                byteOrder,
                parity.name());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        acceptingRequests = false;
        acceptingConnections = false;
        removeConnectionSilently();
        registerCache.clear();
        log.info("PLC4X Modbus TCP 已断开");
    }

    @Override
    @Autowired(required = false)
    public void setAcquisitionRuntimeTracker(AcquisitionRuntimeTracker tracker) {
        super.setAcquisitionRuntimeTracker(tracker);
        requestRuntimeTracker = tracker;
    }

    @Autowired(required = false)
    public void setRequestTaskGuard(CollectionTaskGuard guard) {
        requestTaskGuard = guard;
    }

    @Override
    public synchronized void disconnect() throws CollectorException {
        acceptingRequests = false;
        acceptingConnections = false;
        boolean disconnected = false;
        try {
            super.disconnect();
            disconnected = true;
        } finally {
            connectionAdapter = null;
            connected = false;
            connectionStatus = disconnected ? "DISCONNECTED" : "ERROR";
            registerCache.clear();
        }
    }

    @Override
    public synchronized void destroy() {
        retired = true;
        acceptingRequests = false;
        acceptingConnections = false;
        super.destroy();
    }

    @Override
    public boolean isAutomaticReconnectAllowed() {
        if (retired || deviceInfo == null) return false;
        if (requestTaskGuard != null && runtimeGeneration > 0L
                && !requestTaskGuard.isCurrent(deviceInfo.getDeviceId(), runtimeGeneration)) return false;
        // 已登记适配器的开关、attempt 和 delay 全由 CM 管理，调度器不得重建以绕过其预算。
        if (connectionManager != null && connectionManager.getConnection(deviceInfo.getDeviceId()) != null) return false;
        if (connectionAdapter != null) return false;
        DeviceConnection config = getCurrentConnectionConfig();
        return config != null && config.isAutoReconnect();
    }

    @Override
    public synchronized boolean reconnectAutomatically() {
        if (!isAutomaticReconnectAllowed()) return false;
        connect();
        return true;
    }

    /** 公共单点模板会丢失原异常及 TIMEOUT，叶子仅补足请求归属和结果交接。 */
    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        resetInvocationProcessResults();
        long startedAt = System.currentTimeMillis();
        RequestScope scope = null;
        try {
            validateReadPointOwnership(point);
            scope = captureRequestScope();
            scope.points = List.of(point);
            requestScope.set(scope);
            Object raw = doReadPoint(point);
            ProcessResult result = completePointRead(scope, point, raw, startedAt);
            addInvocationProcessResult(point.getPointId(), result);
            if (!result.isSuccess()) totalErrorCount.incrementAndGet();
            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startedAt);
            lastActivityTime = System.currentTimeMillis();
            return result.getFinalValue();
        } catch (Exception error) {
            // 累计的是本调用真实失败，不因当前 physical 已替换而删除。
            totalErrorCount.incrementAndGet();
            recordCurrentFailure(scope, error, point);
            if (scope != null && point != null) {
                try {
                    completePointRead(scope, point, readFailure(scope, error), startedAt);
                } catch (CancellationException stale) {
                    // 已替换的 owner/session 不再更新旧点位事实、质量或缓存。
                } catch (Exception handoffError) {
                    error.addSuppressed(handoffError);
                }
            }
            clearInvocationProcessResults();
            throw protocolException("点位读取失败", point, error);
        } finally {
            requestScope.remove();
            finishInvocationProcessResults();
        }
    }

    @Override
    public Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException {
        resetInvocationProcessResults();
        long startedAt = System.currentTimeMillis();
        RequestScope scope = null;
        try {
            scope = captureRequestScope();
            requestScope.set(scope);
            List<DataPoint> valid = points == null ? List.of() : points.stream()
                    .filter(point -> point != null && point.isEnabled()).toList();
            Map<String, Object> raw = new LinkedHashMap<>();
            List<DataPoint> readable = new ArrayList<>();
            for (DataPoint point : valid) {
                validateReadPointOwnership(point);
                try {
                    parseModbusAddressForPoint(point, point.getAddress());
                    readable.add(point);
                } catch (Exception error) {
                    raw.put(point.getPointId(), new ReadFailure(error, System.currentTimeMillis()));
                }
            }
            scope.points = valid;
            raw.putAll(doReadPoints(readable));
            // 先统计整批已经发生的失败，随后任何源门拒绝也不能丢掉原超时。
            for (DataPoint point : valid) {
                if (raw.get(point.getPointId()) instanceof ReadFailure failure) {
                    totalErrorCount.incrementAndGet();
                    scope.errorCounted = true;
                    recordCurrentFailure(scope, failure.error(), point);
                }
            }
            Map<String, Object> values = new LinkedHashMap<>();
            Map<String, ProcessResult> snapshots = new LinkedHashMap<>();
            for (DataPoint point : valid) {
                Object value = raw.get(point.getPointId());
                if (value == null) {
                    ReadFailure missing = new ReadFailure(
                            new IllegalStateException("本轮读取计划未返回点位值"), System.currentTimeMillis());
                    value = missing;
                    totalErrorCount.incrementAndGet();
                    scope.errorCounted = true;
                    recordCurrentFailure(scope, missing.error(), point);
                }
                ProcessResult result;
                try {
                    result = completePointRead(scope, point, value, startedAt);
                } catch (CancellationException stale) {
                    throw stale;
                } catch (Exception error) {
                    value = new ReadFailure(error, System.currentTimeMillis());
                    totalErrorCount.incrementAndGet();
                    scope.errorCounted = true;
                    recordCurrentFailure(scope, error, point);
                    result = completePointRead(scope, point, value, startedAt);
                }
                if (!result.isSuccess() && !(value instanceof ReadFailure)) {
                    totalErrorCount.incrementAndGet();
                    scope.errorCounted = true;
                }
                snapshots.put(point.getPointId(), result);
                values.put(point.getPointId(), result.getFinalValue());
            }
            totalReadCount.addAndGet(valid.size());
            totalReadTime.addAndGet(System.currentTimeMillis() - startedAt);
            lastActivityTime = System.currentTimeMillis();
            setInvocationProcessResults(snapshots);
            return values;
        } catch (Exception error) {
            clearInvocationProcessResults();
            if (scope == null || !scope.errorCounted) totalErrorCount.incrementAndGet();
            recordCurrentFailure(scope, error, null);
            throw protocolException("批量点位读取失败", null,
                    scope != null ? readFailure(scope, error).error() : error);
        } finally {
            requestScope.remove();
            finishInvocationProcessResults();
        }
    }

    /** 保留原计划及既有执行器，但计划失败不能再被吞成缺值。 */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        RequestScope scope = requestScope.get();
        Set<String> requested = new LinkedHashSet<>();
        points.forEach(point -> requested.add(point.getPointId()));
        Map<Integer, List<ModbusReadPlan>> groups = new LinkedHashMap<>();
        for (ModbusReadPlan plan : readPlans) {
            if (plan.getPointOffsets().stream().anyMatch(offset -> requested.contains(offset.getPointId()))) {
                groups.computeIfAbsent(plan.getUnitId(), ignored -> new ArrayList<>()).add(plan);
            }
        }
        Map<String, Object> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (List<ModbusReadPlan> group : groups.values()) {
            tasks.add(CompletableFuture.runAsync(() -> {
                requestScope.set(scope);
                try {
                    for (ModbusReadPlan plan : group) {
                        try {
                            byte[] raw = transport.read(plan.getUnitId(), plan.getRegisterType(),
                                    plan.getStartAddress(), plan.getQuantity());
                            boolean bits = plan.getRegisterType() == RegisterType.COIL
                                    || plan.getRegisterType() == RegisterType.DISCRETE_INPUT;
                            List<Boolean> values = bits
                                    ? ModbusUtils.getCoilValues(raw, plan.getQuantity(), parity) : List.of();
                            for (PointOffset offset : plan.getPointOffsets()) {
                                if (!requested.contains(offset.getPointId())) continue;
                                Object value = bits ? values.get(offset.getOffset()) : ModbusUtils.parseValue(
                                        raw, offset.getOffset(), DataType.valueOf(offset.getDataType()), byteOrder);
                                results.put(offset.getPointId(), value);
                            }
                        } catch (Exception error) {
                            ReadFailure failure = readFailure(scope, error);
                            for (PointOffset offset : plan.getPointOffsets()) {
                                if (requested.contains(offset.getPointId())) results.put(offset.getPointId(), failure);
                            }
                        }
                    }
                } finally {
                    requestScope.remove();
                }
            }, resolveModbusReadExecutor()));
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        return results;
    }

    private ProcessResult completePointRead(RequestScope scope, DataPoint point, Object raw, long startedAt) {
        if (!(raw instanceof ReadFailure) && !physicalCurrent(scope)) {
            ReadFailure failure = scope.fatalFailure.get();
            if (failure == null) throw new CancellationException("原物理连接已失效");
            raw = failure;
        }
        boolean failed = raw instanceof ReadFailure;
        Object protocolRaw = failed ? null : raw;
        ProcessResult result;
        if (raw instanceof ReadFailure failure) {
            result = failedProtocolReadResult(failure.error());
            result.setError(describeFailure(failure.error()));
            result.addMetadata("requestFailedAt", failure.at());
        } else {
            Object converted = convertData(point, raw);
            ProcessContext context = new ProcessContext();
            context.addAttribute(CommonMapKeys.DEVICE_ID, deviceInfo.getDeviceId());
            AtomicBoolean rejected = new AtomicBoolean();
            result = dataQualityProcessor.processWithSideEffectGuard(context, point, converted,
                    action -> {
                        try {
                            boolean accepted = commitRequestSource(scope, point, true, action);
                            if (!accepted) rejected.set(true);
                            return accepted;
                        } catch (CancellationException stale) {
                            rejected.set(true);
                            return false;
                        }
                    });
            if (rejected.get()) throw new CancellationException("原请求质量副作用提交被拒绝");
        }
        ProcessResult assessed = result;
        AtomicReference<ProcessResult> completed = new AtomicReference<>();
        if (!commitRequestSource(scope, point, !failed, () -> {
            if (!assessed.isSuccess()) {
                if (scope.reportedFailures.add(point.getPointId())) {
                    reportPointFailure(point, failed ? "COMM_ERROR" : "QUALITY_BAD");
                }
                // BAD 观察不得早于刚记录的失败事实；旧 GOOD 的源时间完全不动。
                assessed.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, System.currentTimeMillis());
            }
            // DQP 的 rawValue 是工程值，质量错误的 finalValue 仍须保留其 Java 类型及缩放。
            ProcessResult snapshot = assessed.snapshot();
            enrichTelemetryMetadata(snapshot, protocolRaw, assessed.getProcessedValue(),
                    readSampleAt(point, startedAt), readSampleSource(point));
            snapshot.addMetadata(ProcessResultMetadataKeys.SOURCE_GENERATION, scope.generation);
            snapshot.addMetadata(ProcessResultMetadataKeys.CONFIG_VERSION, scope.version);
            lastProcessResults.put(point.getPointId(), snapshot.snapshot());
            // 公共管线已持 guard/config/receipt；这里只取得原 physical 锁，绝不反向重入公共门。
            snapshot.bindSourceCommitGuard(action -> commitPhysicalSource(scope, !failed, action));
            completed.set(snapshot);
        })) throw new CancellationException("原请求结果提交被拒绝");
        return completed.get();
    }

    @Override
    public boolean writePoint(DataPoint point, Object value) throws CollectorException {
        RequestScope scope = captureRequestScope();
        if (point != null && deviceInfo.getDeviceId().equals(point.getDeviceId())) scope.points = List.of(point);
        requestScope.set(scope);
        try {
            return super.writePoint(point, value);
        } catch (CollectorException error) {
            if (scope.unknownWrite != null) {
                throw protocolException("写入执行结果未知，未重发", point, scope.unknownWrite.error());
            }
            throw error;
        } finally {
            requestScope.remove();
        }
    }

    @Override
    public Map<String, Boolean> writePoints(Map<DataPoint, Object> points) throws CollectorException {
        RequestScope scope = captureRequestScope();
        if (points != null) scope.points = points.keySet().stream().filter(point -> point != null
                && deviceInfo.getDeviceId().equals(point.getDeviceId())).toList();
        requestScope.set(scope);
        try {
            Map<String, Boolean> results = super.writePoints(points);
            if (scope.unknownWrite != null) {
                totalErrorCount.incrementAndGet();
                recordCurrentFailure(scope, scope.unknownWrite.error(), null);
                throw protocolException("批写执行结果未知，未重发", null, scope.unknownWrite.error());
            }
            return results;
        } catch (CollectorException error) {
            if (scope.unknownWrite != null) {
                throw protocolException("批写执行结果未知，未重发", null, scope.unknownWrite.error());
            }
            throw error;
        } finally {
            requestScope.remove();
        }
    }

    @Override
    public Object executeCommand(String command, Map<String, Object> params) throws CollectorException {
        RequestScope scope = captureRequestScope();
        requestScope.set(scope);
        try {
            return super.executeCommand(command, params);
        } catch (CollectorException error) {
            ReadFailure failure = scope.failure.get();
            if (failure != null) throw protocolException(scope.unknownWrite != null
                    ? "命令写入执行结果未知，未重发" : "命令读取失败", null, failure.error());
            throw error;
        } finally {
            requestScope.remove();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        String address = point.getAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Point address cannot be empty");
        }

        ModbusAddress modbusAddress = parseModbusAddressForPoint(point, address);
        int unitId = resolveUnitId(point);
        int registerCount = resolveQuantity(modbusAddress.getRegisterType(), point.getDataType());
        byte[] raw = transport.read(unitId, modbusAddress.getRegisterType(), modbusAddress.getAddress(), registerCount);

        return switch (modbusAddress.getRegisterType()) {
            case COIL, DISCRETE_INPUT -> ModbusUtils.parseCoilValue(raw, 0, parity);
            case HOLDING_REGISTER, INPUT_REGISTER ->
                    ModbusUtils.parseRegisterValue(raw, point.getDataType(), byteOrder);
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        RequestScope scope = requestScope.get();
        if (scope != null && scope.unknownWrite != null) throw scope.unknownWrite.error();
        String address = point.getAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Point address cannot be empty");
        }

        ModbusAddress modbusAddress = parseModbusAddressForPoint(point, address);
        int unitId = resolveUnitId(point);

        return switch (modbusAddress.getRegisterType()) {
            case COIL -> transport.writeMultipleCoils(
                    unitId,
                    modbusAddress.getAddress(),
                    1,
                    ModbusUtils.buildCoilBytes(List.of(toBoolean(value)), parity));
            case HOLDING_REGISTER -> transport.writeMultipleRegisters(
                    unitId,
                    modbusAddress.getAddress(),
                    ModbusUtils.valueToRegisters(value, point.getDataType(), byteOrder));
            default -> throw new IllegalArgumentException(
                    "This register type does not support write: " + modbusAddress.getRegisterType());
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = getBaseDeviceStatus("Modbus TCP");
        status.put("unitIds", collectUnitIds());
        status.put("byteOrder", byteOrder.toString());
        status.put("parity", parity.name());
        status.put(CommonMapKeys.DRIVER, "PLC4X");
        status.put("connectionString", connectionAdapter != null ? connectionAdapter.getConnectionString() : null);

        try {
            status.put(CommonMapKeys.DEVICE_CONNECTED, testConnection());
        } catch (Exception e) {
            status.put(CommonMapKeys.DEVICE_CONNECTED, false);
            status.put("connectionError", e.getMessage());
        }

        status.put(CommonMapKeys.CONNECTED, isConnected());
        status.put(CommonMapKeys.CONNECTION_STATUS, getConnectionStatus());
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        return switch (command.toUpperCase()) {
            case "READ_MULTIPLE_REGISTERS" -> executeReadMultipleRegisters(unitId, params);
            case "WRITE_MULTIPLE_REGISTERS" -> executeWriteMultipleRegisters(unitId, params);
            case "READ_COILS" -> executeReadCoils(unitId, params);
            case "WRITE_COILS" -> executeWriteCoils(unitId, params);
            case "DIAGNOSTIC" -> executeDiagnostic(unitId);
            default -> throw new IllegalArgumentException("Unsupported Modbus command: " + command);
        };
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeReadMultipleRegisters(int unitId, Map<String, Object> params) throws Exception {
        int address = toInt(params.getOrDefault("address", 0), 0);
        int quantity = toInt(params.getOrDefault("quantity", 1), 1);
        byte[] raw = transport.read(unitId, RegisterType.HOLDING_REGISTER, address, quantity);
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(byteOrder);
        List<Integer> values = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            values.add(buffer.getShort() & 0xFFFF);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.SUCCESS, true);
        result.put(CommonMapKeys.ADDRESS, address);
        result.put(CommonMapKeys.QUANTITY, quantity);
        result.put("values", values);
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeWriteMultipleRegisters(int unitId, Map<String, Object> params) throws Exception {
        int address = toInt(params.getOrDefault("address", 0), 0);
        List<?> values = (List<?>) params.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values cannot be empty");
        }

        short[] registers = new short[values.size()];
        for (int i = 0; i < values.size(); i++) {
            registers[i] = (short) toInt(values.get(i), 0);
        }
        boolean success = transport.writeMultipleRegisters(unitId, address, registers);

        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.SUCCESS, success);
        result.put(CommonMapKeys.ADDRESS, address);
        result.put(CommonMapKeys.QUANTITY, values.size());
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeReadCoils(int unitId, Map<String, Object> params) throws Exception {
        int address = toInt(params.getOrDefault("address", 0), 0);
        int quantity = toInt(params.getOrDefault("quantity", 1), 1);
        byte[] raw = transport.read(unitId, RegisterType.COIL, address, quantity);
        List<Boolean> values = ModbusUtils.getCoilValues(raw, quantity, parity);

        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.SUCCESS, true);
        result.put(CommonMapKeys.ADDRESS, address);
        result.put(CommonMapKeys.QUANTITY, quantity);
        result.put("values", values);
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeWriteCoils(int unitId, Map<String, Object> params) throws Exception {
        int address = toInt(params.getOrDefault("address", 0), 0);
        List<?> values = (List<?>) params.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values cannot be empty");
        }

        List<Boolean> coilValues = new ArrayList<>(values.size());
        for (Object value : values) {
            coilValues.add(toBoolean(value));
        }
        boolean success = transport.writeMultipleCoils(
                unitId,
                address,
                coilValues.size(),
                ModbusUtils.buildCoilBytes(coilValues, parity));

        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.SUCCESS, success);
        result.put(CommonMapKeys.ADDRESS, address);
        result.put(CommonMapKeys.QUANTITY, coilValues.size());
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeDiagnostic(int unitId) {
        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.PROTOCOL, "Modbus TCP");
        result.put("unitId", unitId);
        result.put(CommonMapKeys.TIMEOUT, timeout);
        result.put("masterConnected", connectionAdapter != null && connectionAdapter.isConnected());
        result.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());

        try {
            boolean connected = testConnection();
            result.put(CommonMapKeys.DEVICE_CONNECTED, connected);
            result.put(CommonMapKeys.CONNECTION_TEST, connected ? "SUCCESS" : "FAILED");
        } catch (Exception e) {
            result.put(CommonMapKeys.DEVICE_CONNECTED, false);
            result.put(CommonMapKeys.CONNECTION_TEST, "FAILED");
            result.put(CommonMapKeys.ERROR, e.getMessage());
        }

        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean testConnection() throws Exception {
        int unitId = resolveHealthCheckUnitId();
        transport.read(unitId, RegisterType.HOLDING_REGISTER, 0, 1);
        return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<Integer> collectUnitIds() {
        Set<Integer> unitIds = new LinkedHashSet<>();
        for (ModbusReadPlan plan : readPlans) {
            unitIds.add(plan.getUnitId());
        }
        if (unitIds.isEmpty()) {
            Integer configured = getConfiguredSlaveId();
            if (configured != null) {
                unitIds.add(configured);
            }
        }
        if (unitIds.isEmpty()) {
            unitIds.add(1);
        }
        return new ArrayList<>(unitIds);
    }

    private Integer getConfiguredSlaveId() {
        DeviceConnection connection = getCurrentConnectionConfig();
        return connection != null ? connection.getInt("slaveId", 1) : null;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveHealthCheckUnitId() {
        List<Integer> unitIds = collectUnitIds();
        return unitIds.isEmpty() ? 1 : unitIds.get(0);
    }

    /**
     * 清理或删除业务数据。
     */
    private void removeConnectionSilently() {
        removeManagedConnection("PLC4X Modbus TCP");
        connectionAdapter = null;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveQuantity(RegisterType registerType, String dataType) {
        return switch (registerType) {
            case COIL, DISCRETE_INPUT -> 1;
            case HOLDING_REGISTER, INPUT_REGISTER -> DataType.fromString(dataType).getRegisterCount();
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private Object[] toRegisterWriteValues(short[] registers) {
        Object[] values = new Object[registers.length];
        for (int i = 0; i < registers.length; i++) {
            values[i] = registers[i] & 0xFFFF;
        }
        return values;
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            throw new IllegalStateException("PLC4X " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X " + operation + " failed with response code: " + code);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /** 请求提交只短暂持有物理锁，Future 等待和 CM 操作绝不在该锁内。 */
    private <T extends PlcTagResponse> T request(boolean write,
            Function<PlcConnection, CompletableFuture<? extends T>> submit) throws Exception {
        RequestScope existing = requestScope.get();
        RequestScope scope = existing != null ? existing : captureRequestScope();
        CompletableFuture<? extends T> future = null;
        boolean confirmed = false;
        try {
            if (!configurationCurrent(scope)) throw new CancellationException("原请求配置已失效");
            synchronized (scope.adapter) {
                if (write && scope.unknownWrite != null) throw scope.unknownWrite.error();
                if (!ownerCurrent(scope) || !physicalCurrent(scope)) {
                    ReadFailure failure = scope.fatalFailure.get();
                    if (failure == null) failure = scope.failure.get();
                    if (failure != null) throw failure.error();
                    throw new CancellationException("原请求连接已失效");
                }
                future = submit.apply(scope.client);
            }
            T response = await(future);
            confirmed = response != null;
            ensureResponseOk(response, FIELD_NAME, write ? "write" : "read");
            if (!physicalCurrent(scope) || !ownerCurrent(scope) || !configurationCurrent(scope)) {
                throw new CancellationException("原请求响应已失效");
            }
            return response;
        } catch (Exception error) {
            ReadFailure failure = new ReadFailure(error, System.currentTimeMillis());
            scope.failure.compareAndSet(null, failure);
            boolean fatal = connectionFailure(error);
            if (fatal) scope.fatalFailure.compareAndSet(null, failure);
            if (write && ((!confirmed && future != null) || fatal)) {
                // 外层 Future 取消并不释放 driver 的事务；本批后续 fallback 一律拒绝发送。
                if (scope.unknownWrite == null) scope.unknownWrite = failure;
            }
            if (fatal) {
                try {
                    // 逻辑失败账本保留原捕获时间；新 physical 不是新样本，窗口自身拒绝更早的失败。
                    if ((future != null && !confirmed) || !(error instanceof CancellationException)) {
                        recordLogicalFailure(scope, failure);
                    }
                } catch (CancellationException stale) {
                    // 旧 generation/config 的失败不能进入当前逻辑账本。
                } catch (Exception factError) {
                    error.addSuppressed(factError);
                }
                try {
                    commitRequestSource(scope, null, false, () -> {
                        boolean marked = scope.adapter.markClientInvalid(scope.client, error);
                        if (marked && requestRuntimeTracker != null) {
                            requestRuntimeTracker.resetProtocolReady(deviceInfo.getDeviceId(), scope.generation);
                        }
                    });
                } catch (CancellationException stale) {
                    // 当前源事实拒绝旧 physical；本调用失败及原 cause 仍由调用模板累计/传播。
                } catch (Exception factError) {
                    error.addSuppressed(factError);
                }
                boolean interrupted = Thread.interrupted();
                try {
                    // 源门已释放；若 CM 已换出新 client，按 expected 的旧清理成为无操作。
                    scope.adapter.closeInvalidClient(scope.client);
                } catch (Exception cleanupError) {
                    error.addSuppressed(cleanupError);
                } finally {
                    if (interrupted || error instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            }
            throw error;
        }
    }

    private <T> T await(CompletableFuture<? extends T> future) throws Exception {
        return future.get(timeout, TimeUnit.MILLISECONDS);
    }

    private RequestScope captureRequestScope() {
        Plc4xModbusTcpConnectionAdapter adapter = connectionAdapter;
        if (adapter == null) throw new IllegalStateException("PLC4X Modbus TCP connection has not been established");
        long generation = runtimeGeneration;
        long version = runtimeConfigVersion;
        if (configManager != null && configManager.getDeviceConfigVersion(deviceInfo.getDeviceId()) != version) {
            throw new CancellationException("原请求配置已失效");
        }
        synchronized (adapter) {
            RequestScope scope = new RequestScope(adapter, adapter.getClient(), generation, version);
            if (!ownerCurrent(scope) || !physicalCurrent(scope)) {
                throw new IllegalStateException("PLC4X Modbus TCP 原连接不可用，等待既有所有者恢复");
            }
            return scope;
        }
    }

    private boolean ownerCurrent(RequestScope scope) {
        return acceptingRequests && connectionAdapter == scope.adapter
                && runtimeGeneration == scope.generation && runtimeConfigVersion == scope.version
                && (connectionManager == null || connectionManager.getConnection(deviceInfo.getDeviceId()) == scope.adapter)
                && (requestTaskGuard == null || scope.generation == 0L
                    || requestTaskGuard.isCurrent(deviceInfo.getDeviceId(), scope.generation));
    }

    private boolean physicalCurrent(RequestScope scope) {
        return scope.client != null && scope.adapter.getClient() == scope.client && scope.adapter.isConnected();
    }

    /** 配置读取不能在 Adapter 锁内，避免与配置提交后的生命周期清理逆序。 */
    private boolean configurationCurrent(RequestScope scope) {
        return configManager == null || configManager.getDeviceConfigVersion(deviceInfo.getDeviceId()) == scope.version;
    }

    @Override
    protected void recordOperationFailure(Throwable error, DataPoint point) {
        recordCurrentFailure(requestScope.get(), error, point);
    }

    private void recordCurrentFailure(RequestScope scope, Throwable error, DataPoint point) {
        if (scope == null) return;
        try {
            commitRequestSource(scope, point, false, () -> {
                lastError = scope.unknownWrite != null ? "写入执行结果未知，未重发: "
                        + describeFailure(scope.unknownWrite.error()) : describeFailure(error);
                recordException(error, point);
            });
        } catch (CancellationException stale) {
            // 当前源状态不接纳旧调用，累计错误已在门外记录；不恢复或撤销更新的 lastError。
        } catch (Exception reportingError) {
            error.addSuppressed(reportingError);
        }
    }

    /** 逻辑请求失败与当前 physical 的 lastError/异常通知分离，不刷新采样或关闭新 client。 */
    private void recordLogicalFailure(RequestScope scope, ReadFailure failure) {
        if (requestRuntimeTracker == null) return;
        commitGenerationConfiguration(scope, () -> {
            if (retired || !acceptingRequests || runtimeGeneration != scope.generation
                    || runtimeConfigVersion != scope.version) return;
            for (DataPoint point : scope.points) {
                if (requestRuntimeTracker.matchesPoint(deviceInfo.getDeviceId(), scope.generation, point)
                        && scope.reportedFailures.add(point.getPointId())) {
                    requestRuntimeTracker.recordPointFailure(deviceInfo.getDeviceId(), scope.generation,
                            point.getPointId(), "COMM_ERROR", failure.at());
                }
            }
        });
    }

    /** 公共最终门只嵌入原 Adapter 许可，不在持物理锁时获取配置或代次提交锁。 */
    private boolean commitPhysicalSource(RequestScope scope, boolean physical, Runnable action) {
        synchronized (scope.adapter) {
            if (!ownerCurrent(scope) || (physical ? !physicalCurrent(scope) : !scope.adapter.ownsClient(scope.client))) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /** 锁序：代次门 → 配置读门 → Adapter；门内不获取 Collector monitor 或 CM I/O 锁。 */
    private boolean commitRequestSource(RequestScope scope, DataPoint point, boolean physical, Runnable action) {
        Runnable checked = () -> {
            synchronized (scope.adapter) {
                if (!ownerCurrent(scope) || (physical ? !physicalCurrent(scope) : !scope.adapter.ownsClient(scope.client))
                        || (point != null && requestRuntimeTracker != null && scope.generation > 0L
                            && !requestRuntimeTracker.matchesPoint(deviceInfo.getDeviceId(), scope.generation, point))) {
                    throw new CancellationException("原请求源身份已失效");
                }
                action.run();
            }
        };
        return commitGenerationConfiguration(scope, checked);
    }

    private boolean commitGenerationConfiguration(RequestScope scope, Runnable checked) {
        Runnable configured = () -> {
            if (configManager == null) checked.run();
            else if (!configManager.runIfConfigurationCurrent(deviceInfo.getDeviceId(), scope.version, checked)) {
                throw new CancellationException("原请求配置版本已失效");
            }
        };
        if (requestTaskGuard != null && scope.generation > 0L) {
            return requestTaskGuard.commitIfCurrent(deviceInfo.getDeviceId(), scope.generation, configured);
        }
        configured.run();
        return true;
    }

    private ReadFailure readFailure(RequestScope scope, Exception error) {
        ReadFailure first = scope.fatalFailure.get();
        return first != null && !physicalCurrent(scope) ? first
                : new ReadFailure(error, System.currentTimeMillis());
    }

    private boolean connectionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof CancellationException
                    || cause instanceof InterruptedException || cause instanceof IOException
                    || cause instanceof PlcIoException || cause instanceof PlcConnectionException) {
                return true;
            }
        }
        return false;
    }

    private CollectorException protocolException(String message, DataPoint point, Throwable error) {
        DataQuality quality = DataQuality.DEVICE_ERROR;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException) {
                quality = DataQuality.TIMEOUT;
                break;
            }
            if (cause instanceof IllegalArgumentException) quality = DataQuality.CONFIG_ERROR;
        }
        CollectorException result = new CollectorException(message, deviceInfo.getDeviceId(),
                point != null ? point.getPointId() : null, quality);
        result.initCause(error);
        return result;
    }

    private String describeFailure(Throwable error) {
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (!chain.isEmpty()) chain.append(" -> ");
            chain.append(cause);
        }
        return chain.toString();
    }

    private record ReadFailure(Exception error, long at) {
    }

    private static final class RequestScope {
        private final Plc4xModbusTcpConnectionAdapter adapter;
        private final PlcConnection client;
        private final long generation;
        private final long version;
        private final AtomicReference<ReadFailure> failure = new AtomicReference<>();
        private final AtomicReference<ReadFailure> fatalFailure = new AtomicReference<>();
        private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();
        private volatile List<DataPoint> points = List.of();
        private volatile ReadFailure unknownWrite;
        private boolean errorCounted;

        private RequestScope(Plc4xModbusTcpConnectionAdapter adapter, PlcConnection client, long generation, long version) {
            this.adapter = adapter;
            this.client = client;
            this.generation = generation;
            this.version = version;
        }
    }

    @Override
    protected ModbusTransport getModbusTransport() {
        return transport;
    }

    @Override
    protected ByteOrder getModbusByteOrder() {
        return byteOrder;
    }

    @Override
    protected Parity getModbusParity() {
        return parity;
    }

    @Override
    public boolean isConnected() {
        Plc4xModbusTcpConnectionAdapter adapter = connectionAdapter;
        return adapter != null && adapter.isConnected();
    }

    @Override
    public String getConnectionStatus() {
        Plc4xModbusTcpConnectionAdapter adapter = connectionAdapter;
        return adapter != null ? adapter.getStatus().name() : super.getConnectionStatus();
    }
}
