package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.CloudFlushResponse;
import com.wangbin.collector.api.controller.dto.CloudOutboxDetailResponse;
import com.wangbin.collector.api.controller.dto.CloudOutboxItemResponse;
import com.wangbin.collector.api.controller.dto.CloudTestResponse;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.outbox.CloudOutboxMessage;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.outbox.CloudOutboxStatus;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.common.web.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CloudOperationsApplicationService {
    private final ObjectProvider<CloudOutboxService> outboxProvider;
    private final ObjectProvider<ReportProperties> reportPropertiesProvider;

    public List<CloudOutboxItemResponse> list(CloudOutboxStatus status, String deviceId, int limit) {
        CloudOutboxService service = outboxProvider.getIfAvailable();
        if (service == null) return List.of();
        return service.list(status, deviceId, Math.min(200, Math.max(1, limit))).stream()
                .map(CloudOutboxItemResponse::from).toList();
    }

    public Optional<CloudOutboxDetailResponse> detail(String messageId) {
        CloudOutboxService service = outboxProvider.getIfAvailable();
        if (service == null) return Optional.empty();
        return service.find(messageId).map(CloudOutboxDetailResponse::from);
    }

    public ApiResult<Void> replay(String messageId) {
        CloudOutboxService service = outboxProvider.getIfAvailable();
        if (service == null || !service.replay(messageId)) {
            return ApiResult.error(ResultCode.DATA_NOT_FOUND.getCode(), "仅支持重放存在且处于 ISOLATED 状态的消息");
        }
        return ApiResult.success("云端发件箱消息已重新进入发送队列", null);
    }

    public CloudFlushResponse flush() {
        CloudOutboxService service = outboxProvider.getIfAvailable();
        long pending = service == null ? 0L : service.getPendingCount();
        long isolated = service == null ? 0L : service.getIsolatedCount();
        if (service != null) service.flush();
        return CloudFlushResponse.builder().accepted(service != null && service.isEnabled())
                .pendingBefore(pending).isolated(isolated).triggeredAt(System.currentTimeMillis()).build();
    }

    public CloudTestResponse test() {
        ReportProperties properties = reportPropertiesProvider.getIfAvailable();
        CloudOutboxService service = outboxProvider.getIfAvailable();
        boolean enabled = service != null && service.isEnabled();
        boolean configured = properties != null && properties.mqttEnabled();
        return CloudTestResponse.builder().enabled(enabled).configured(configured).connected(null)
                .message(enabled && configured ? "云链路配置可用，连接状态由运行时上报确认" : "云链路未启用或配置不完整")
                .checkedAt(System.currentTimeMillis()).build();
    }
}
