package com.wangbin.collector.regression;

import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessorTest;
import com.wangbin.collector.core.cache.service.TelemetryStreamServiceImplTest;
import com.wangbin.collector.core.collector.scheduler.CollectionSchedulerTest;
import com.wangbin.collector.core.collector.scheduler.AdaptiveCollectionUtilTest;
import com.wangbin.collector.core.collector.protocol.modbus.ModbusTcpCollectionChainAcceptanceTest;
import com.wangbin.collector.core.alarm.AlarmStateTrackerTest;
import com.wangbin.collector.core.alarm.RedisAlarmStateRepositoryTest;
import com.wangbin.collector.core.report.service.CacheReportServiceTest;
import com.wangbin.collector.core.report.outbox.CloudOutboxServiceTest;
import com.wangbin.collector.core.report.shadow.ShadowManagerTest;
import com.wangbin.collector.storage.buffer.HistoryWriteBufferTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        CollectorDataPostProcessorTest.class,
        ModbusTcpCollectionChainAcceptanceTest.class,
        CollectionSchedulerTest.class,
        AdaptiveCollectionUtilTest.class,
        AlarmStateTrackerTest.class,
        RedisAlarmStateRepositoryTest.class,
        CacheReportServiceTest.class,
        CloudOutboxServiceTest.class,
        TelemetryStreamServiceImplTest.class,
        ShadowManagerTest.class,
        HistoryWriteBufferTest.class
})
class P0RegressionSuite {
}
