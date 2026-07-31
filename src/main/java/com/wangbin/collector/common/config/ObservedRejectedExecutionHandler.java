package com.wangbin.collector.common.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带拒绝计数指标的任务拒绝处理器包装器。
 */
public class ObservedRejectedExecutionHandler implements RejectedExecutionHandler {

    private final String executorName;
    private final RejectedExecutionHandler delegate;
    private final AtomicLong rejectedCount = new AtomicLong(0);

    /**
     * 创建当前组件实例。
     */
    public ObservedRejectedExecutionHandler(String executorName, RejectedExecutionHandler delegate) {
        this.executorName = executorName;
        this.delegate = delegate;
    }

    /**
     * 执行当前业务逻辑。
     */
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
