package com.wangbin.collector.core.collector.protocol.iec;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ServiceError;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.iec.base.AbstractIec61850Collector;
import com.wangbin.collector.core.collector.protocol.iec.domain.Iec61850Address;
import com.wangbin.collector.core.collector.protocol.iec.util.Iec61850AddressParser;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec61850ConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IEC61850 采集器.
 */
@Slf4j
public class Iec61850Collector extends AbstractIec61850Collector {

    private final Map<String, DataPoint> subscriptionPoints = new ConcurrentHashMap<>();

    @Override
    public String getCollectorType() {
        return "IEC61850";
    }

    @Override
    public String getProtocolType() {
        return "IEC61850";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        initIec61850Config(deviceInfo);
        DeviceConnection connectionConfig = requireConnectionConfig();
        try {
            ConnectionAdapter<?> adapter = createManagedConnection(connectionConfig);
            Iec61850ConnectionAdapter iec61850Adapter = requireAdapterType(
                    adapter,
                    Iec61850ConnectionAdapter.class,
                    "IEC61850");
            iec61850Adapter.setClientEventListener(createClientEventListener());
            connectManagedConnection();
            this.association = iec61850Adapter.getClient();
            reloadServerModel();
        } catch (Exception e) {
            removeConnectionSilently();
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeConnectionSilently();
        closeAssociation();
        log.info("IEC61850 连接 已关闭:{}:{}", host, port);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        Iec61850Address address = Iec61850AddressParser.parse(point, resolveDefaultFc(point));
        return readAttribute(address);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        Map<String, Object> results = new HashMap<>();
        for (DataPoint point : points) {
            try {
                Object value = doReadPoint(point);
                results.put(point.getPointId(), value);
            } catch (Exception e) {
                log.error("IEC61850 批量 读取 失败, 点位={}", point.getPointName(), e);
                results.put(point.getPointId(), null);
            }
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        Iec61850Address address = Iec61850AddressParser.parse(point, resolveDefaultFc(point));
        log.debug("IEC61850 写入:点位={}, 地址={}, 值={}",
                point.getPointName(), address.getObjectReference(), value);
        return writeAttribute(address, value);
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
                log.error("IEC61850 写入 失败, 点位={}", entry.getKey().getPointName(), e);
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
            try {
                Iec61850Address address = Iec61850AddressParser.parse(point, resolveDefaultFc(point));
                subscriptionPoints.put(address.getCacheKey(), point);
            } catch (Exception e) {
                log.warn("IEC61850 订阅解析失败, 点位={}", point.getPointName(), e);
            }
            subscribedPointMap.put(point.getPointId(), point);
        }
        log.info("IEC61850 订阅完成, 数量={}", points.size());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            subscriptionPoints.clear();
            log.info("IEC61850 全部取消订阅");
            return;
        }
        for (DataPoint point : points) {
            try {
                Iec61850Address address = Iec61850AddressParser.parse(point, resolveDefaultFc(point));
                subscriptionPoints.remove(address.getCacheKey());
            } catch (Exception e) {
                log.warn("IEC61850 取消订阅解析失败, 点位={}", point.getPointName(), e);
            }
            subscribedPointMap.remove(point.getPointId());
        }
        log.info("IEC61850 取消订阅数量={}", points.size());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.HOST, host);
        status.put(CommonMapKeys.PORT, port);
        status.put(CommonMapKeys.CONNECTED, association != null);
        status.put("modelLoaded", serverModel != null);
        status.put(CommonMapKeys.TIMEOUT, timeout);
        status.put("lastError", lastError);
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        switch (command.toLowerCase()) {
            case "reload_model":
            case "refresh_model":
                reloadServerModel();
                return "model reloaded";
            case "set_timeout":
                Object timeoutValue = params.get(CommonMapKeys.TIMEOUT);
                if (timeoutValue == null) {
                    throw new IllegalArgumentException("missing timeout param");
                }
                int newTimeout = Integer.parseInt(timeoutValue.toString());
                timeout = newTimeout;
                if (association != null) {
                    association.setResponseTimeout(newTimeout);
                }
                return "timeout updated";
            case "read":
            case "read_raw": {
                Iec61850Address address = parseCommandAddress(params);
                return readAttribute(address);
            }
            case "write":
            case "write_raw": {
                Iec61850Address address = parseCommandAddress(params);
                Object value = params.get(CommonMapKeys.VALUE);
                if (value == null) {
                    throw new IllegalArgumentException("missing value param");
                }
                writeAttribute(address, value);
                return "write success";
            }
            case "select": {
                Iec61850Address address = parseCommandAddress(params);
                FcModelNode node = resolveFcNode(address);
                boolean ok = association.select(node);
                return ok;
            }
            case "operate": {
                Iec61850Address address = parseCommandAddress(params);
                FcModelNode node = resolveFcNode(address);
                Object value = params.get(CommonMapKeys.VALUE);
                if (value != null && node instanceof BasicDataAttribute attribute) {
                    applyWriteValue(attribute, value);
                }
                association.operate(node);
                return "operate sent";
            }
            default:
                throw new IllegalArgumentException("Unsupported IEC61850 command: " + command);
        }
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        log.info("IEC61850 点位已加载 数量={}", points.size());
    }

    /**
     * 解析或转换业务数据。
     */
    private Fc resolveDefaultFc(DataPoint point) {
        String dataType = point.getDataType();
        if (dataType == null) {
            return Fc.ST;
        }
        return switch (dataType.toUpperCase()) {
            case "ANALOG", "FLOAT", "DOUBLE" -> Fc.MX;
            case "CONTROL", "COMMAND" -> Fc.CO;
            default -> Fc.ST;
        };
    }

    /**
     * 处理当前业务流程。
     */
    @Override
    protected void handleReportValue(BasicDataAttribute attribute, Object value) {
        String key = attribute.getReference() + "@" + attribute.getFc();
        DataPoint point = subscriptionPoints.get(key);
        if (point == null) {
            return;
        }
        ProcessResult processResult = ingestPushedValue(point, value);
        Object finalValue = processResult != null ? processResult.getFinalValue() : value;
        log.info("IEC61850 上报:点位={} 值={}", point.getPointId(), finalValue);
    }

    /**
     * 解析或转换业务数据。
     */
    private Iec61850Address parseCommandAddress(Map<String, Object> params) {
        Object addr = params.get(CommonMapKeys.ADDRESS);
        if (addr == null) {
            throw new IllegalArgumentException("missing address param");
        }
        String fc = params.get("fc") != null ? params.get("fc").toString() : null;
        return Iec61850AddressParser.parse(addr.toString(), fc, Fc.ST);
    }

    /**
     * 解析或转换业务数据。
     */
    private FcModelNode resolveFcNode(Iec61850Address address) {
        if (address == null) {
            throw new IllegalArgumentException("address parse failed");
        }
        com.beanit.iec61850bean.ModelNode node = findModelNode(address);
        if (!(node instanceof FcModelNode fcNode)) {
            throw new IllegalArgumentException("address is not fc node: " + address);
        }
        return fcNode;
    }

    /**
     * 清理或删除业务数据。
     */
    private void removeConnectionSilently() {
        removeManagedConnection("IEC61850");
        association = null;
    }
}
