package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.report.service.CacheReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 定义当前模块的业务组件。
 */
@Component
@Order(40)
@RequiredArgsConstructor
class ReportTelemetryPostProcessStage implements TelemetryPostProcessStage {

    private final CacheReportService cacheReportService;

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public TelemetryStageType type() {
        return TelemetryStageType.REPORT;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public String name() {
        return "report";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public boolean enabled(TelemetryPostProcessContext context) {
        return context.processResult() != null
                && context.point() != null
                && (context.point().isReportEnabled() || context.point().isEventReportingEnabled());
    }

    /**
     * 处理当前业务流程。
     */
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
