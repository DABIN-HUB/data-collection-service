package com.wangbin.collector.regression;

import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessorTest;
import com.wangbin.collector.core.cache.service.TelemetryStreamServiceImplTest;
import com.wangbin.collector.core.collector.scheduler.CollectionSchedulerTest;
import com.wangbin.collector.core.report.service.CacheReportServiceTest;
import com.wangbin.collector.core.report.shadow.ShadowManagerTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        CollectorDataPostProcessorTest.class,
        CollectionSchedulerTest.class,
        CacheReportServiceTest.class,
        TelemetryStreamServiceImplTest.class,
        ShadowManagerTest.class
})
class P0RegressionSuite {
}
