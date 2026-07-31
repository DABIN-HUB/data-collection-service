package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.storage.service.HistoryDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 定义当前模块的业务组件。
 */
@Component
@Order(30)
@RequiredArgsConstructor
@ConditionalOnBean(HistoryDataService.class)
class HistoryTelemetryPostProcessStage implements TelemetryPostProcessStage {

    private final HistoryDataService historyDataService;

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public TelemetryStageType type() {
        return TelemetryStageType.HISTORY;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public String name() {
        return "history";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public boolean enabled(TelemetryPostProcessContext context) {
        return historyDataService.isEnabled()
                && context.point() != null
                && context.point().isEnabled()
                && context.processResult() != null
                && Boolean.TRUE.equals(context.point().getAdditionalConfig("historyEnabled", Boolean.TRUE));
    }

    /**
     * 处理当前业务流程。
     */
    @Override
    public void process(TelemetryPostProcessContext context) {
        historyDataService.savePoint(context.deviceId(), context.point(), context.processResult());
    }
}
