package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.loader.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 首次远端同步不能占用本地配置启动线程；不创建另一套调度器。 */
class WangbinConfigStartupSyncTest {
    @Test
    void initialSyncRunsOnlyOnExistingExecutorWorker() throws Exception {
        ConfigLoader loader = mock(ConfigLoader.class);
        List<Runnable> submitted = new ArrayList<>();
        ConfigSyncService service = new ConfigSyncService(loader, submitted::add, new CollectorProperties());
        service.startSyncTask();
        assertEquals(1, submitted.size());
        verifyNoInteractions(loader);
        Thread worker = new Thread(submitted.get(0), "wangbin-config-sync-check");
        worker.start();
        worker.join(5000);
        verify(loader).loadSnapshotResult();
    }

    @Test
    void callerRunsFallbackNeverReadsRemoteOnStartupThread() {
        ConfigLoader loader = mock(ConfigLoader.class);
        ConfigSyncService service = new ConfigSyncService(loader, Runnable::run, new CollectorProperties());
        service.startSyncTask();
        verifyNoInteractions(loader);
        service.scheduledSync();
        verify(loader).loadSnapshotResult();
    }

    @Test
    void rejectionLeavesRecoveryToExistingScheduledSync() {
        ConfigLoader loader = mock(ConfigLoader.class);
        Executor rejecting = command -> { throw new RejectedExecutionException(); };
        ConfigSyncService service = new ConfigSyncService(loader, rejecting, new CollectorProperties());
        service.startSyncTask();
        verifyNoInteractions(loader);
        service.scheduledSync();
        verify(loader).loadSnapshotResult();
    }
}
