package com.wangbin.collector.core.collector.protocol.custom;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.base.BaseCollector;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 自定义协议采集器占位实现。
 *
 * 当前仅用于保证工厂创建和生命周期可用，
 * 实际协议读写/订阅逻辑需要按现场私有协议补齐。
 */
@Slf4j
public class CustomProtocolCollector extends BaseCollector {

    @Override
    public String getCollectorType() {
        return resolveProtocolType();
    }

    @Override
    public String getProtocolType() {
        return resolveProtocolType();
    }

    @Override
    protected void doConnect() {
        log.info("Custom protocol collector connected, deviceId={}, protocolType={}",
                deviceInfo.getDeviceId(), resolveProtocolType());
    }

    @Override
    protected void doDisconnect() {
        log.info("Custom protocol collector disconnected, deviceId={}, protocolType={}",
                deviceInfo.getDeviceId(), resolveProtocolType());
    }

    @Override
    protected Object doReadPoint(DataPoint point) {
        throw unsupported("readPoint");
    }

    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        throw unsupported("readPoints");
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) {
        throw unsupported("writePoint");
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        throw unsupported("writePoints");
    }

    @Override
    protected void doSubscribe(List<DataPoint> points) {
        throw unsupported("subscribe");
    }

    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        throw unsupported("unsubscribe");
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        return Collections.emptyMap();
    }

    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
        throw unsupported("executeCommand");
    }

    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        log.debug("Custom protocol buildReadPlans noop, deviceId={}, protocolType={}",
                deviceId, resolveProtocolType());
    }

    private String resolveProtocolType() {
        if (deviceInfo != null && deviceInfo.getProtocolType() != null && !deviceInfo.getProtocolType().isBlank()) {
            return deviceInfo.getProtocolType().trim().toUpperCase();
        }
        return "CUSTOM_TCP";
    }

    private UnsupportedOperationException unsupported(String operation) {
        String message = String.format("%s collector does not implement %s yet", resolveProtocolType(), operation);
        log.warn(message);
        return new UnsupportedOperationException(message);
    }
}
