package com.wangbin.collector.core.collector.protocol.modbus.base;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.config.ThreadPoolFallbacks;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.enums.DataType;
import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.modbus.domain.ModbusAddress;
import com.wangbin.collector.core.collector.protocol.modbus.domain.ModbusAddressMode;
import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;
import com.wangbin.collector.core.collector.protocol.modbus.plan.ModbusReadPlan;
import com.wangbin.collector.core.collector.protocol.modbus.plan.ModbusReadPlanBuilder;
import com.wangbin.collector.core.collector.protocol.modbus.plan.PointOffset;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Modbus采集器抽象基类
 */
@Slf4j
public abstract class AbstractModbusCollector extends ConnectionBackedCollector {

    private static final int MAX_WRITE_REGISTERS = 123;
    private static final int MAX_WRITE_COILS = 1968;
    private static final ExecutorService DEFAULT_MODBUS_READ_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            runnable -> {
                Thread thread = new Thread(runnable, "modbus-read-shared");
                thread.setDaemon(true);
                return thread;
            });

    protected int timeout = 3000;
    protected volatile List<ModbusReadPlan> readPlans = List.of();

    // 订阅缓存
    protected final Map<RegisterType, Map<Integer, DataPoint>> registerCache = new ConcurrentHashMap<>();
    private Executor modbusReadExecutor;

    /**
     * 注入 Modbus 批量读取线程池。
     */
    @Autowired(required = false)
    public void setModbusReadExecutor(@Qualifier("asyncCollectorExecutor") Executor modbusReadExecutor) {
        this.modbusReadExecutor = modbusReadExecutor;
    }

    // =============== 公共方法 ===============

    /**
     * 构建执行计划
     * @param 点位
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        this.readPlans = ModbusReadPlanBuilder.build(deviceId,
                points,
                this::resolveUnitId,
                this::parseModbusAddressForPoint
        );
        log.info("Modbus 读取计划构建完成，计划数: {}", readPlans.size());
    }

    protected ModbusAddress parseModbusAddressForPoint(DataPoint point, String address) {
        DeviceConnection connection = getCurrentConnectionConfig();
        Object configuredMode = point.getAdditionalConfig("addressMode");
        if (configuredMode == null && connection != null) configuredMode = connection.getProperty("addressMode");
        ModbusAddressMode mode = ModbusAddressMode.from(configuredMode);
        Object configuredType = point.getAdditionalConfig("registerType");
        RegisterType registerType = resolveRegisterType(configuredType);
        return parseModbusAddress(address, mode, registerType);
    }

    private RegisterType parseRegisterTypeToken(String token) {
        String normalized = token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "0", "COIL" -> RegisterType.COIL;
            case "1", "DISCRETE_INPUT" -> RegisterType.DISCRETE_INPUT;
            case "3", "INPUT_REGISTER" -> RegisterType.INPUT_REGISTER;
            case "4", "HOLDING_REGISTER" -> RegisterType.HOLDING_REGISTER;
            default -> throw new IllegalArgumentException("Unsupported Modbus register type: " + token);
        };
    }

    private RegisterType resolveRegisterType(Object value) {
        return value == null ? null : parseRegisterTypeToken(value.toString());
    }

    private ModbusAddress parseModbusAddress(String address, ModbusAddressMode mode, RegisterType configuredType) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Modbus address cannot be empty");
        }
        String text = address.trim();
        boolean typed = text.contains("x") || text.contains("X") || text.contains(":");
        if (typed) {
            String[] parts = text.split("[xX:]");
            if (parts.length != 2) throw new IllegalArgumentException("Invalid Modbus address: " + address);
            RegisterType type = parseRegisterTypeToken(parts[0]);
            int value = parseAddressNumber(parts[1], address);
            // 0x00001 的前导零也属于五位引用地址，不能按整数 1 当作原始偏移。
            boolean reference = parts[1].length() == 5
                    && parts[1].charAt(0) == parts[0].charAt(0)
                    && Character.isDigit(parts[1].charAt(0));
            int offset = reference || value >= 10000 ? value % 10000 - 1 : value;
            return new ModbusAddress(type, validateOffset(offset, address));
        }
        int value = parseAddressNumber(text, address);
        if (mode == ModbusAddressMode.RAW_OFFSET || (mode == ModbusAddressMode.AUTO_COMPAT && value < 10000)) {
            RegisterType type = configuredType != null ? configuredType : RegisterType.HOLDING_REGISTER;
            return new ModbusAddress(type, validateOffset(value, address));
        }
        return parseReferenceAddress(value, address);
    }

    private int parseAddressNumber(String value, String address) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Modbus address: " + address);
        }
    }

    private int validateOffset(int offset, String address) {
        if (offset < 0 || offset > 65535) {
            throw new IllegalArgumentException("Modbus address out of range: " + address);
        }
        return offset;
    }

    private ModbusAddress parseReferenceAddress(int reference, String address) {
        RegisterType type = parseRegisterTypeToken(String.valueOf(reference / 10000));
        return new ModbusAddress(type, validateOffset(reference % 10000 - 1, address));
    }

    public ModbusAddress parseModbusAddress(String addressStr) {
        return parseModbusAddress(addressStr, ModbusAddressMode.AUTO_COMPAT, null);
    }

    /**
     * 获取UnitId
     * @param 点位
     * @return 处理结果
     */
    protected int resolveUnitId(DataPoint point) {
        if (point.getUnitId() != null) {
            return point.getUnitId();
        }
        DeviceConnection connection = getCurrentConnectionConfig();
        Integer slaveId = connection != null ? (Integer) connection.getProperty("slaveId") : null;
        return slaveId != null ? slaveId : 1;
    }

    /**
     * 获取设备状态信息
     */
    protected Map<String, Object> getBaseDeviceStatus(String protocolType) {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.PROTOCOL, protocolType);
        DeviceConnection connection = getCurrentConnectionConfig();
        assert connection != null;
        Object configuredSlaveId = connection.getProperty("slaveId");
        status.put(CommonMapKeys.HOST, connection.getHost());
        status.put(CommonMapKeys.PORT, connection.getPort());
        status.put("slaveId", configuredSlaveId != null ? String.valueOf(configuredSlaveId) : "1");
        status.put(CommonMapKeys.TIMEOUT, timeout);
        status.put("clientConnected", isConnected());

        // 统计订阅信息
        int totalSubscribed = 0;
        Map<String, Integer> subscribedByType = new HashMap<>();
        for (Map.Entry<RegisterType, Map<Integer, DataPoint>> entry : registerCache.entrySet()) {
            int count = entry.getValue().size();
            subscribedByType.put(entry.getKey().name(), count);
            totalSubscribed += count;
        }
        status.put(CommonMapKeys.SUBSCRIBED_POINTS, totalSubscribed);
        status.put("subscribedByType", subscribedByType);

        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        if (readPlans.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> results = new ConcurrentHashMap<>();
        Map<Integer, List<ModbusReadPlan>> groupedPlans = readPlans.stream()
                .collect(Collectors.groupingBy(ModbusReadPlan::getUnitId, LinkedHashMap::new, Collectors.toList()));

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (List<ModbusReadPlan> group : groupedPlans.values()) {
            futures.add(CompletableFuture.runAsync(
                    () -> group.forEach(plan -> processReadPlan(plan, results)),
                    resolveModbusReadExecutor()));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return results;
    }

    /**
     * 批量写入点位
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) throws Exception {
        Map<String, Boolean> results = new HashMap<>();
        Map<BatchKey, List<WriteEntry>> grouped = new LinkedHashMap<>();

        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            Object value = entry.getValue();

            try {
                ModbusAddress address = parseModbusAddressForPoint(point, point.getAddress());
                RegisterType type = address.getRegisterType();

                if (type != RegisterType.COIL && type != RegisterType.HOLDING_REGISTER) {
                    boolean success = doWritePoint(point, value);
                    results.put(point.getPointId(), success);
                    continue;
                }

                int unitId = resolveBatchUnitId(point);
                int registerCount = DataType.fromString(point.getDataType()).getRegisterCount();
                BatchKey key = new BatchKey(unitId, type);
                grouped.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new WriteEntry(point, value, address.getAddress(), registerCount));
            } catch (Exception e) {
                log.error("解析写入点位失败: {}", point.getPointName(), e);
                results.put(point.getPointId(), false);
            }
        }

        for (Map.Entry<BatchKey, List<WriteEntry>> batch : grouped.entrySet()) {
            List<WriteEntry> entries = batch.getValue();
            entries.sort(Comparator.comparingInt(WriteEntry::address));
            processWriteBatch(batch.getKey(), entries, results);
        }

        return results;
    }

    /**
     * 订阅点位
     */
    @Override
    protected void doSubscribe(List<DataPoint> points) {
        log.info("Modbus订阅: 数量={}", points.size());

        for (DataPoint point : points) {
            try {
                ModbusAddress address = parseModbusAddressForPoint(point, point.getAddress());
                RegisterType type = address.getRegisterType();

                registerCache.computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                        .put(address.getAddress(), point);
            } catch (Exception e) {
                log.error("订阅点位失败: {}", point.getAddress(), e);
            }
        }
    }

    /**
     * 取消订阅
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        log.info("取消Modbus订阅: 数量={}", points.size());

        if (points.isEmpty()) {
            registerCache.clear();
        } else {
            for (DataPoint point : points) {
                try {
                    ModbusAddress address = parseModbusAddressForPoint(point, point.getAddress());
                    RegisterType type = address.getRegisterType();

                    Map<Integer, DataPoint> typeCache = registerCache.get(type);
                    if (typeCache != null) {
                        typeCache.remove(address.getAddress());

                        if (typeCache.isEmpty()) {
                            registerCache.remove(type);
                        }
                    }
                } catch (Exception e) {
                    log.error("取消订阅点位失败: {}", point.getAddress(), e);
                }
            }
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void processReadPlan(ModbusReadPlan plan, Map<String, Object> results) {
        try {
            byte[] raw = getModbusTransport().read(
                    plan.getUnitId(),
                    plan.getRegisterType(),
                    plan.getStartAddress(),
                    plan.getQuantity());

            if (plan.getRegisterType() == RegisterType.COIL ||
                    plan.getRegisterType() == RegisterType.DISCRETE_INPUT) {
                List<Boolean> boolValues = ModbusUtils.getCoilValues(raw, plan.getQuantity(), getModbusParity());
                for (PointOffset pointOffset : plan.getPointOffsets()) {
                    Boolean value = null;
                    int offset = pointOffset.getOffset();
                    if (offset >= 0 && offset < boolValues.size()) {
                        value = boolValues.get(offset);
                    }
                    results.put(pointOffset.getPointId(), value);
                }
            } else {
                for (PointOffset pointOffset : plan.getPointOffsets()) {
                    Object value = ModbusUtils.parseValue(
                            raw,
                            pointOffset.getOffset(),
                            DataType.valueOf(pointOffset.getDataType()),
                            getModbusByteOrder());
                    results.put(pointOffset.getPointId(), value);
                }
            }

        } catch (Exception e) {
            log.error("ReadPlan 执行失败: unitId={}, type={}, addr={}",
                    plan.getUnitId(),
                    plan.getRegisterType(),
                    plan.getStartAddress(),
                    e);
            // ConcurrentHashMap 不允许写入 null。计划读取失败时保持点位缺失，
            // 由上层按空结果判定本轮采集失败，避免真实异常被二次 NPE 覆盖。
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void processWriteBatch(BatchKey key,List<WriteEntry> entries,Map<String, Boolean> results) {
        int limit = key.registerType == RegisterType.COIL ? MAX_WRITE_COILS : MAX_WRITE_REGISTERS;
        List<WriteEntry> chunk = new ArrayList<>();
        int chunkStart = -1;
        int chunkQuantity = 0;

        for (WriteEntry entry : entries) {
            if (chunk.isEmpty()) {
                chunk.add(entry);
                chunkStart = entry.address();
                chunkQuantity = entry.registerCount();
                continue;
            }

            int expectedAddress = chunkStart + chunkQuantity;
            boolean contiguous = entry.address() == expectedAddress;
            boolean exceeds = chunkQuantity + entry.registerCount() > limit;

            if (!contiguous || exceeds) {
                flushWriteChunk(key, chunkStart, chunk, results);
                chunk = new ArrayList<>();
                chunk.add(entry);
                chunkStart = entry.address();
                chunkQuantity = entry.registerCount();
            } else {
                chunk.add(entry);
                chunkQuantity += entry.registerCount();
            }
        }

        if (!chunk.isEmpty()) {
            flushWriteChunk(key, chunkStart, chunk, results);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void flushWriteChunk(BatchKey key,int startAddress,List<WriteEntry> chunk,Map<String, Boolean> results) {
        if (chunk.isEmpty()) {
            return;
        }

        if (chunk.size() == 1) {
            writeEntriesIndividually(chunk, results);
            return;
        }

        boolean success = key.registerType == RegisterType.COIL
                ? writeCoilChunk(key.unitId, startAddress, chunk)
                : writeHoldingChunk(key.unitId, startAddress, chunk);

        if (success) {
            chunk.forEach(entry -> results.put(entry.point().getPointId(), true));
        } else {
            writeEntriesIndividually(chunk, results);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private void writeEntriesIndividually(List<WriteEntry> chunk,
                                          Map<String, Boolean> results) {
        for (WriteEntry entry : chunk) {
            try {
                boolean single = doWritePoint(entry.point(), entry.value());
                results.put(entry.point().getPointId(), single);
            } catch (Exception e) {
                log.error("单点写入失败: {}", entry.point().getPointName(), e);
                results.put(entry.point().getPointId(), false);
            }
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeCoilChunk(int unitId, int startAddress, List<WriteEntry> chunk) {
        try {
            List<Boolean> values = new ArrayList<>();
            for (WriteEntry entry : chunk) {
                values.add(asBoolean(entry.value()));
            }
            byte[] coilBytes = ModbusUtils.buildCoilBytes(values, getModbusParity());
            return getModbusTransport().writeMultipleCoils(unitId, startAddress, values.size(), coilBytes);
        } catch (Exception e) {
            log.error("批量写线圈失败: unitId={}, startAddress={}", unitId, startAddress, e);
            return false;
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private boolean writeHoldingChunk(int unitId, int startAddress, List<WriteEntry> chunk) {
        try {
            short[] registers = buildRegisterBuffer(chunk);
            return getModbusTransport().writeMultipleRegisters(unitId, startAddress, registers);
        } catch (Exception e) {
            log.error("批量写保持寄存器失败: unitId={}, startAddress={}", unitId, startAddress, e);
            return false;
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private short[] buildRegisterBuffer(List<WriteEntry> chunk) {
        int total = chunk.stream().mapToInt(WriteEntry::registerCount).sum();
        short[] buffer = new short[total];
        int offset = 0;
        for (WriteEntry entry : chunk) {
            short[] values = ModbusUtils.valueToRegisters(
                    entry.value(),
                    entry.point().getDataType(),
                    getModbusByteOrder());
            System.arraycopy(values, 0, buffer, offset, values.length);
            offset += values.length;
        }
        return buffer;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        throw new IllegalArgumentException("无法转换为布尔值: " + value);
    }

    /**
     * 解析或转换业务数据。
     */
    protected int resolveBatchUnitId(DataPoint point) {
        return resolveUnitId(point);
    }

    /**
     * 解析或转换业务数据。
     */
    protected Executor resolveModbusReadExecutor() {
        return ThreadPoolFallbacks.preferExecutor(
                modbusReadExecutor,
                DEFAULT_MODBUS_READ_EXECUTOR,
                "AbstractModbusCollector",
                "modbus-read-shared");
    }

    protected abstract ModbusTransport getModbusTransport();

    protected abstract ByteOrder getModbusByteOrder();

    protected abstract Parity getModbusParity();

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record BatchKey(int unitId, RegisterType registerType) {
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record WriteEntry(DataPoint point,
                              Object value,
                              int address,
                              int registerCount) {
    }

    /**
     * 检查连接状态
     */
    public abstract boolean isConnected();

}
