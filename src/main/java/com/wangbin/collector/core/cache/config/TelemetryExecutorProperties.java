package com.wangbin.collector.core.cache.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 遥测后处理阶段线程池配置。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "collector.telemetry-executors")
public class TelemetryExecutorProperties {

    @Valid
    private Stage cache = new Stage(2, 4, 2000);

    @Valid
    private Stage stream = new Stage(2, 4, 2000);

    @Valid
    private Stage history = new Stage(2, 4, 5000);

    @Valid
    private Stage report = new Stage(2, 4, 5000);

    /**
     * 单个遥测阶段线程池参数。
     */
    @Data
    public static class Stage {

        @Min(1)
        private int coreSize;

        @Min(1)
        private int maxSize;

        @Min(1)
        private int queueCapacity;

        /**
         * 创建阶段线程池参数。
         */
        public Stage() {
            this(2, 4, 2000);
        }

        /**
         * 创建阶段线程池参数。
         */
        public Stage(int coreSize, int maxSize, int queueCapacity) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
        }
    }
}
