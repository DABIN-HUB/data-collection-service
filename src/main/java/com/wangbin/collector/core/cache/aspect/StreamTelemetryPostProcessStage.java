package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
@RequiredArgsConstructor
class StreamTelemetryPostProcessStage implements TelemetryPostProcessStage {

    private final TelemetryStreamService telemetryStreamService;
    private final TelemetryStreamProperties streamProperties;

    @Override
    public TelemetryStageType type() {
        return TelemetryStageType.STREAM;
    }

    @Override
    public String name() {
        return "stream";
    }

    @Override
    public boolean enabled(TelemetryPostProcessContext context) {
        return streamProperties.isEnabled()
                && context.point() != null
                && context.point().isEnabled()
                && context.processResult() != null
                && Boolean.TRUE.equals(context.point().getAdditionalConfig("streamEnabled", Boolean.TRUE));
    }

    @Override
    public void process(TelemetryPostProcessContext context) {
        telemetryStreamService.append(context.deviceId(), context.point(), context.processResult());
    }
}
