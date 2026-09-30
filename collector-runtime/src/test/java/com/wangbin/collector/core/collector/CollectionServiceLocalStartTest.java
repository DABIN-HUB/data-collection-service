package com.wangbin.collector.core.collector;

import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionServiceLocalStartTest {

    private CollectionService collectionService;
    private CollectionScheduler collectionScheduler;
    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        collectionScheduler = mock(CollectionScheduler.class);
        configManager = mock(ConfigManager.class);
        collectionService = new CollectionService(
                collectionScheduler,
                mock(CollectionStatistics.class),
                configManager
        );
    }

    @Test
    void shouldStartLocalDeviceThroughGenericStartWithoutRemoteRefresh() {
        when(configManager.isLocalTemporaryDevice("local-1")).thenReturn(true);
        when(collectionScheduler.startDevice("local-1")).thenReturn(true);

        assertTrue(collectionService.startDevice("local-1"));

        verify(configManager, never()).refreshDeviceConfig(anyString());
        verify(collectionScheduler).startDevice("local-1");
    }

    @Test
    void shouldReturnFalseWhenGenericLocalStartSchedulerFails() {
        when(configManager.isLocalTemporaryDevice("local-1")).thenReturn(true);
        when(collectionScheduler.startDevice("local-1")).thenReturn(false);

        assertFalse(collectionService.startDevice("local-1"));

        verify(configManager, never()).refreshDeviceConfig(anyString());
        verify(collectionScheduler).startDevice("local-1");
    }

    @Test
    void shouldRefreshRemoteDeviceBeforeGenericStart() {
        when(configManager.isLocalTemporaryDevice("remote-1")).thenReturn(false);
        when(configManager.refreshDeviceConfig("remote-1")).thenReturn(true);
        when(collectionScheduler.startDevice("remote-1")).thenReturn(true);

        assertTrue(collectionService.startDevice("remote-1"));

        verify(configManager).refreshDeviceConfig("remote-1");
        verify(collectionScheduler).startDevice("remote-1");
    }

    @Test
    void shouldRejectGenericRemoteStartWhenRefreshFails() {
        when(configManager.isLocalTemporaryDevice("remote-1")).thenReturn(false);
        when(configManager.refreshDeviceConfig("remote-1")).thenReturn(false);

        assertFalse(collectionService.startDevice("remote-1"));

        verify(configManager).refreshDeviceConfig("remote-1");
        verify(collectionScheduler, never()).startDevice(anyString());
    }

    @Test
    void shouldStartLocalDeviceWithoutRemoteRefresh() {
        when(configManager.isLocalTemporaryDevice("local-1")).thenReturn(true);
        when(collectionScheduler.startDevice("local-1")).thenReturn(true);

        assertTrue(collectionService.startLocalDevice("local-1"));

        verify(configManager, never()).refreshDeviceConfig(anyString());
        verify(collectionScheduler).startDevice("local-1");
    }

    @Test
    void shouldRejectLocalStartForNonLocalDevice() {
        when(configManager.isLocalTemporaryDevice("remote-1")).thenReturn(false);

        assertFalse(collectionService.startLocalDevice("remote-1"));

        verify(collectionScheduler, never()).startDevice(anyString());
    }
}
