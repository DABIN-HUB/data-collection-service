package com.wangbin.collector.storage.config;

import com.wangbin.collector.storage.service.TdengineWriteMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 承载当前模块的配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "telemetry.tdengine")
public class TdengineProperties {

    /**
     * 是否启用 TDengine 持久化。
     */
    private boolean enabled = false;

    /**
     * TDengine 数据库名称。
     */
    private String database = "wangbin_collector";

    /**
     * 遥测超级表名称。
     */
    private String superTable = "telemetry_super";

    /**
     * 遥测子表前缀。
     */
    private String subTablePrefix = "d_";

    /**
     * 告警超级表名称。
     */
    private String alarmSuperTable = "alarm_super";

    /**
     * 告警子表前缀。
     */
    private String alarmSubTablePrefix = "d_alarm_";

    /**
     * 数据保留天数。
     */
    private int keepDays = 30;

    /**
     * 是否自动创建数据库、超级表和子表。
     */
    private boolean autoCreate = true;

    /**
     * 查询默认返回数量。
     */
    private int queryDefaultLimit = 500;

    /**
     * 查询最大返回数量。
     */
    private int queryMaxLimit = 5000;

    /**
     * TDengine 写入路径配置，默认保持历史单子表 INSERT 语义。
     */
    @Valid
    private Write write = new Write();

    /**
     * TDengine 历史写入路径的可回滚开关和有界聚合参数。
     */
    @Data
    public static class Write {

        /**
         * TDengine 历史写入实现模式，默认配置为直接 JDBC 写入，仍可回滚到既有 MyBatis 路径。
         */
        private TdengineWriteMode mode = TdengineWriteMode.DIRECT_REST;

        /**
         * 是否启用跨子表 multi-table INSERT。
         */
        private boolean multiTableEnabled = false;

        /**
         * 单次 multi-table request 最多包含的子表数量。
         */
        @Min(1)
        @Max(100)
        private int maxTablesPerRequest = 5;

        /**
         * 单次 multi-table request 最多包含的行数。
         */
        @Min(1)
        @Max(5000)
        private int maxRowsPerRequest = 250;

        /**
         * 为聚合同一轮已脱离 bucket 的 device batch 最多等待的毫秒数。
         */
        @Min(0)
        @Max(100)
        private long aggregationWaitMs = 5L;

        /**
         * WebSocket STMT 专用 JDBC URL；未配置时使用本地 6041 和当前数据库名。
         */
        private String websocketUrl;

        /**
         * WebSocket STMT 专用用户名；为空时由 driver 使用默认值或 URL 参数。
         */
        private String websocketUsername = "root";

        /**
         * WebSocket STMT 专用密码；为空时由 driver 使用默认值或 URL 参数。
         */
        private String websocketPassword = "taosdata";
    }
}
