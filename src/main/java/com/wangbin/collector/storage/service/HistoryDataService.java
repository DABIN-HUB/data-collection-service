package com.wangbin.collector.storage.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.port.HistoryTelemetrySink;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.buffer.HistoryWriteRequest;
import com.wangbin.collector.storage.config.TdengineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 历史数据写入与查询服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class HistoryDataService implements HistoryTelemetrySink {

    private final HistoryWriteBuffer historyWriteBuffer;
    private final TimeSeriesService timeSeriesService;
    private final ConfigManager configManager;
    private final TdengineProperties properties;

    /**
     * 保存单点历史数据，正常路径仍优先同步写入 TDengine。
     */
    @Override
    public void savePoint(String deviceId, DataPoint point, ProcessResult processResult) {
        if (!properties.isEnabled() || deviceId == null || point == null || processResult == null) {
            return;
        }
        historyWriteBuffer.writeOrBuffer(newRequest(deviceId, point, processResult));
    }

    /**
     * History stage 执行器过载时跳过同步直写，直接进入既有可靠缓冲链路。
     */
    @Override
    public boolean deferPoint(String deviceId,
                              DataPoint point,
                              ProcessResult processResult,
                              RuntimeException cause) {
        if (!properties.isEnabled() || deviceId == null || point == null || processResult == null) {
            return false;
        }
        historyWriteBuffer.deferForRetry(newRequest(deviceId, point, processResult), cause);
        return true;
    }

    /**
     * 查询指定点位的历史数据。
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

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private HistoryWriteRequest newRequest(String deviceId, DataPoint point, ProcessResult processResult) {
        return new HistoryWriteRequest(
                deviceId, resolveProtocolType(deviceId), point, processResult, System.currentTimeMillis());
    }

    private String resolveProtocolType(String deviceId) {
        try {
            DeviceInfo deviceInfo = configManager.getDevice(deviceId);
            if (deviceInfo != null && deviceInfo.getProtocolType() != null) {
                return deviceInfo.getProtocolType();
            }
        } catch (Exception exception) {
            log.debug("解析历史数据协议类型失败，设备={}", deviceId, exception);
        }
        return "UNKNOWN";
    }
}
