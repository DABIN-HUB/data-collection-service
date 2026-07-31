package com.wangbin.collector.storage.service;

import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.AlarmRepository;
import com.wangbin.collector.storage.repository.DataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class TdengineSchemaInitializer implements ApplicationRunner {

    private static final String ALARM_EVENT_TYPE_COLUMN = "alarm_event_type";
    private static final String TELEMETRY_UNIT_COLUMN = "unit";

    private final DataRepository dataRepository;
    private final AlarmRepository alarmRepository;
    private final TdengineProperties properties;

    private final AtomicBoolean databaseReady = new AtomicBoolean(false);
    private final AtomicBoolean telemetryStableReady = new AtomicBoolean(false);
    private final AtomicBoolean alarmStableReady = new AtomicBoolean(false);

    /**
     * 处理当前业务流程。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isAutoCreate()) {
            log.info("TDengine 自动-创建 disabled, 跳过 startup schema initialization.");
            return;
        }
        ensureTelemetrySuperTable();
        ensureAlarmSuperTable();
    }

    /**
     * 校验业务条件和参数边界。
     */
    public void ensureTelemetrySuperTable() {
        if (!properties.isAutoCreate()) {
            return;
        }
        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = sanitizeIdentifier(properties.getSuperTable());
        ensureDatabase(database);
        ensureStable(
                telemetryStableReady,
                database,
                superTable,
                () -> dataRepository.createStable(database, superTable),
                "telemetry"
        );
        ensureTelemetryUnitColumn(database, superTable);
    }

    /**
     * 校验业务条件和参数边界。
     */
    public void ensureAlarmSuperTable() {
        if (!properties.isAutoCreate()) {
            return;
        }
        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = sanitizeIdentifier(properties.getAlarmSuperTable());
        ensureDatabase(database);
        if (alarmStableReady.get()) {
            return;
        }
        synchronized (alarmStableReady) {
            if (alarmStableReady.get()) {
                return;
            }
            boolean existed = stableExists(database, superTable);
            if (existed) {
                log.info("TDengine 告警 超级表 已存在 exists:{}.{}", database, superTable);
            } else {
                alarmRepository.createStable(database, superTable);
                log.info("TDengine 告警 超级表 已初始化:{}.{}", database, superTable);
            }
            ensureAlarmEventTypeColumn(database, superTable);
            alarmStableReady.set(true);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureDatabase(String database) {
        if (databaseReady.get()) {
            return;
        }
        synchronized (databaseReady) {
            if (databaseReady.get()) {
                return;
            }
            dataRepository.createDatabase(database, properties.getKeepDays());
            databaseReady.set(true);
            log.info("TDengine 数据库 ready:{}", database);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureStable(AtomicBoolean readyFlag,
                              String database,
                              String superTable,
                              Runnable createAction,
                              String schemaName) {
        if (readyFlag.get()) {
            return;
        }
        synchronized (readyFlag) {
            if (readyFlag.get()) {
                return;
            }
            if (stableExists(database, superTable)) {
                log.info("TDengine {} 超级表 已存在 exists:{}.{}", schemaName, database, superTable);
                readyFlag.set(true);
                return;
            }
            createAction.run();
            readyFlag.set(true);
            log.info("TDengine {} 超级表 已初始化:{}.{}", schemaName, database, superTable);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureTelemetryUnitColumn(String database, String superTable) {
        if (columnExists(database, superTable, TELEMETRY_UNIT_COLUMN)) {
            return;
        }
        dataRepository.addTelemetryUnitColumn(database, superTable);
        log.info("TDengine 遥测 超级表 已升级 with 字段 {}:{}.{}",
                TELEMETRY_UNIT_COLUMN, database, superTable);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureAlarmEventTypeColumn(String database, String superTable) {
        if (columnExists(database, superTable, ALARM_EVENT_TYPE_COLUMN)) {
            return;
        }
        alarmRepository.addAlarmEventTypeColumn(database, superTable);
        log.info("TDengine 告警 超级表 已升级 with 字段 {}:{}.{}",
                ALARM_EVENT_TYPE_COLUMN, database, superTable);
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean stableExists(String database, String stableName) {
        Long count = dataRepository.countStable(database, stableName);
        return count != null && count > 0;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean columnExists(String database, String tableName, String columnName) {
        Long count = dataRepository.countColumn(database, tableName, columnName);
        return count != null && count > 0;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String sanitizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String value = raw.replaceAll("[^a-zA-Z0-9_]", "_");
        if (!Character.isLetter(value.charAt(0)) && value.charAt(0) != '_') {
            value = "_" + value;
        }
        return value.toLowerCase();
    }
}
