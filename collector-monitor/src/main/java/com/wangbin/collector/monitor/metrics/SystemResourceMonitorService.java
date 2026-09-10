package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import com.wangbin.collector.core.port.SystemResourceProbe;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.outbox.CloudOutboxSnapshot;
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
import java.util.concurrent.BlockingQueue;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * JVM 与系统资源监控服务。
 */
@Service
@RequiredArgsConstructor
public class SystemResourceMonitorService implements SystemResourceProbe {

    private final BeanFactory beanFactory;
    private final CloudOutboxService cloudOutboxService;

    private MemoryMXBean memoryMXBean;
    private ThreadMXBean threadMXBean;
    private OperatingSystemMXBean operatingSystemMXBean;
    private com.sun.management.OperatingSystemMXBean extendedOperatingSystemMXBean;

    /**
     * 处理组件生命周期。
     */
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
        CloudOutboxSnapshot cloudOutboxSnapshot = cloudOutboxService.snapshot();
        return SystemResourceSnapshot.builder()
                .heapUsed(memoryMXBean != null ? memoryMXBean.getHeapMemoryUsage().getUsed() : -1L)
                .heapCommitted(memoryMXBean != null ? memoryMXBean.getHeapMemoryUsage().getCommitted() : -1L)
                .heapMax(memoryMXBean != null ? memoryMXBean.getHeapMemoryUsage().getMax() : -1L)
                .nonHeapUsed(memoryMXBean != null ? memoryMXBean.getNonHeapMemoryUsage().getUsed() : -1L)
                .nonHeapCommitted(memoryMXBean != null ? memoryMXBean.getNonHeapMemoryUsage().getCommitted() : -1L)
                .totalPhysicalMemorySize(readMemorySize(com.sun.management.OperatingSystemMXBean::getTotalMemorySize))
                .freePhysicalMemorySize(readMemorySize(com.sun.management.OperatingSystemMXBean::getFreeMemorySize))
                .processCpuLoad(readCpuLoad(com.sun.management.OperatingSystemMXBean::getProcessCpuLoad))
                .systemCpuLoad(readCpuLoad(com.sun.management.OperatingSystemMXBean::getSystemCpuLoad))
                .threadCount(threadMXBean != null ? threadMXBean.getThreadCount() : -1)
                .daemonThreadCount(threadMXBean != null ? threadMXBean.getDaemonThreadCount() : -1)
                .outboxPendingCount(cloudOutboxSnapshot.pending())
                .outboxIsolatedCount(cloudOutboxSnapshot.isolated())
                .outboxOldestMessageAgeMillis(cloudOutboxSnapshot.oldestMessageAgeMillis())
                .threadPools(threadPools)
                .build();
    }

    /**
     * 查询线程池资源快照，不读取 CPU、内存或云端发件箱等其它监控源。
     */
    public Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> getThreadPools() {
        return collectThreadPoolStats();
    }

    @Override
    public double getProcessCpuLoad() {
        return readCpuLoad(com.sun.management.OperatingSystemMXBean::getProcessCpuLoad);
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> collectThreadPoolStats() {
        Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> result = new LinkedHashMap<>();
        for (String beanName : MonitorThreadPoolNames.ALL) {
            result.put(beanName, inspectThreadPool(beanName));
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
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
                    threadPoolExecutor.getQueue(),
                    threadPoolExecutor.getCompletedTaskCount(),
                    rejectedCount(threadPoolExecutor)
            );
        }

        if (bean instanceof ThreadPoolExecutor executor) {
            return buildSnapshot(
                    executor.getCorePoolSize(),
                    executor.getMaximumPoolSize(),
                    executor.getActiveCount(),
                    executor.getQueue(),
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
                    executor.getQueue(),
                    executor.getCompletedTaskCount(),
                    rejectedCount(executor)
            );
        }

        return emptyThreadPoolSnapshot();
    }

    /**
     * 创建并返回业务对象。
     */
    private SystemResourceSnapshot.ThreadPoolSnapshot buildSnapshot(int core,
                                                                    int max,
                                                                    int active,
                                                                    BlockingQueue<?> queue,
                                                                    long completed,
                                                                    long rejected) {
        int queueSize = queue == null ? -1 : queue.size();
        int queueCapacity = queueCapacity(queue, queueSize);
        return SystemResourceSnapshot.ThreadPoolSnapshot.builder()
                .corePoolSize(core)
                .maxPoolSize(max)
                .activeCount(active)
                .queueSize(queueSize)
                .queueCapacity(queueCapacity)
                .queueUtilization(queueUtilization(queueSize, queueCapacity))
                .completedTaskCount(completed)
                .rejectedCount(rejected)
                .build();
    }

    private int queueCapacity(BlockingQueue<?> queue, int queueSize) {
        if (queue == null || queueSize < 0) {
            return -1;
        }
        int remainingCapacity = queue.remainingCapacity();
        if (remainingCapacity < 0 || remainingCapacity == Integer.MAX_VALUE) {
            return -1;
        }
        long capacity = (long) queueSize + remainingCapacity;
        return capacity > Integer.MAX_VALUE ? -1 : (int) capacity;
    }

    private double queueUtilization(int queueSize, int queueCapacity) {
        if (queueSize < 0 || queueCapacity < 0) {
            return -1D;
        }
        if (queueCapacity == 0) {
            return 0D;
        }
        return Math.min(1D, Math.max(0D, (double) queueSize / queueCapacity));
    }

    /**
     * 执行当前业务逻辑。
     */
    private SystemResourceSnapshot.ThreadPoolSnapshot emptyThreadPoolSnapshot() {
        return SystemResourceSnapshot.ThreadPoolSnapshot.builder()
                .corePoolSize(-1)
                .maxPoolSize(-1)
                .activeCount(-1)
                .queueSize(-1)
                .queueCapacity(-1)
                .queueUtilization(-1D)
                .completedTaskCount(-1)
                .rejectedCount(-1)
                .build();
    }

    /**
     * 执行当前业务逻辑。
     */
    private long rejectedCount(ThreadPoolExecutor executor) {
        if (executor == null) {
            return -1L;
        }
        if (executor.getRejectedExecutionHandler() instanceof ObservedRejectedExecutionHandler observed) {
            return observed.getRejectedCount();
        }
        return -1L;
    }

    /**
     * 查询并返回业务数据。
     */
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

    /**
     * 查询并返回业务数据。
     */
    private long readMemorySize(ToLongFunction<com.sun.management.OperatingSystemMXBean> reader) {
        if (extendedOperatingSystemMXBean == null) {
            return -1L;
        }
        try {
            long value = reader.applyAsLong(extendedOperatingSystemMXBean);
            return value >= 0L ? value : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }
}
