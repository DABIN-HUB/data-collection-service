package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.storage.service.HistoryDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
@RequiredArgsConstructor
@ConditionalOnBean(HistoryDataService.class)
class HistoryTelemetryPostProcessStage implements TelemetryPostProcessStage {

    private final HistoryDataService historyDataService;

    @Override
    public String name() {
        return "history";
    }

    @Override
    public boolean enabled(TelemetryPostProcessContext context) {
        return historyDataService.isEnabled()
                && context.point() != null
                && context.point().isEnabled()
                && context.processResult() != null
                && Boolean.TRUE.equals(context.point().getAdditionalConfig("historyEnabled", Boolean.TRUE));
    }

    @Override
    public void process(TelemetryPostProcessContext context) {
        historyDataService.savePoint(context.deviceId(), context.point(), context.processResult());
    }
}
