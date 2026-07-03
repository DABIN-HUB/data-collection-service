package com.wangbin.collector.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "telemetry.tdengine")
public class TdengineProperties {

    /**
     * Enable TDengine persistence.
     */
    private boolean enabled = false;

    /**
     * TDengine database.
     */
    private String database = "wangbin_collector";

    /**
     * Super table name.
     */
    private String superTable = "telemetry_super";

    /**
     * Child table prefix.
     */
    private String subTablePrefix = "d_";

    /**
     * Alarm super table name.
     */
    private String alarmSuperTable = "alarm_super";

    /**
     * Alarm child table prefix.
     */
    private String alarmSubTablePrefix = "d_alarm_";

    /**
     * Keep days for DB retention.
     */
    private int keepDays = 30;

    /**
     * Auto create database/stable/sub-tables.
     */
    private boolean autoCreate = true;

    /**
     * Query default limit.
     */
    private int queryDefaultLimit = 500;

    /**
     * Query max limit guard.
     */
    private int queryMaxLimit = 5000;
}
