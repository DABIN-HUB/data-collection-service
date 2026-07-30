package com.wangbin.collector.common.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rejected-execution handler wrapper that exposes rejection counts for monitoring.
 */
public class ObservedRejectedExecutionHandler implements RejectedExecutionHandler {

    private final String executorName;
    private final RejectedExecutionHandler delegate;
    private final AtomicLong rejectedCount = new AtomicLong(0);

    public ObservedRejectedExecutionHandler(String executorName, RejectedExecutionHandler delegate) {
        this.executorName = executorName;
        this.delegate = delegate;
    }

    @Override
    public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
        rejectedCount.incrementAndGet();
        delegate.rejectedExecution(runnable, executor);
    }

    public String getExecutorName() {
        return executorName;
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }
}
