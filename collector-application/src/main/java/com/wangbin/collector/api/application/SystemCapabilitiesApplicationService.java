package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.SystemCapabilitiesResponse;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import com.wangbin.collector.monitor.metrics.CloudReportMonitorService;
import com.wangbin.collector.storage.service.HistoryDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 计算当前 JVM 实际可用的系统能力，不以类是否存在推断能力。 */
@Service
@RequiredArgsConstructor
public class SystemCapabilitiesApplicationService {
    private final ObjectProvider<HistoryDataService> historyDataServiceProvider;
    private final ObjectProvider<CollectionManager> collectionManagerProvider;
    private final ObjectProvider<ShadowManager> shadowManagerProvider;
    private final ObjectProvider<CloudReportMonitorService> cloudReportMonitorProvider;
    private final ObjectProvider<CloudOutboxService> cloudOutboxProvider;
    private final ObjectProvider<CloudOperationsApplicationService> cloudOperationsProvider;

    public SystemCapabilitiesResponse getCapabilities() {
        HistoryDataService history = historyDataServiceProvider.getIfAvailable();
        boolean historyAvailable = history != null && history.isEnabled();
        String historyReason = historyAvailable ? null : "当前服务未启用历史存储能力";
        boolean controlAvailable = collectionManagerProvider.getIfAvailable() != null;
        ShadowManager shadow = shadowManagerProvider.getIfAvailable();
        boolean shadowAvailable = shadow != null;
        boolean cloudMonitoringAvailable = cloudReportMonitorProvider.getIfAvailable() != null;
        return SystemCapabilitiesResponse.builder()
                .realtime(SystemCapabilitiesResponse.Realtime.builder()
                        .browserTransport("HTTP_POLLING")
                        .websocketAvailable(false)
                        .sseAvailable(false)
                        .pollingAvailable(true)
                        .build())
                .history(SystemCapabilitiesResponse.History.builder()
                        .available(historyAvailable)
                        .backend("TDENGINE")
                        .reason(historyReason)
                        .build())
                .control(SystemCapabilitiesResponse.Control.builder()
                        .available(controlAvailable)
                        .readbackSupported(controlAvailable)
                        .build())
                .shadow(SystemCapabilitiesResponse.Shadow.builder().available(shadowAvailable).build())
                .cloud(SystemCapabilitiesResponse.Cloud.builder()
                        .monitoringAvailable(cloudMonitoringAvailable)
                        .managementAvailable(cloudOperationsProvider.getIfAvailable() != null && cloudOutboxProvider.getIfAvailable() != null)
                        .build())
                .build();
    }
}
