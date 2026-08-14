package com.wangbin.collector.monitor.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 报表调度器骨架，后续可拓展真实统计逻辑。
 */
@Slf4j
@Component
public class ReportScheduler {

    private final Map<String, ReportTask> tasks = new ConcurrentHashMap<>();

    /**
     * 维护注册或订阅关系。
     */
    public void registerTask(ReportTask task) {
        tasks.put(task.getId(), task);
    }

    /**
     * 处理当前业务流程。
     */
    @Scheduled(fixedDelay = 60_000)
    public void schedule() {
        long now = System.currentTimeMillis() / 1000;
        for (ReportTask task : tasks.values()) {
            if (task.getPeriodSeconds() <= 0) {
                continue;
            }
            if (now - task.getLastRun() >= task.getPeriodSeconds()) {
                log.debug("上报 任务 {} 等待中 execution", task.getName());
                // 待接入真实监控上报逻辑。
            }
        }
    }
}
