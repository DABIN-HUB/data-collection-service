package com.wangbin.collector.core.cache.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes post-processing stages with per-stage failure isolation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryPostProcessPipeline {

    private final List<TelemetryPostProcessStage> stageCandidates;

    public void process(TelemetryPostProcessContext context) {
        if (context == null || context.deviceId() == null || context.point() == null || context.processResult() == null) {
            return;
        }

        List<TelemetryPostProcessStage> orderedStages = new ArrayList<>(stageCandidates);
        AnnotationAwareOrderComparator.sort(orderedStages);
        for (TelemetryPostProcessStage stage : orderedStages) {
            if (stage == null || !stage.enabled(context)) {
                continue;
            }
            try {
                stage.process(context);
            } catch (Exception e) {
                log.error("telemetry post-process stage failed, stage={}, device={}, point={}",
                        stage.name(), context.deviceId(), context.point().getPointId(), e);
            }
        }
    }
}
