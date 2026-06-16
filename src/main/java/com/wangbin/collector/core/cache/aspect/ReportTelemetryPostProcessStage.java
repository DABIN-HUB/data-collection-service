package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.report.service.CacheReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
@RequiredArgsConstructor
class ReportTelemetryPostProcessStage implements TelemetryPostProcessStage {

    private final CacheReportService cacheReportService;

    @Override
    public String name() {
        return "report";
    }

    @Override
    public boolean enabled(TelemetryPostProcessContext context) {
        return context.processResult() != null
                && context.point() != null
                && (context.point().isReportEnabled() || context.point().isEventReportingEnabled());
    }

    @Override
    public void process(TelemetryPostProcessContext context) {
        cacheReportService.reportPoint(
                context.deviceId(),
                MessageConstant.MESSAGE_TYPE_PROPERTY_POST,
                context.point(),
                context.processResult()
        );
    }
}
