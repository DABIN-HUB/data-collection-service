package com.wangbin.collector;

import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.connection.manager.ConnectionManager;
import com.wangbin.collector.core.report.service.CacheReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协调采集服务的优雅停机顺序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdown implements TomcatConnectorCustomizer,
        ApplicationListener<ContextClosedEvent> {

    private static final long WAIT_TIMEOUT_SECONDS = 30L;
    private static final long POLL_INTERVAL_MILLIS = 100L;

    private final CollectionScheduler collectionScheduler;
    private final CacheReportService cacheReportService;
    private final ConnectionManager connectionManager;

    private volatile Connector connector;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void customize(Connector connector) {
        this.connector = connector;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        Connector currentConnector = connector;
        if (currentConnector != null) {
            currentConnector.pause();
        }

        log.info("开始优雅停机，停止接收新请求和新采集任务");
        collectionScheduler.stopAllDevices();
        cacheReportService.shutdown();
        waitForActiveRequests(currentConnector);
        connectionManager.closeAllConnections();
        log.info("采集服务优雅停机流程完成");
    }

    /**
     * 执行当前业务逻辑。
     */
    private void waitForActiveRequests(Connector currentConnector) {
        if (currentConnector == null) {
            return;
        }
        Executor executor = currentConnector.getProtocolHandler().getExecutor();
        if (!(executor instanceof ThreadPoolExecutor threadPoolExecutor)) {
            return;
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS);
        while (threadPoolExecutor.getActiveCount() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待活动请求完成时被中断");
                return;
            }
        }
        if (threadPoolExecutor.getActiveCount() > 0) {
            log.warn("等待活动请求超时，剩余活动请求数: {}", threadPoolExecutor.getActiveCount());
        }
    }
}
