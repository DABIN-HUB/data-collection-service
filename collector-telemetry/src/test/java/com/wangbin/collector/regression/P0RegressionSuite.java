package com.wangbin.collector.regression;

import com.wangbin.collector.core.alarm.AlarmStateTrackerTest;
import com.wangbin.collector.core.alarm.RedisAlarmStateRepositoryTest;
import com.wangbin.collector.core.cache.service.TelemetryStreamServiceImplTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        AlarmStateTrackerTest.class,
        RedisAlarmStateRepositoryTest.class,
        TelemetryStreamServiceImplTest.class
})
class P0RegressionSuite {
}
