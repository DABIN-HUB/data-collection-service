package com.wangbin.collector.core.collector.protocol.iec.base;

import com.wangbin.collector.common.config.ThreadPoolFallbacks;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.iec.util.Iec104Utils;
import com.wangbin.collector.core.config.CollectorProperties;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openmuc.j60870.*;
import org.openmuc.j60870.ie.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IEC 104 协议 采集器 base class.
 */
@Slf4j
public abstract class AbstractIce104Collector extends ConnectionBackedCollector {

    @Getter
    protected volatile Connection connection;

    protected String host;
    protected int port = 2404;
    protected int commonAddress = 1;
    protected int timeout = 5000;
    protected int connectionTimeout = 5000;
    protected int ioaFieldLength = 3;
    protected Iec104IoaEncodingMode ioaEncodingMode = Iec104IoaEncodingMode.STANDARD;
    protected boolean timeTag = true;
    protected CollectorProperties.Iec104Config iec104Config;
    /** 当前设备的读取策略，AUTO 保持历史行为。 */
    protected String readMode = "AUTO";
    protected boolean generalInterrogationOnConnect = true;
    protected boolean singleInterrogationOnReadMiss = true;
    protected long generalInterrogationInterval;
    protected long cacheTtl;
    protected int generalInterrogationQualifier = 20;

    protected volatile boolean dataTransferStopped = true;

    protected final Map<Iec104Key, CopyOnWriteArrayList<CompletableFuture<Object>>> pendingRequests = new ConcurrentHashMap<>();
    protected final Map<Iec104Key, CacheEntry> valueCache = new ConcurrentHashMap<>();
    protected final Map<InterrogationKey, CompletableFuture<Void>> pendingInterrogations = new ConcurrentHashMap<>();
    protected ScheduledExecutorService interrogationScheduler;
    private ScheduledFuture<?> generalInterrogationTask;

    private static final AtomicInteger TIMEOUT_THREAD_COUNTER = new AtomicInteger(0);
    private static final ScheduledExecutorService DEFAULT_PROTOCOL_SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread thread = new Thread(r, "iec104-protocol-" + TIMEOUT_THREAD_COUNTER.incrementAndGet());
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((t, e) ->
                        log.warn("IEC104 协议 调度器 thread {} 失败", t.getName(), e));
                return thread;
            });
    private ScheduledExecutorService protocolScheduler;

    /**
     * 注入协议调度线程池。
     */
    @Autowired(required = false)
    public void setProtocolScheduler(@Qualifier("timeSliceScheduler") ScheduledExecutorService protocolScheduler) {
        this.protocolScheduler = protocolScheduler;
    }
    /**
     * 处理组件生命周期。
     */
    @PreDestroy
    protected void shutdownSchedulers() {
        cancelGeneralInterrogationTask();
    }

    /**
     * 处理组件生命周期。
     */
    protected void initIec104Config(DeviceInfo deviceInfo) {
        this.iec104Config = collectorProperties != null
                ? collectorProperties.getIec104()
                : new CollectorProperties.Iec104Config();

        DeviceConnection connectionConfig = requireConnectionConfig();
        this.readMode = resolveReadMode(connectionConfig.getString("generalInterrogationMode", "AUTO"));
        this.generalInterrogationOnConnect = Boolean.TRUE.equals(connectionConfig.getBool(
                "generalInterrogationOnConnect", iec104Config.isGeneralInterrogationOnConnect()));
        this.singleInterrogationOnReadMiss = Boolean.TRUE.equals(connectionConfig.getBool(
                "singleInterrogationOnReadMiss", iec104Config.isSingleInterrogationOnReadMiss()));
        this.cacheTtl = resolveLongConfig(connectionConfig, "cacheTtl", iec104Config.getCacheTtl());
        this.generalInterrogationQualifier = resolveIntConfig(connectionConfig,
                "generalInterrogationQualifier", 20);
        long configuredInterrogationInterval = resolveLongConfig(connectionConfig,
                "generalInterrogationInterval", iec104Config.getGeneralInterrogationInterval());
        this.generalInterrogationInterval = resolveInterrogationInterval(
                configuredInterrogationInterval, cacheTtl);
        this.host = connectionConfig.getHost() != null && !connectionConfig.getHost().isBlank()
                ? connectionConfig.getHost() : deviceInfo.getIpAddress();
        this.port = connectionConfig.getPort() != null ? connectionConfig.getPort()
                : deviceInfo.getPort() != null ? deviceInfo.getPort() : 2404;
        this.commonAddress = resolveCommonAddress(connectionConfig);
        this.timeout = resolveTimeout(connectionConfig);
        this.connectionTimeout = connectionConfig.getConnectTimeout() != null && connectionConfig.getConnectTimeout() > 0
                ? connectionConfig.getConnectTimeout()
                : connectionConfig.getTimeout() != null && connectionConfig.getTimeout() > 0
                ? connectionConfig.getTimeout() : 5000;
        this.ioaFieldLength = connectionConfig.getInt("ioaFieldLength", 3);
        this.ioaEncodingMode = Iec104IoaEncodingMode.from(connectionConfig.getProperty("ioaEncodingMode"));
        this.timeTag = true;
        if (interrogationScheduler == null) {
            interrogationScheduler = resolveProtocolScheduler();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void doDisconnect() {
        clearProtocolState();
    }

    /**
     * 清理或删除业务数据。
     */
    protected void clearProtocolState() {
        dataTransferStopped = true;
        valueCache.clear();
        pendingRequests.values()
                .forEach(list -> list.forEach(f -> f.completeExceptionally(new IOException("connection closed"))));
        pendingRequests.clear();
        pendingInterrogations.values()
                .forEach(f -> f.completeExceptionally(new IOException("connection closed")));
        pendingInterrogations.clear();
        cancelGeneralInterrogationTask();
        interrogationScheduler = null;
    }

    /**
     * 处理当前业务流程。
     */
    protected Map<String, Object> handleResponse(Connection conn, ASdu asdu) {
        Map<String, Object> result = new HashMap<>();

        try {
            ASduType type = asdu.getTypeIdentification();
            CauseOfTransmission cot = asdu.getCauseOfTransmission();
            int commonAddr = asdu.getCommonAddress();

            log.debug("IEC104 ASDU：类型={}，传送原因={}，公共地址={}，连续序列={}，元素数={}，否定确认={}",
                    type, cot, commonAddr, asdu.isSequenceOfElements(),
                    asdu.getSequenceLength(), asdu.isNegativeConfirm());

            if (type == ASduType.C_IC_NA_1) {
                handleInterrogationAsdu(asdu);
                return result;
            }

            if (asdu.isNegativeConfirm() || isErrorCause(cot)) {
                failPendingAsdu(asdu, new IOException("IEC104 error: " + cot));
                return result;
            }

            if (asdu.isTestFrame() || type == null || !Iec104Utils.isReadType(type.getId())) {
                return result;
            }

            InformationObject[] ios = asdu.getInformationObjects();
            if (ios == null || ios.length == 0) {
                handleEmptyAsdu(asdu);
                return result;
            }

            boolean isResponse = isResponseCause(cot);
            Integer typeId = Iec104Utils.resolveTypeId(type);

            for (InformationObject io : ios) {
                int rawIoa = io.getInformationObjectAddress();
                InformationElement[][] elements = io.getInformationElements();
                int count = asdu.isSequenceOfElements() && elements != null ? elements.length : 1;
                for (int i = 0; i < count; i++) {
                    // j60870 的 sequence 只携带起始 IOA，每个元素组对应后续连续地址。
                    int wireIoa = sequenceWireIoa(rawIoa, i);
                    int ioa = fromWireIoa(wireIoa);
                    Object value = elements != null && i < elements.length
                            ? parseValue(type, elements[i]) : null;
                    Object normalized = normalizeValue(value);
                    boolean pendingRead = hasPendingRequest(commonAddr, typeId, ioa);
                    CacheEntry sample = createSample(normalized, type,
                            elements != null && i < elements.length ? elements[i] : new InformationElement[0]);
                    cacheValue(commonAddr, typeId, ioa, sample);
                    completeRequest(commonAddr, typeId, ioa, sample);
                    if (!isResponse || (type != null && type.name().startsWith("M_")
                            && normalized != null && !pendingRead)) {
                        handleSpontaneous(commonAddr, typeId, ioa, type, normalized, asdu);
                    }
                    result.put(String.valueOf(ioa), normalized);
                }
            }

        } catch (Exception e) {
            log.error("IEC104 处理响应失败", e);
            failAllPending(e);
        }

        return result;
    }

    protected int toWireIoa(int logicalIoa) {
        long wireIoa = ioaEncodingMode == Iec104IoaEncodingMode.SHIFT8_COMPAT
                ? ((long) logicalIoa) << 8 : logicalIoa;
        validateIoa(wireIoa);
        return (int) wireIoa;
    }

    protected int fromWireIoa(int wireIoa) {
        validateIoa(wireIoa);
        if (ioaEncodingMode == Iec104IoaEncodingMode.SHIFT8_COMPAT && (wireIoa & 0xFF) != 0) {
            throw new IllegalArgumentException("IEC104 SHIFT8_COMPAT wire IOA must be aligned to 256: " + wireIoa);
        }
        return ioaEncodingMode == Iec104IoaEncodingMode.SHIFT8_COMPAT
                ? wireIoa >>> 8 : wireIoa;
    }

    private void validateIoa(long ioa) {
        if (ioaFieldLength < 1 || ioaFieldLength > 3 || ioa < 0
                || ioa > (1L << (ioaFieldLength * 8)) - 1) {
            throw new IllegalArgumentException("IEC104 IOA is not representable in "
                    + ioaFieldLength + " bytes: " + ioa);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private void failAllPending(Exception e) {
        pendingRequests.forEach((k, list) -> list.forEach(f -> f.completeExceptionally(e)));
        pendingRequests.clear();
    }

    /** 否定单点读只影响原公共地址和IOA，不抹掉其他点位的真实结果。 */
    private void failPendingAsdu(ASdu asdu, Exception failure) {
        Set<Integer> addresses = new HashSet<>();
        InformationObject[] objects = asdu.getInformationObjects();
        if (objects != null) {
            for (InformationObject object : objects) {
                addresses.add(fromWireIoa(object.getInformationObjectAddress()));
            }
        }
        pendingRequests.forEach((key, futures) -> {
            if (key.commonAddress() == asdu.getCommonAddress() && addresses.contains(key.ioAddress())) {
                futures.forEach(future -> future.completeExceptionally(failure));
            }
        });
    }

    /** 保留线上的质量位与原时间；无有效CP56时只记录真实本地收包时间。 */
    private CacheEntry createSample(Object value, ASduType type, InformationElement[] elements) {
        long observedAt = System.currentTimeMillis();
        int quality = 0;
        Long sourceTime = null;
        for (InformationElement element : elements) {
            if (element instanceof IeQuality q) {
                quality |= qualityBits(q.isBlocked(), q.isSubstituted(), q.isNotTopical(), q.isInvalid())
                        | (q.isOverflow() ? 1 : 0);
            } else if (element instanceof IeSinglePointWithQuality q) {
                quality |= qualityBits(q.isBlocked(), q.isSubstituted(), q.isNotTopical(), q.isInvalid());
            } else if (element instanceof IeDoublePointWithQuality q) {
                quality |= qualityBits(q.isBlocked(), q.isSubstituted(), q.isNotTopical(), q.isInvalid());
            } else if (element instanceof IeTime56 time && !time.isInvalid()) {
                long timestamp = time.getTimestamp();
                if (timestamp > 0 && timestamp <= observedAt) sourceTime = timestamp;
            }
        }
        return new CacheEntry(value, sourceTime != null ? sourceTime : observedAt,
                observedAt, quality, type.getId(), sourceTime);
    }

    private int qualityBits(boolean blocked, boolean substituted, boolean notTopical, boolean invalid) {
        return (blocked ? 16 : 0) | (substituted ? 32 : 0) | (notTopical ? 64 : 0) | (invalid ? 128 : 0);
    }

    /**
     * 执行当前业务逻辑。
     */
    private void cacheValue(int commonAddress, Integer typeId, int ioa, CacheEntry entry) {
        if (entry.value() == null) {
            return;
        }
        if (typeId != null) {
            valueCache.put(new Iec104Key(commonAddress, typeId, ioa), entry);
        }
        valueCache.put(new Iec104Key(commonAddress, null, ioa), entry);
    }

    protected Object getCachedValue(int commonAddress, Integer typeId, int ioa) {
        CacheEntry entry = getCachedSample(commonAddress, typeId, ioa);
        return entry != null ? entry.value() : null;
    }

    protected CacheEntry getCachedSample(int commonAddress, Integer typeId, int ioa) {
        CacheEntry entry = getCacheEntry(commonAddress, typeId, ioa);
        return entry != null && resolveCacheValue(commonAddress, typeId, ioa, entry) != null ? entry : null;
    }

    /**
     * 校验业务条件和参数边界。
     */
    @Override
    protected void checkConnection() {
        super.checkConnection();
        if (connection == null) {
            throw new IllegalStateException("IEC104 connection client is unavailable");
        }
        if (dataTransferStopped) {
            String deviceId = deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN";
            throw new IllegalStateException("IEC104 data transfer is stopped: " + deviceId);
        }
    }

    private CacheEntry getCacheEntry(int commonAddress, Integer typeId, int ioa) {
        if (typeId != null) {
            return valueCache.get(new Iec104Key(commonAddress, typeId, ioa));
        }
        return valueCache.get(new Iec104Key(commonAddress, null, ioa));
    }

    /**
     * 解析或转换业务数据。
     */
    private Object resolveCacheValue(int commonAddress, Integer typeId, int ioa, CacheEntry entry) {
        long ttl = cacheTtl;
        if (ttl <= 0) {
            return entry.value();
        }
        if (System.currentTimeMillis() - entry.observedAt() <= ttl) {
            return entry.value();
        }
        if (typeId != null) {
            valueCache.remove(new Iec104Key(commonAddress, typeId, ioa), entry);
        }
        valueCache.remove(new Iec104Key(commonAddress, null, ioa), entry);
        return null;
    }

    /**
     * 处理当前业务流程。
     */
    private void handleEmptyAsdu(ASdu asdu) {
        if (asdu.getTypeIdentification() == ASduType.C_IC_NA_1 &&
                asdu.getCauseOfTransmission() == CauseOfTransmission.ACTIVATION_TERMINATION) {

            log.info("IEC104 总召唤已完成");
        }
    }

    /**
     * 处理当前业务流程。
     */
    protected void handleSpontaneous(int commonAddress, Integer typeId, int ioa, ASduType type, Object value, ASdu asdu) {
        log.debug("IEC104 突发上送:ioa={}, type={}, 值={}",
                ioa, type, value);
    }

    /**
     * 解析或转换业务数据。
     */
    private Object parseValue(ASduType type, InformationElement[] ies) {
        if (ies == null || ies.length == 0) {
            return null;
        }

        InformationElement ie = ies[0];

        return switch (type) {
            case M_SP_NA_1, M_SP_TA_1, M_SP_TB_1 -> ((IeSinglePointWithQuality) ie).isOn();
            case M_DP_NA_1, M_DP_TA_1, M_DP_TB_1 -> ((IeDoublePointWithQuality) ie)
                    .getDoublePointInformation();
            case M_ST_NA_1, M_ST_TA_1, M_ST_TB_1 -> ((IeValueWithTransientState) ie).getValue();
            case M_BO_NA_1, M_BO_TA_1, M_BO_TB_1 -> ((IeBinaryStateInformation) ie).getValue();
            case M_ME_NA_1, M_ME_TA_1, M_ME_TD_1, M_ME_ND_1 -> ((IeNormalizedValue) ie)
                    .getUnnormalizedValue();
            case M_ME_NB_1, M_ME_TB_1, M_ME_TE_1 -> ((IeScaledValue) ie)
                    .getUnnormalizedValue();
            case M_ME_NC_1, M_ME_TC_1, M_ME_TF_1 -> ((IeShortFloat) ie).getValue();
            case M_IT_NA_1, M_IT_TA_1, M_IT_TB_1 -> ((IeBinaryCounterReading) ie).getCounterReading();
            default -> {
                log.debug("不支持的 IEC104 ASDU 类型：{}", type);
                yield ie.toString();
            }
        };
    }

    private boolean isErrorCause(CauseOfTransmission cot) {
        return cot == CauseOfTransmission.UNKNOWN_COMMON_ADDRESS_OF_ASDU ||
                cot == CauseOfTransmission.UNKNOWN_INFORMATION_OBJECT_ADDRESS ||
                cot == CauseOfTransmission.UNKNOWN_TYPE_ID ||
                cot == CauseOfTransmission.UNKNOWN_CAUSE_OF_TRANSMISSION;
    }

    private boolean isResponseCause(CauseOfTransmission cot) {
        if (cot == null) {
            return false;
        }
        if (cot == CauseOfTransmission.REQUEST || cot == CauseOfTransmission.INTERROGATED_BY_STATION) {
            return true;
        }
        String name = cot.name();
        return name.startsWith("INTERROGATED_BY_GROUP_") || name.startsWith("REQUESTED_BY_");
    }

    /**
     * 执行当前业务逻辑。
     */
    private void completeRequest(int commonAddress, Integer typeId, int ioAddress, Object value) {
        if (typeId != null) {
            completePendingKey(new Iec104Key(commonAddress, typeId, ioAddress), value);
        }
        completePendingKey(new Iec104Key(commonAddress, null, ioAddress), value);
    }

    private boolean hasPendingRequest(int commonAddress, Integer typeId, int ioAddress) {
        return (typeId != null && pendingRequests.containsKey(new Iec104Key(commonAddress, typeId, ioAddress)))
                || pendingRequests.containsKey(new Iec104Key(commonAddress, null, ioAddress));
    }

    /**
     * 按 IEC104 的地址编码模式计算 sequence 中每个元素的 wire IOA。
     * SHIFT8_COMPAT 的每个逻辑地址占用一个 256 对齐的 wire 地址，不能在解码后再累加逻辑地址。
     */
    private int sequenceWireIoa(int rawIoa, int elementIndex) {
        long increment = ioaEncodingMode == Iec104IoaEncodingMode.SHIFT8_COMPAT
                ? (long) elementIndex << 8 : elementIndex;
        long wireIoa = Math.addExact((long) rawIoa, increment);
        validateIoa(wireIoa);
        return (int) wireIoa;
    }

    /**
     * 执行当前业务逻辑。
     */
    private void completePendingKey(Iec104Key key, Object value) {
        CopyOnWriteArrayList<CompletableFuture<Object>> futures = pendingRequests.remove(key);
        if (futures != null) {
            futures.forEach(f -> f.complete(value));
        }
    }

    /**
     * 维护注册或订阅关系。
     */
    protected CompletableFuture<Object> registerPendingRequest(int commonAddress, Integer typeId, int ioAddress) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        Iec104Key key = new Iec104Key(commonAddress, typeId, ioAddress);
        pendingRequests.compute(key, (k, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            list.add(future);
            return list;
        });

        ScheduledFuture<?> deadline = resolveProtocolScheduler().schedule(() ->
                future.completeExceptionally(new TimeoutException("IEC104 wait timeout for ca/type/ioa="
                        + commonAddress + "/" + (typeId != null ? typeId : "*") + "/" + ioAddress)),
                requestTimeoutMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((value, failure) -> {
            removePendingFuture(key, future);
            deadline.cancel(false);
        });
        return future;
    }

    /**
     * 清理或删除业务数据。
     */
    private void removePendingFuture(Iec104Key key, CompletableFuture<Object> future) {
        pendingRequests.computeIfPresent(key, (k, list) -> {
            list.remove(future);
            return list.isEmpty() ? null : list;
        });
    }

    /**
     * 解析或转换业务数据。
     */
    protected Object normalizeValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value instanceof IeDoublePointWithQuality.DoublePointInformation dpi) {
            return dpi.ordinal();
        }
        return value;
    }

    /**
     * 处理当前业务流程。
     */
    private void handleInterrogationAsdu(ASdu asdu) {
        int qualifier = extractQualifier(asdu);
        int commonAddr = asdu.getCommonAddress();
        CauseOfTransmission cot = asdu.getCauseOfTransmission();
        InterrogationKey key = new InterrogationKey(commonAddr, qualifier);
        CompletableFuture<Void> future = pendingInterrogations.get(key);

        if (asdu.isNegativeConfirm() || isErrorCause(cot)) {
            if (future != null && pendingInterrogations.remove(key, future)) {
                future.completeExceptionally(new IOException("IEC104 interrogation rejected: " + cot));
            }
            return;
        }

        switch (cot) {
            case ACTIVATION:
            case ACTIVATION_CON:
                log.info("IEC104 总召唤限定词 {} 激活", qualifier);
                break;
            case ACTIVATION_TERMINATION:
                if (future != null) {
                    if (pendingInterrogations.remove(key, future)) {
                        future.complete(null);
                    }
                }
                log.info("IEC104 总召唤已完成");
                break;
            default:
                log.debug("IEC104 总召唤：传送原因={}，限定词={}，公共地址={}", cot, qualifier, commonAddr);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int extractQualifier(ASdu asdu) {
        InformationObject[] ios = asdu.getInformationObjects();
        if (ios == null || ios.length == 0) {
            return 20;
        }
        InformationElement[][] elements = ios[0].getInformationElements();
        if (elements == null || elements.length == 0 || elements[0].length == 0) {
            return 20;
        }
        InformationElement element = elements[0][0];
        if (element instanceof IeQualifierOfInterrogation qoi) {
            return qoi.getValue();
        }
        return 20;
    }

    /**
     * 执行当前业务逻辑。
     */
    protected void onConnectionReady() {
        maybeTriggerGeneralInterrogation("connect");
        startGeneralInterrogationLoop();
    }

    /**
     * 执行当前业务逻辑。
     */
    protected void maybeTriggerGeneralInterrogation(String reason) {
        if (generalInterrogationOnConnect) {
            triggerInterrogation(commonAddress, generalInterrogationQualifier, reason);
        }
    }

    protected boolean isGeneralInterrogationOnly() {
        return "GENERAL_INTERROGATION".equals(readMode);
    }

    protected boolean isSingleInterrogationOnReadMiss() {
        return singleInterrogationOnReadMiss;
    }

    protected int getGeneralInterrogationQualifier() {
        return generalInterrogationQualifier;
    }

    private String resolveReadMode(String raw) {
        String normalized = raw == null || raw.isBlank() ? "AUTO" : raw.trim().toUpperCase(Locale.ROOT);
        if (Set.of("AUTO", "READ_COMMAND", "GENERAL_INTERROGATION", "SPONTANEOUS").contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported IEC104 generalInterrogationMode: " + raw);
    }

    private long resolveLongConfig(DeviceConnection connectionConfig, String key, long defaultValue) {
        Long value = connectionConfig.getLong(key, defaultValue);
        return value != null ? value : defaultValue;
    }

    private int resolveIntConfig(DeviceConnection connectionConfig, String key, int defaultValue) {
        Integer value = connectionConfig.getInt(key, defaultValue);
        return value != null ? value : defaultValue;
    }

    private long resolveInterrogationInterval(long configuredInterval, long effectiveCacheTtl) {
        if (!isGeneralInterrogationOnly() || effectiveCacheTtl <= 0) {
            return configuredInterval;
        }
        long safeInterval = Math.max(1000L, effectiveCacheTtl / 2L);
        return configuredInterval <= 0 ? safeInterval : Math.min(configuredInterval, safeInterval);
    }

    /**
     * 处理组件生命周期。
     */
    protected void startGeneralInterrogationLoop() {
        if (iec104Config == null || interrogationScheduler == null) {
            return;
        }
        long interval = generalInterrogationInterval;
        if (interval <= 0) {
            return;
        }
        cancelGeneralInterrogationTask();
        Connection scheduledConnection = connection;
        generalInterrogationTask = interrogationScheduler.scheduleAtFixedRate(() -> {
            if (!isConnected() || connection != scheduledConnection || dataTransferStopped) {
                return;
            }
            triggerInterrogation(commonAddress, 20, "scheduled");
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * 更新或刷新业务状态。
     */
    protected void triggerSingleInterrogation(int qualifier, String reason) {
        triggerSingleInterrogation(commonAddress, qualifier, reason);
    }

    /**
     * 更新或刷新业务状态。
     */
    protected void triggerSingleInterrogation(int targetCommonAddress, int qualifier, String reason) {
        triggerInterrogation(targetCommonAddress, qualifier, reason);
    }

    /**
     * 更新或刷新业务状态。
     */
    private void triggerInterrogation(int targetCommonAddress, int qualifier, String reason) {
        if (connection == null || dataTransferStopped) {
            return;
        }
        InterrogationKey key = new InterrogationKey(targetCommonAddress, qualifier);
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (pendingInterrogations.putIfAbsent(key, future) != null) {
            return;
        }
        try {
            connection.interrogation(targetCommonAddress, CauseOfTransmission.ACTIVATION,
                    new IeQualifierOfInterrogation(qualifier));
            resolveProtocolScheduler().schedule(() -> {
                if (pendingInterrogations.remove(key, future)) {
                    future.completeExceptionally(new TimeoutException("IEC104 interrogation timeout"));
                }
            }, requestTimeoutMillis(), TimeUnit.MILLISECONDS);
            log.info("触发 IEC104 总召唤 ca={} qualifier={} 原因={}", targetCommonAddress, qualifier, reason);
        } catch (Exception e) {
            pendingInterrogations.remove(key, future);
            future.completeExceptionally(e);
            log.error("触发总召唤失败 ca={} qualifier={}", targetCommonAddress, qualifier, e);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private ScheduledExecutorService resolveProtocolScheduler() {
        return ThreadPoolFallbacks.preferScheduler(
                protocolScheduler,
                DEFAULT_PROTOCOL_SCHEDULER,
                "AbstractIce104Collector",
                "iec104-protocol-shared");
    }

    /**
     * 执行当前业务逻辑。
     */
    private void cancelGeneralInterrogationTask() {
        if (generalInterrogationTask != null) {
            generalInterrogationTask.cancel(true);
            generalInterrogationTask = null;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    protected Optional<Integer> resolveSingleInterrogationQualifier(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveCommonAddress(DeviceConnection connectionConfig) {
        Integer configured = connectionConfig != null ? connectionConfig.getInt("commonAddress", null) : null;
        if (configured == null && connectionConfig != null) {
            configured = connectionConfig.getInt("slaveId", null);
        }
        if (configured != null && configured > 0) {
            return configured;
        }
        return iec104Config != null && iec104Config.getCommonAddress() > 0
                ? iec104Config.getCommonAddress()
                : 1;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveTimeout(DeviceConnection connectionConfig) {
        Integer configuredTimeout = connectionConfig != null ? connectionConfig.getTimeout() : null;
        if (configuredTimeout == null || configuredTimeout <= 0) {
            configuredTimeout = connectionConfig != null ? connectionConfig.getReadTimeout() : null;
        }
        if (configuredTimeout != null && configuredTimeout > 0) {
            return configuredTimeout;
        }
        return iec104Config != null && iec104Config.getTimeout() > 0
                ? iec104Config.getTimeout()
                : 5000;
    }

    /**
     * 执行当前业务逻辑。
     */
    protected long requestTimeoutMillis() {
        return timeout > 0 ? timeout : 5000L;
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public static record Iec104Key(int commonAddress, Integer typeId, int ioAddress) {}

    /**
     * 定义当前模块的不可变数据记录。
     */
    protected record CacheEntry(Object value, long timestamp, long observedAt, int qualityBits,
                                Integer rawTypeId, Long sourceTime) {}

    /**
     * 定义当前模块的不可变数据记录。
     */
    public static record InterrogationKey(int commonAddress, int qualifier) {}
}


