package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 定义当前模块的业务组件。
 */
@Component
@Order(20)
@RequiredArgsConstructor
class StreamTelemetryPostProcessStage implements TelemetryPostProcessStage {

    private final TelemetryStreamService telemetryStreamService;
    private final TelemetryStreamProperties streamProperties;

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public TelemetryStageType type() {
        return TelemetryStageType.STREAM;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public String name() {
        return "stream";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public boolean enabled(TelemetryPostProcessContext context) {
        return streamProperties.isEnabled()
                && context.point() != null
                && context.point().isEnabled()
                && context.processResult() != null
                && Boolean.TRUE.equals(context.point().getAdditionalConfig("streamEnabled", Boolean.TRUE));
    }

    /**
     * 处理当前业务流程。
     */
    @Override
    public void process(TelemetryPostProcessContext context) {
        telemetryStreamService.append(context.deviceId(), context.point(), context.processResult());
    }
}
