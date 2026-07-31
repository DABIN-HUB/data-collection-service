package com.wangbin.collector.storage.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.buffer.HistoryWriteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 处理当前模块的业务服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class HistoryDataService {

    private final HistoryWriteBuffer historyWriteBuffer;
    private final TimeSeriesService timeSeriesService;
    private final ConfigManager configManager;
    private final TdengineProperties properties;

    /**
     * 写入或持久化业务数据。
     */
    public void savePoint(String deviceId, DataPoint point, ProcessResult processResult) {
        if (!properties.isEnabled() || deviceId == null || point == null || processResult == null) {
            return;
        }
        String protocolType = "UNKNOWN";
        try {
            DeviceInfo deviceInfo = configManager.getDevice(deviceId);
            if (deviceInfo != null && deviceInfo.getProtocolType() != null) {
                protocolType = deviceInfo.getProtocolType();
            }
        } catch (Exception e) {
            log.debug("resolve protocolType from 配置 失败, 设备={}", deviceId, e);
        }
        historyWriteBuffer.writeOrBuffer(new HistoryWriteRequest(
                deviceId, protocolType, point, processResult, System.currentTimeMillis()));
    }

    /**
     * 查询并返回业务数据。
     */
    public List<Map<String, Object>> queryPointHistory(String deviceId,
                                                       String pointId,
                                                       Long startTs,
                                                       Long endTs,
                                                       Integer limit) {
        if (!properties.isEnabled()) {
            return Collections.emptyList();
        }
        return timeSeriesService.query(deviceId, pointId, startTs, endTs, limit);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

}
