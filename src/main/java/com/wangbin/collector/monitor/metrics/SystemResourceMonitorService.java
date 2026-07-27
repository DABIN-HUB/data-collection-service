package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * JVM 与系统资源监控服务。
 */
@Service
@RequiredArgsConstructor
public class SystemResourceMonitorService {

    private final BeanFactory beanFactory;
    private final CloudOutboxService cloudOutboxService;

    private MemoryMXBean memoryMXBean;
    private ThreadMXBean threadMXBean;
    private OperatingSystemMXBean operatingSystemMXBean;
    private com.sun.management.OperatingSystemMXBean extendedOperatingSystemMXBean;

    @PostConstruct
    public void init() {
        memoryMXBean = ManagementFactory.getMemoryMXBean();
        threadMXBean = ManagementFactory.getThreadMXBean();
        operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        if (operatingSystemMXBean instanceof com.sun.management.OperatingSystemMXBean extended) {
            extendedOperatingSystemMXBean = extended;
        } else {
            try {
                extendedOperatingSystemMXBean = ManagementFactory.getPlatformMXBean(
                        com.sun.management.OperatingSystemMXBean.class);
            } catch (Exception ignored) {
                extendedOperatingSystemMXBean = null;
            }
        }
    }

    public SystemResourceSnapshot getResources() {
        Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> threadPools = collectThreadPoolStats();
        return SystemResourceSnapshot.builder()
                .heapUsed(memoryMXBean != null ? memoryMXBean.getHeapMemoryUsage().getUsed() : -1L)
                .heapCommitted(memoryMXBean != null ? memoryMXBean.getHeapMemoryUsage().getCommitted() : -1L)
                .heapMax(memoryMXBean != null ? memoryMXBean.getHeapMemoryUsage().getMax() : -1L)
                .nonHeapUsed(memoryMXBean != null ? memoryMXBean.getNonHeapMemoryUsage().getUsed() : -1L)
                .nonHeapCommitted(memoryMXBean != null ? memoryMXBean.getNonHeapMemoryUsage().getCommitted() : -1L)
                .processCpuLoad(readCpuLoad(com.sun.management.OperatingSystemMXBean::getProcessCpuLoad))
                .systemCpuLoad(readCpuLoad(com.sun.management.OperatingSystemMXBean::getSystemCpuLoad))
                .threadCount(threadMXBean != null ? threadMXBean.getThreadCount() : -1)
                .daemonThreadCount(threadMXBean != null ? threadMXBean.getDaemonThreadCount() : -1)
                .outboxPendingCount(cloudOutboxService.getPendingCount())
                .outboxIsolatedCount(cloudOutboxService.getIsolatedCount())
                .outboxOldestMessageAgeMillis(cloudOutboxService.getOldestMessageAgeMillis())
                .threadPools(threadPools)
                .build();
    }

    private Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> collectThreadPoolStats() {
        Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> result = new LinkedHashMap<>();
        for (String beanName : MonitorThreadPoolNames.ALL) {
            result.put(beanName, inspectThreadPool(beanName));
        }
        return result;
    }

    private SystemResourceSnapshot.ThreadPoolSnapshot inspectThreadPool(String beanName) {
        if (!beanFactory.containsBean(beanName)) {
            return emptyThreadPoolSnapshot();
        }

        Object bean = beanFactory.getBean(beanName);
        if (bean instanceof ThreadPoolTaskExecutor executor) {
            ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();
            if (threadPoolExecutor == null) {
                return emptyThreadPoolSnapshot();
            }
            return buildSnapshot(
                    executor.getCorePoolSize(),
                    executor.getMaxPoolSize(),
                    executor.getActiveCount(),
                    threadPoolExecutor.getQueue().size(),
                    threadPoolExecutor.getCompletedTaskCount(),
                    rejectedCount(threadPoolExecutor)
            );
        }

        if (bean instanceof ThreadPoolExecutor executor) {
            return buildSnapshot(
                    executor.getCorePoolSize(),
                    executor.getMaximumPoolSize(),
                    executor.getActiveCount(),
                    executor.getQueue().size(),
                    executor.getCompletedTaskCount(),
                    rejectedCount(executor)
            );
        }

        if (bean instanceof ThreadPoolTaskScheduler scheduler) {
            ScheduledThreadPoolExecutor executor = scheduler.getScheduledThreadPoolExecutor();
            if (executor == null) {
                return emptyThreadPoolSnapshot();
            }
            return buildSnapshot(
                    executor.getCorePoolSize(),
                    executor.getMaximumPoolSize(),
                    executor.getActiveCount(),
                    executor.getQueue().size(),
                    executor.getCompletedTaskCount(),
                    rejectedCount(executor)
            );
        }

        return emptyThreadPoolSnapshot();
    }

    private SystemResourceSnapshot.ThreadPoolSnapshot buildSnapshot(int core,
                                                                    int max,
                                                                    int active,
                                                                    int queue,
                                                                    long completed,
                                                                    long rejected) {
        return SystemResourceSnapshot.ThreadPoolSnapshot.builder()
                .corePoolSize(core)
                .maxPoolSize(max)
                .activeCount(active)
                .queueSize(queue)
                .completedTaskCount(completed)
                .rejectedCount(rejected)
                .build();
    }

    private SystemResourceSnapshot.ThreadPoolSnapshot emptyThreadPoolSnapshot() {
        return SystemResourceSnapshot.ThreadPoolSnapshot.builder()
                .corePoolSize(-1)
                .maxPoolSize(-1)
                .activeCount(-1)
                .queueSize(-1)
                .completedTaskCount(-1)
                .rejectedCount(-1)
                .build();
    }

    private long rejectedCount(ThreadPoolExecutor executor) {
        if (executor == null) {
            return -1L;
        }
        if (executor.getRejectedExecutionHandler() instanceof ObservedRejectedExecutionHandler observed) {
            return observed.getRejectedCount();
        }
        return -1L;
    }

    private double readCpuLoad(ToDoubleFunction<com.sun.management.OperatingSystemMXBean> reader) {
        if (extendedOperatingSystemMXBean == null) {
            return -1.0;
        }
        try {
            double load = reader.applyAsDouble(extendedOperatingSystemMXBean);
            return load >= 0.0 ? load * 100.0 : -1.0;
        } catch (Exception ignored) {
            return -1.0;
        }
    }
}
