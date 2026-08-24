package com.wangbin.collector.regression;

import com.wangbin.collector.storage.buffer.HistoryWriteBufferTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        HistoryWriteBufferTest.class
})
class P0RegressionSuite {
}
