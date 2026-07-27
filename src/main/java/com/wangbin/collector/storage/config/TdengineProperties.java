package com.wangbin.collector.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
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
}
