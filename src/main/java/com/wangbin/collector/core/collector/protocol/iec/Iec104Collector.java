package com.wangbin.collector.core.collector.protocol.iec;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.iec.base.AbstractIce104Collector;
import com.wangbin.collector.core.collector.protocol.iec.domain.Iec104Address;
import com.wangbin.collector.core.collector.protocol.iec.domain.Iec104Type;
import com.wangbin.collector.core.collector.protocol.iec.util.Iec104Utils;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec104ConnectionAdapter;
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
        initIec104Config(deviceInfo);
        DeviceConnection connectionConfig = requireConnectionConfig();
        try {
            ConnectionAdapter<?> adapter = createManagedConnection(connectionConfig);
            Iec104ConnectionAdapter iec104Adapter = requireAdapterType(
                    adapter,
                    Iec104ConnectionAdapter.class,
                    "IEC104");
            iec104Adapter.setConnectionEventListener(createConnectionEventListener());
            connectManagedConnection();
            this.connection = iec104Adapter.getClient();
            onConnectionReady();
        } catch (Exception e) {
            removeConnectionSilently();
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void doDisconnect() {
        removeConnectionSilently();
        clearProtocolState();
        突发上送PointIndex.clear();
        log.info("IEC104 连接 已关闭");
    }

    /**
     * 创建并返回业务对象。
     */
    private ConnectionEventListener createConnectionEventListener() {
        return new ConnectionEventListener() {
            /**
             * 创建并返回业务对象。
             */
            @Override
            public void newASdu(Connection conn, ASdu asdu) {
                try {
                    handleResponse(conn, asdu);
                    lastActivityTime = System.currentTimeMillis();
                } catch (Exception e) {
                    handleError("ASDU handling failed", e);
                }
            }

            /**
             * 处理连接生命周期。
             */
            @Override
            public void connectionClosed(Connection conn, IOException e) {
                handleConnectionClosed(e);
            }

            /**
             * 执行当前业务逻辑。
             */
            @Override
            public void dataTransferStateChanged(Connection conn, boolean stopped) {
                dataTransferStopped = stopped;
                if (stopped) {
                    connectionStatus = "CONNECTED_STOPPED";
                    log.warn("IEC104 数据传输已停止：{}", conn.getRemoteInetAddress());
                } else {
                    connectionStatus = connected ? "CONNECTED" : connectionStatus;
                    log.info("IEC104 数据传输已启动：{}", conn.getRemoteInetAddress());
                }
            }
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        Iec104Address address = resolveReadAddress(point);
        int ioa = address.getIoAddress();
        int ca = address.getCommonAddress();
        Integer typeId = resolvePointTypeId(point, address);

        Object cached = getCachedValue(ca, typeId, ioa);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<Object> future = registerPendingRequest(ca, typeId, ioa);

        if (!maybeTriggerSingleInterrogation(point, address)) {
            connection.readCommand(ca, ioa);
        }

        try {
            return normalizeValue(future.get(timeout, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            throw new IOException("IEC104 read timeout, ioa=" + ioa);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        Map<String, Object> results = new HashMap<>();
        Map<String, PendingRead> pendingReads = new LinkedHashMap<>();
        Map<Iec104Key, PendingRead> deduplicatedRequests = new LinkedHashMap<>();

        for (DataPoint point : points) {
            Iec104Address address = resolveReadAddress(point);
            Integer typeId = resolvePointTypeId(point, address);
            Object cached = getCachedValue(address.getCommonAddress(), typeId, address.getIoAddress());
            if (cached != null) {
                results.put(point.getPointId(), cached);
                continue;
            }

            PendingRead pendingRead = new PendingRead(
                    point,
                    address,
                    registerPendingRequest(address.getCommonAddress(), typeId, address.getIoAddress()));
            pendingReads.put(point.getPointId(), pendingRead);

            Iec104Key key = resolvePointKey(point, address);
            deduplicatedRequests.putIfAbsent(key, pendingRead);
        }

        Set<InterrogationKey> triggeredQualifiers = new HashSet<>();
        for (Map.Entry<Iec104Key, PendingRead> entry : deduplicatedRequests.entrySet()) {
            Iec104Key key = entry.getKey();
            PendingRead pendingRead = entry.getValue();
            boolean triggered = maybeTriggerSingleInterrogation(
                    pendingRead.point(),
                    pendingRead.address(),
                    triggeredQualifiers);
            if (!triggered) {
                try {
                    connection.readCommand(key.commonAddress(), key.ioAddress());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        for (Map.Entry<String, PendingRead> entry : pendingReads.entrySet()) {
            try {
                Object value = entry.getValue().future().get(timeout, TimeUnit.MILLISECONDS);
                results.put(entry.getKey(), normalizeValue(value));
            } catch (Exception e) {
                results.put(entry.getKey(), null);
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
        status.put("protocol", "IEC 104");
        status.put("host", host);
        status.put("port", port);
        status.put("commonAddress", commonAddress);
        status.put("timeout", timeout);
        status.put("connected", isConnected());
        status.put("dataTransferStopped", dataTransferStopped);
        status.put("connectionStatus", connectionStatus);
        status.put("subscribedPoints", subscribedPointsSet.size());
        status.put("lastConnectTime", lastConnectTime);
        status.put("lastActivityTime", lastActivityTime);
        status.put("totalErrorCount", totalErrorCount.get());
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
                connection.readCommand(ca, requireIntParameter(safeParams, "address"));
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
        if (point != null) {
            ingestPushedValue(point, value);
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
        return new WriteTarget(
                address,
                type,
                getAdditionalInt(point, "writeQl", 0),
                getAdditionalBoolean(point, "writeSelect", false));
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
        Integer configured = resolveConfiguredTypeId(point.getAdditionalConfig("typeId"));
        if (configured != null) {
            return configured;
        }
        configured = resolveConfiguredTypeId(point.getAdditionalConfig("iecTypeId"));
        if (configured != null) {
            return configured;
        }
        Object registerType = point.getAdditionalConfig("registerType");
        if (registerType != null) {
            return resolveConfiguredTypeId(registerType);
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolveConfiguredTypeId(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Iec104Utils.resolveTypeIdToken(raw);
        } catch (IllegalArgumentException e) {
            log.debug("IEC104 typeId 标记无效：{}", raw);
            return null;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolvePointTypeId(DataPoint point, Iec104Address address) {
        if (address != null && address.getTypeId() != null) {
            return Iec104Type.canonicalTypeId(address.getTypeId());
        }
        return extractReadConfiguredTypeId(point);
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
            timedSender.send(address.getCommonAddress(), address.getIoAddress(), payload, currentTime());
        } else {
            plainSender.send(address.getCommonAddress(), address.getIoAddress(), payload);
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
            timedSender.send(address.getCommonAddress(), address.getIoAddress(), payload, qualifier, currentTime());
        } else {
            plainSender.send(address.getCommonAddress(), address.getIoAddress(), payload, qualifier);
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
