package com.wangbin.collector.regression;

import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessorTest;
import com.wangbin.collector.core.report.outbox.CloudOutboxServiceTest;
import com.wangbin.collector.core.report.service.CacheReportServiceTest;
import com.wangbin.collector.core.report.shadow.ShadowManagerTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        CollectorDataPostProcessorTest.class,
        CacheReportServiceTest.class,
        CloudOutboxServiceTest.class,
        ShadowManagerTest.class
})
class P0RegressionSuite {
}
