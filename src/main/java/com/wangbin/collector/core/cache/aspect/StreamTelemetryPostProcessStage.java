package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

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

    /**
     * Stream stage executor 拒绝时直接进入同一个有界写缓冲，成功 admission 视为已补偿。
     */
    @Override
    public boolean onRejected(TelemetryPostProcessContext context, RejectedExecutionException exception) {
        return telemetryStreamService.appendBestEffort(
                context.deviceId(), context.point(), context.processResult());
    }
}
