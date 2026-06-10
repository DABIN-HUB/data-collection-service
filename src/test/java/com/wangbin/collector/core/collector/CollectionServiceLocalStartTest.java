package com.wangbin.collector.core.collector;

import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
        collectionService = new CollectionService();
        collectionScheduler = mock(CollectionScheduler.class);
        configManager = mock(ConfigManager.class);
        ReflectionTestUtils.setField(collectionService, "collectionScheduler", collectionScheduler);
        ReflectionTestUtils.setField(collectionService, "collectionStatistics", mock(CollectionStatistics.class));
        ReflectionTestUtils.setField(collectionService, "configManager", configManager);
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
