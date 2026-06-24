package com.wangbin.collector.core.collector.protocol.ethernetip;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpPlcType;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;
import com.wangbin.collector.core.collector.protocol.ethernetip.util.EtherNetIpAddressParser;
import com.wangbin.collector.core.collector.protocol.ethernetip.util.EtherNetIpPlcTypeResolver;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.EtherNetIpConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcTagResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class EtherNetIpCollector extends ConnectionBackedCollector {

    @Autowired(required = false)
    private DevicePointResolver devicePointResolver;

    private EtherNetIpConnectionAdapter connectionAdapter;
    private final Map<String, EtherNetIpTagAddress> configuredAddresses = new ConcurrentHashMap<>();
    private int timeout = 5000;
    private int maxFieldsPerRequest = 64;

    @Override
    public String getCollectorType() {
        return "ETHERNET_IP";
    }

    @Override
    public String getProtocolType() {
        return "ETHERNET_IP";
    }

    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, EtherNetIpConnectionAdapter.class, "EtherNet/IP");

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }

        Integer configuredTimeout = currentConfig.getReadTimeout() != null
                ? currentConfig.getReadTimeout()
                : currentConfig.getTimeout();
        this.timeout = configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : 5000;
        this.maxFieldsPerRequest = Math.max(1, currentConfig.getInt("maxFieldsPerRequest", 64));
        log.info("PLC4X EtherNet/IP collector connected, deviceId={}, timeout={}, maxFieldsPerRequest={}",
                deviceInfo.getDeviceId(), timeout, maxFieldsPerRequest);
    }

    @Override
    protected void doDisconnect() {
        removeManagedConnection("EtherNet/IP");
        connectionAdapter = null;
        configuredAddresses.clear();
        log.info("PLC4X EtherNet/IP collector disconnected, deviceId={}", deviceInfo.getDeviceId());
    }

    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        if (!isArrayPoint(point)) {
            return super.readPoint(point);
        }
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            EtherNetIpTagAddress address = requireAddress(point);
            validateArrayPointConfiguration(point, address, "read");

            Object rawValue = doReadPoint(point);
            ProcessResult processResult = buildArrayProcessResult(point, address, rawValue, "array pass-through read");
            lastProcessResults.put(point.getPointId(), processResult);

            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return processResult.getFinalValue();
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            log.error("Point read failed {}.{}", deviceInfo.getDeviceId(), point.getPointName(), e);
            recordException(e, point);
            throw new CollectorException("点位读取失败", deviceInfo.getDeviceId(),
                    point.getPointId(), e);
        }
    }

    @Override
    public Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException {
        if (!containsArrayPoint(points)) {
            return super.readPoints(points);
        }
        checkConnection();

        Map<String, Object> results = new LinkedHashMap<>();
        List<DataPoint> scalarPoints = new ArrayList<>();
        List<DataPoint> arrayPoints = new ArrayList<>();
        partitionPoints(points, scalarPoints, arrayPoints);

        if (!scalarPoints.isEmpty()) {
            results.putAll(super.readPoints(scalarPoints));
        }
        if (arrayPoints.isEmpty()) {
            return results;
        }

        long arrayStartTime = System.currentTimeMillis();
        try {
            for (DataPoint point : arrayPoints) {
                validateArrayPointConfiguration(point, requireAddress(point), "read");
            }

            Map<String, Object> rawValues = doReadPoints(arrayPoints);
            for (DataPoint point : arrayPoints) {
                String pointId = point.getPointId();
                Object rawValue = rawValues.get(pointId);
                if (rawValue == null) {
                    results.put(pointId, null);
                    continue;
                }
                try {
                    EtherNetIpTagAddress address = requireAddress(point);
                    ProcessResult processResult = buildArrayProcessResult(point, address, rawValue,
                            "array pass-through batch read");
                    lastProcessResults.put(pointId, processResult);
                    results.put(pointId, processResult.getFinalValue());
                } catch (Exception e) {
                    log.error("濠电姷鏁告慨鐑藉极閸涘﹥鍙忓ù鍏兼綑閸ㄥ倿鏌ｉ幘宕囧哺闁哄鐗楃换娑㈠箣閻愯尙鍔伴梺绋款儐閹告悂锝炲┑瀣亗閹兼番鍨昏ぐ搴繆?EtherNet/IP 闂傚倸鍊搁崐鎼佸磹瀹勬噴褰掑炊瑜滃ù鏍煏婵炵偓娅嗛柛濠傛健閺屻劑寮崒娑欑彧闂佺粯绻傞悥濂稿蓟濞戙垹鐒洪柛鎰典簼閸Ｑ囨⒑閹肩偛濡界紒璇插閸┾偓妞ゆ巻鍋撶紒鐘茬Ч瀹曟洟鏌嗗畵銉ユ处鐎靛ジ寮堕幋鐙呯串闂備胶绮崹鍏兼叏閵堝鐓曢柟瀵稿亼娴滄粓鏌熼弶鍨暢闁诡喖銈搁弻鏇㈠幢濡櫣顑傜紓浣介哺鐢顕ラ崟顓涘亾閿濆骸浜滈柛鐐差樀濮婃椽宕崟顐У闂佸憡鎸荤换鍫ョ嵁韫囨稑宸濋柡澶嬪灣缁卞爼姊洪崨濠冪闁诲繑鑹捐濠㈣埖鍔栭悡鐔煎箹濞ｎ剙鈧倕顭囬幇鐗堢厵闁告縿鍎遍崢鎾煙椤旀儳浠遍柡浣稿暣閸┾偓妞ゆ帒瀚烽弫瀣煏婢跺棙娅呯紒鈧€ｎ偁浜滈柟鍝勭Х閸忓瞼绱掓径搴㈢【妞ゎ亜鍟存俊鍫曞幢濡も偓椤洭姊? {}.{}", deviceInfo.getDeviceId(), point.getPointName(), e);
                    recordException(e, point);
                    results.put(pointId, null);
                }
            }

            totalReadCount.addAndGet(arrayPoints.size());
            totalReadTime.addAndGet(System.currentTimeMillis() - arrayStartTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            log.error("闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗ù锝堟缁€濠傗攽閻樻彃浜為柣鎺旀櫕閹叉瓕绠涢弴鐕佹綗闂佺粯鍔曢顓犲姬閳ь剟姊洪幖鐐插妧闁搞儺鐓夌槐顒€鈹戦悩鍨毄闁稿绋戣灒濠电姴鍟伴々鍙夌節闂堟侗鍎忕痪鎯х秺閺岋綁骞嬮敐鍛呮捇鏌嶉柨瀣仸闁靛洤瀚伴獮鍥礈娴ｇ懓浠归梻渚€娼уΛ娆戞暜閻愬灚顫曢柟鐑樻尭缁剁偤鎮楅敐搴′簻闁哥偛顦靛铏规嫚閳ヨ櫕鐏堢紓鍌氱Т閿曘倝鎮鹃悜钘夐唶闁哄洢鍔嶉弲婊堟倵楠炲灝鍔氶柟鍐茬箻椤㈡瑩宕熼娑氬幗闂佺粯鏌ㄩ幗婊堟儗鐎ｎ偆绡€闁靛繆鍩楅鍡楀疾闂備焦瀵уú宥夊磻閹炬番浜滈柡鍥崝锔锯偓瑙勬礈閸犳牠銆佸Δ浣瑰闁惧繐澧ｉ妶鍡曠箚闁绘劦浜滈埀顒佺墵瀹曟繈骞嬮敃鈧壕? {}", deviceInfo.getDeviceId(), e);
            recordException(e, null);
            throw new CollectorException("批量读取失败", deviceInfo.getDeviceId(),
                    null, e);
        }
    }

    @Override
    public boolean writePoint(DataPoint point, Object value) throws CollectorException {
        if (!isArrayPoint(point)) {
            return super.writePoint(point, value);
        }
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                throw new CollectorException("点位不可写", deviceInfo.getDeviceId(), point.getPointId());
            }

            EtherNetIpTagAddress address = requireAddress(point);
            validateArrayPointConfiguration(point, address, "write");
            boolean result = doWritePoint(point, value);

            totalWriteCount.incrementAndGet();
            totalWriteTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return result;
        } catch (CollectorException e) {
            throw e;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            log.error("闂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛鎾茬閸ㄦ繃銇勯弽顐粶缂佲偓婢跺绠鹃柛鈩兩戠亸顓㈡煟閹烘垹浠涢柕鍥у楠炴帒顓奸崼婵嗗腐闂備線娼уΛ娆戞暜閻愬灚顫曢柟鐑樻尭缁剁偤鎮楅敐搴′簻闁哥偛顦靛娲传閸曨剚鎷遍梺鐑╂櫓閸ㄥ爼鎮伴鈧畷鍫曨敆婢跺娅嶉梻浣虹帛閿氶柛鐔风仢閳诲秹骞嬮敂瑙ｆ嫽婵炶揪绲介幉锟犲疮閻愮儤鐓熼柣鏃€娼欓崝姘舵懚閻愬绠鹃柛鈩冾殕缁傚鏌涢妶鍡╂疁闁哄本鐩鎾Ω閵夈儺娼界紓鍌氬€哥粔鏉懨洪敃鍌毼﹂柛鏇ㄥ灠缁犳盯鏌嶆潪鎵槮濠? {}.{}", deviceInfo.getDeviceId(), point.getPointName(), e);
            recordException(e, point);
            throw new CollectorException("点位写入失败", deviceInfo.getDeviceId(),
                    point.getPointId(), e);
        }
    }

    @Override
    public Map<String, Boolean> writePoints(Map<DataPoint, Object> points) throws CollectorException {
        if (!containsArrayPoint(points != null ? points.keySet() : null)) {
            return super.writePoints(points);
        }
        checkConnection();

        Map<String, Boolean> results = new LinkedHashMap<>();
        Map<DataPoint, Object> scalarPoints = new LinkedHashMap<>();
        Map<DataPoint, Object> arrayPoints = new LinkedHashMap<>();
        partitionPointValues(points, scalarPoints, arrayPoints);

        if (!scalarPoints.isEmpty()) {
            results.putAll(super.writePoints(scalarPoints));
        }
        if (arrayPoints.isEmpty()) {
            return results;
        }

        long arrayStartTime = System.currentTimeMillis();
        try {
            for (Map.Entry<DataPoint, Object> entry : arrayPoints.entrySet()) {
                DataPoint point = entry.getKey();
                if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                    results.put(point.getPointId(), false);
                    continue;
                }
                try {
                    EtherNetIpTagAddress address = requireAddress(point);
                    validateArrayPointConfiguration(point, address, "write");
                    results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
                } catch (Exception e) {
                    log.error("PLC4X EtherNet/IP 闂傚倸鍊搁崐鎼佸磹瀹勬噴褰掑炊瑜滃ù鏍煏婵炵偓娅嗛柛濠傛健閺屻劑寮崒娑欑彧闂佺粯绻傞悥濂稿蓟濞戙垹鐒洪柛鎰典簼閸Ｑ囨⒑閹肩偛濡界紒璇插閸┾偓妞ゆ巻鍋撶紒鐘茬Ч瀹曟洟鏌嗗畵銉ユ处鐎靛ジ寮堕幋鐙呯串闂備胶绮崹鍏兼叏閵堝鐓曢柟瀵稿亼娴滄粓鏌熼弶鍨暢闁诡喖銈搁弻鏇㈠幢濡櫣顑傜紓浣介哺鐢顕ラ崟顓涘亾閿濆骸浜滈柛鐐差樀濮婃椽宕崟顒佹嫳闂佺儵鏅╅崹鍫曟偘椤曗偓瀹曞爼顢楁径瀣珝闂備胶绮敋闁哥喎鐏濋埢宥夊箣閿旇В鎷绘繛杈剧到閹诧繝宕悙鐑樼厽闁绘梹娼欓崝姘舵懚閻愬绠鹃柛鈩冾殕缁傚鏌涢妶鍡╂疁闁哄本鐩鎾Ω閵夈儺娼界紓鍌氬€哥粔鏉懨洪敃鍌毼﹂柛鏇ㄥ灠缁犳盯鏌嶆潪鎵槮濠? pointId={}", point.getPointId(), e);
                    recordException(e, point);
                    results.put(point.getPointId(), false);
                }
            }

            totalWriteCount.addAndGet(arrayPoints.size());
            totalWriteTime.addAndGet(System.currentTimeMillis() - arrayStartTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            log.error("闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗ù锝堟缁€濠傗攽閻樻彃浜為柣鎺旀櫕閹叉瓕绠涢弴鐕佹綗闂佺粯鍔曢顓犲姬閳ь剟姊洪幖鐐插妧闁搞儺鐓夌槐顒€鈹戦悩鍨毄闁稿绋戣灒濠电姴鍟伴々鍙夌節闂堟侗鍎忕痪鎯х秺閺岋綁骞嬮敐鍛呮捇鏌嶉柨瀣仸闁靛洤瀚伴獮鍥礈娴ｇ懓浠归梻渚€娼уΛ娆戞暜閻愬灚顫曢柟鐑樻尭缁剁偤鎮楅敐搴′簻闁哥偛顦靛娲传閸曨剚鎷遍梺鐑╂櫓閸ㄥ爼鎮伴鈧畷鍫曨敆婢跺娅嶉梻浣虹帛閿氶柛鐔风仢閳诲秹骞嬮敂瑙ｆ嫽婵炶揪绲介幉锟犲疮閻愮儤鐓熼柣鏃€娼欓崝姘舵懚閻愬绠鹃柛鈩冾殕缁傚鏌涢妶鍡╂疁闁哄本鐩鎾Ω閵夈儺娼界紓鍌氬€哥粔鏉懨洪敃鍌毼﹂柛鏇ㄥ灠缁犳盯鏌嶆潪鎵槮濠? {}", deviceInfo.getDeviceId(), e);
            recordException(e, null);
            throw new CollectorException("批量写入失败", deviceInfo.getDeviceId(),
                    null, e);
        }
    }

    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        EtherNetIpTagAddress address = requireAddress(point);
        String fieldName = tagName(point);

        PlcReadResponse response = await(requireConnection().getClient()
                .readRequestBuilder()
                .addTagAddress(fieldName, address.getPlc4xAddress())
                .build()
                .execute());
        ensureResponseOk(response, fieldName, "read");
        return extractValue(response, fieldName, point, address);
    }

    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        Map<String, Object> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        List<DataPoint> batch = new ArrayList<>(Math.min(points.size(), maxFieldsPerRequest));
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            batch.add(point);
            if (batch.size() >= maxFieldsPerRequest) {
                executeReadBatch(batch, results);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            executeReadBatch(batch, results);
        }
        return results;
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        EtherNetIpTagAddress address = requireAddress(point);
        String fieldName = tagName(point);

        PlcWriteResponse response = await(requireConnection().getClient()
                .writeRequestBuilder()
                .addTagAddress(fieldName, address.getPlc4xAddress(), coerceWriteValue(value, address, point))
                .build()
                .execute());
        ensureResponseOk(response, fieldName, "write");
        return true;
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        try {
            PlcWriteRequest.Builder builder = requireConnection().getClient().writeRequestBuilder();
            List<DataPoint> orderedPoints = new ArrayList<>();

            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                EtherNetIpTagAddress address = requireAddress(point);
                builder.addTagAddress(tagName(point), address.getPlc4xAddress(),
                        coerceWriteValue(entry.getValue(), address, point));
                orderedPoints.add(point);
            }

            PlcWriteResponse response = await(builder.build().execute());
            for (DataPoint point : orderedPoints) {
                String fieldName = tagName(point);
                results.put(point.getPointId(), response != null && response.getResponseCode(fieldName) == PlcResponseCode.OK);
            }
            return results;
        } catch (Exception ex) {
            log.warn("PLC4X EtherNet/IP batch write failed, falling back to point-by-point writes: {}", ex.getMessage());
            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                try {
                    results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
                } catch (Exception singleEx) {
                    log.error("PLC4X EtherNet/IP point write failed, pointId={}", point.getPointId(), singleEx);
                    results.put(point.getPointId(), false);
                }
            }
            return results;
        }
    }

    @Override
    protected void doSubscribe(List<DataPoint> points) {
        cacheAddresses(points);
        throw unsupported("subscribe", "PLC4X Logix driver metadata reports subscribe unsupported for the current connection");
    }

    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            configuredAddresses.clear();
            return;
        }
        for (DataPoint point : points) {
            configuredAddresses.remove(cacheKey(point));
        }
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("protocol", getProtocolType());
        status.put("driver", "PLC4X");
        status.put("implemented", true);
        status.put("writable", true);
        status.put("subscribable", false);
        status.put("isConnected", isConnected());
        status.put("configuredPointCount", configuredAddresses.size());
        status.put("maxFieldsPerRequest", maxFieldsPerRequest);

        DeviceConnection connection = getCurrentConnectionConfig();
        if (connection != null) {
            status.put("host", connection.getHost());
            status.put("port", connection.getPort());
            status.put("communicationPath", connection.getString("communicationPath", null));
            status.put("backplane", connection.getInt("backplane", 1));
            status.put("slot", connection.getInt("slot", 0));
            status.put("timeout", connection.getReadTimeout() != null ? connection.getReadTimeout() : connection.getTimeout());
        }

        if (connectionAdapter != null) {
            status.put("connectionString", connectionAdapter.getConnectionString());
        }
        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        String normalized = normalizeCommand(command);
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        return switch (normalized) {
            case "read", "read_point", "readpoint" -> executeCommandRead(safeParams);
            case "write", "write_point", "writepoint" -> executeCommandWrite(safeParams);
            case "status", "diagnostic" -> getDeviceStatus();
            default -> throw new IllegalArgumentException("Unsupported PLC4X EtherNet/IP command: " + command);
        };
    }

    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        cacheAddresses(points);
    }

    private void cacheAddresses(List<DataPoint> points) {
        configuredAddresses.clear();
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            configuredAddresses.put(cacheKey(point), EtherNetIpAddressParser.parse(point));
        }
    }

    private EtherNetIpTagAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(cacheKey(point), ignored -> EtherNetIpAddressParser.parse(point));
    }

    private UnsupportedOperationException unsupported(String operation) {
        return unsupported(operation, null);
    }

    private UnsupportedOperationException unsupported(String operation, String reason) {
        String message = String.format("PLC4X EtherNet/IP collector does not implement %s", operation);
        if (reason != null && !reason.isBlank()) {
            message = message + ": " + reason;
        }
        log.warn(message);
        return new UnsupportedOperationException(message);
    }

    private void executeReadBatch(List<DataPoint> batch, Map<String, Object> results) {
        try {
            PlcReadResponse response = executeReadBatchRequest(batch);
            for (DataPoint point : batch) {
                if (point == null || point.getPointId() == null) {
                    continue;
                }
                String fieldName = tagName(point);
                if (response == null || response.getResponseCode(fieldName) != PlcResponseCode.OK) {
                    results.put(point.getPointId(), null);
                    continue;
                }
                results.put(point.getPointId(), extractValue(response, fieldName, point, requireAddress(point)));
            }
        } catch (Exception ex) {
            log.error("PLC4X EtherNet/IP batch read failed, deviceId={}, batchSize={}", deviceInfo.getDeviceId(), batch.size(), ex);
            for (DataPoint point : batch) {
                if (point != null && point.getPointId() != null) {
                    results.put(point.getPointId(), null);
                }
            }
        }
    }

    private PlcReadResponse executeReadBatchRequest(List<DataPoint> batch) throws Exception {
        var builder = requireConnection().getClient().readRequestBuilder();
        for (DataPoint point : batch) {
            if (point == null) {
                continue;
            }
            EtherNetIpTagAddress address = requireAddress(point);
            builder.addTagAddress(tagName(point), address.getPlc4xAddress());
        }
        return await(builder.build().execute());
    }

    private Object extractValue(PlcReadResponse response, String fieldName, DataPoint point, EtherNetIpTagAddress address) {
        PlcValue plcValue = response.getPlcValue(fieldName);
        if (plcValue == null || plcValue.isNull()) {
            return null;
        }
        if (plcValue.isList()) {
            if (address.isScalar() && plcValue.getLength() == 1) {
                plcValue = plcValue.getIndex(0);
            } else {
                return extractArrayValue(plcValue, point, address);
            }
        }

        if (!address.isScalar()) {
            throw new IllegalStateException("EtherNet/IP array point did not return list payload: " + address.getRawAddress());
        }
        return coerceScalarValue(plcValue, resolvePointType(point, address));
    }

    private Object coerceScalarValue(PlcValue plcValue, EtherNetIpPlcType plcType) {
        if (plcValue == null) {
            return null;
        }
        return plcType != null ? plcType.read(plcValue) : plcValue.getObject();
    }

    private List<Object> extractArrayValue(PlcValue plcValue, DataPoint point, EtherNetIpTagAddress address) {
        List<Object> values = new ArrayList<>();
        EtherNetIpPlcType plcType = resolvePointType(point, address);
        int length = plcValue.getLength();
        for (int i = 0; i < length; i++) {
            values.add(coerceScalarValue(plcValue.getIndex(i), plcType));
        }
        return values;
    }

    private EtherNetIpPlcType resolvePointType(DataPoint point, EtherNetIpTagAddress address) {
        return EtherNetIpPlcTypeResolver.INSTANCE.resolveOrNull(point, address);
    }

    private Object coerceWriteValue(Object value, EtherNetIpTagAddress address, DataPoint point) {
        if (!address.isScalar()) {
            return coerceWriteArrayValue(value, address, point);
        }
        return coerceWriteScalarValue(value, address, point);
    }

    private Object coerceWriteScalarValue(Object value, EtherNetIpTagAddress address, DataPoint point) {
        if (value == null) {
            return null;
        }
        EtherNetIpPlcType plcType = resolvePointType(point, address);
        return plcType != null ? plcType.write(value) : value;
    }

    private List<Object> coerceWriteArrayValue(Object value, EtherNetIpTagAddress address, DataPoint point) {
        List<Object> sourceValues = toObjectList(value);
        if (sourceValues.isEmpty()) {
            throw new IllegalArgumentException("EtherNet/IP array write value cannot be empty");
        }
        if (address.getArraySize() > 1 && sourceValues.size() != address.getArraySize()) {
            throw new IllegalArgumentException("EtherNet/IP array write size mismatch, expected "
                    + address.getArraySize() + " but got " + sourceValues.size());
        }

        List<Object> coerced = new ArrayList<>(sourceValues.size());
        for (Object sourceValue : sourceValues) {
            coerced.add(coerceWriteScalarValue(sourceValue, address, point));
        }
        return coerced;
    }

    private List<Object> toObjectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
            return values;
        }
        throw new IllegalArgumentException("EtherNet/IP array write requires collection or array value");
    }

    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            throw new IllegalStateException("PLC4X EtherNet/IP " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X EtherNet/IP " + operation + " failed with response code: " + code);
        }
    }

    private <T> T await(CompletableFuture<? extends T> future) throws Exception {
        return future.get(timeout, TimeUnit.MILLISECONDS);
    }

    private EtherNetIpConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X EtherNet/IP connection has not been established");
        }
        return connectionAdapter;
    }

    private String cacheKey(DataPoint point) {
        if (point.getPointId() != null && !point.getPointId().isBlank()) {
            return point.getPointId();
        }
        if (point.getAddress() != null && !point.getAddress().isBlank()) {
            return point.getAddress();
        }
        if (point.getPointCode() != null && !point.getPointCode().isBlank()) {
            return point.getPointCode();
        }
        throw new IllegalArgumentException("Point cache key cannot be resolved");
    }

    private String tagName(DataPoint point) {
        return point.getPointId() != null && !point.getPointId().isBlank()
                ? point.getPointId()
                : cacheKey(point);
    }

    private Object executeCommandRead(Map<String, Object> params) throws Exception {
        DataPoint point = resolveCommandPoint(params);
        Object value = readPoint(point);
        Map<String, Object> result = new LinkedHashMap<>();
        populatePointMetadata(result, point);
        result.put("value", value);
        return result;
    }

    private Object executeCommandWrite(Map<String, Object> params) throws Exception {
        DataPoint point = resolveCommandPoint(params);
        if (!params.containsKey("value")) {
            throw new IllegalArgumentException("value is required");
        }
        Object value = params.get("value");
        boolean success = writePoint(point, value);
        Map<String, Object> result = new LinkedHashMap<>();
        populatePointMetadata(result, point);
        result.put("value", value);
        result.put("success", success);
        return result;
    }

    private DataPoint resolveCommandPoint(Map<String, Object> params) {
        List<DataPoint> points = configManager != null && deviceInfo != null
                ? configManager.getDataPoints(deviceInfo.getDeviceId())
                : Collections.emptyList();
        if (points.isEmpty()) {
            throw new IllegalArgumentException("No configured EtherNet/IP points found for device: "
                    + (deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN"));
        }

        String pointRef = firstNonBlank(
                asText(params.get("pointRef")),
                asText(params.get("pointId")),
                asText(params.get("pointCode")),
                asText(params.get("pointName")),
                asText(params.get("field")),
                asText(params.get("reportField"))
        );
        if (hasText(pointRef)) {
            DataPoint point = resolveConfiguredPoint(points, pointRef);
            if (point != null) {
                return point;
            }
        }

        String address = asText(params.get("address"));
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

        throw new IllegalArgumentException("Unable to resolve EtherNet/IP point from command params");
    }

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

    private boolean matchesPointRef(DataPoint point, String normalizedRef) {
        return point != null
                && (normalizedRef.equals(normalize(point.getReportField()))
                || normalizedRef.equals(normalize(point.getPointAlias()))
                || normalizedRef.equals(normalize(point.getPointCode()))
                || normalizedRef.equals(normalize(point.getPointId()))
                || normalizedRef.equals(normalize(point.getPointName())));
    }

    private void populatePointMetadata(Map<String, Object> target, DataPoint point) {
        target.put("pointId", point.getPointId());
        target.put("pointCode", point.getPointCode());
        target.put("pointName", point.getPointName());
        if (point.getAddress() != null) {
            target.put("address", point.getAddress());
        }
    }

    private String normalizeCommand(String command) {
        return command != null ? command.trim().toLowerCase(Locale.ROOT).replace('-', '_') : "";
    }

    private String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

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

    private String asText(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isArrayPoint(DataPoint point) {
        if (point == null) {
            return false;
        }
        return !requireAddress(point).isScalar();
    }

    private boolean containsArrayPoint(Iterable<DataPoint> points) {
        if (points == null) {
            return false;
        }
        for (DataPoint point : points) {
            if (point != null && point.isEnabled() && isArrayPoint(point)) {
                return true;
            }
        }
        return false;
    }

    private void partitionPoints(List<DataPoint> points, List<DataPoint> scalarPoints, List<DataPoint> arrayPoints) {
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null || !point.isEnabled()) {
                continue;
            }
            if (isArrayPoint(point)) {
                arrayPoints.add(point);
            } else {
                scalarPoints.add(point);
            }
        }
    }

    private void partitionPointValues(Map<DataPoint, Object> points,
                                      Map<DataPoint, Object> scalarPoints,
                                      Map<DataPoint, Object> arrayPoints) {
        if (points == null) {
            return;
        }
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null) {
                continue;
            }
            if (isArrayPoint(point)) {
                arrayPoints.put(point, entry.getValue());
            } else {
                scalarPoints.put(point, entry.getValue());
            }
        }
    }

    private void validateArrayPointConfiguration(DataPoint point,
                                                 EtherNetIpTagAddress address,
                                                 String operation) {
        if (point == null || address == null || address.isScalar()) {
            return;
        }
        if (point.getScalingFactor() != null && point.getScalingFactor() != 0 && point.getScalingFactor() != 1.0d) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support scalingFactor: "
                    + point.getPointId());
        }
        if (point.getOffset() != null && point.getOffset() != 0.0d) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support offset: "
                    + point.getPointId());
        }
        if (point.getPrecision() != null) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support precision: "
                    + point.getPointId());
        }
        if (point.getMinValue() != null || point.getMaxValue() != null) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support min/max validation: "
                    + point.getPointId());
        }
        if (point.getAlarmEnabled() != null && point.getAlarmEnabled() == 1) {
            throw new IllegalArgumentException("EtherNet/IP " + operation + " array point does not support alarm processing: "
                    + point.getPointId());
        }
    }

    private ProcessResult buildArrayProcessResult(DataPoint point,
                                                  EtherNetIpTagAddress address,
                                                  Object rawValue,
                                                  String message) {
        if (!(rawValue instanceof Collection<?>) && !(rawValue != null && rawValue.getClass().isArray())) {
            throw new IllegalArgumentException("EtherNet/IP array point did not produce collection payload: " + point.getPointId());
        }
        ProcessResult processResult = ProcessResult.success(rawValue, rawValue, message);
        processResult.addMetadata("arrayValue", true);
        processResult.addMetadata("arraySize", address.getArraySize());
        processResult.addMetadata("processingMode", "protocol_passthrough");
        if (point != null && point.getAddress() != null) {
            processResult.addMetadata("address", point.getAddress());
        }
        return processResult;
    }

    @Override
    public boolean isConnected() {
        return connectionAdapter != null && connectionAdapter.isConnected();
    }
}