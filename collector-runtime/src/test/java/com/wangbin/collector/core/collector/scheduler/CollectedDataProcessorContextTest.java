package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.config.CollectorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CollectedDataProcessorContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(CollectorProperties.class)
            .withBean(PointRuntimeStateService.class)
            .withBean(PerformanceMonitor.class)
            .withBean(CollectedDataProcessor.class);

    @Test
    void shouldSelectProductionConstructorInSpringContext() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CollectedDataProcessor.class);
        });
    }
}
