package com.wangbin.collector.regression;

import com.wangbin.collector.core.collector.scheduler.AdaptiveCollectionUtilTest;
import com.wangbin.collector.core.collector.scheduler.CollectionSchedulerTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        CollectionSchedulerTest.class,
        AdaptiveCollectionUtilTest.class
})
class P0RegressionSuite {
}
