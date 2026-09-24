package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.SystemCapabilitiesResponse;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import com.wangbin.collector.monitor.metrics.CloudReportMonitorService;
import com.wangbin.collector.storage.service.HistoryDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemCapabilitiesApplicationServiceTest {
    @Test
    void shouldReportOnlyActuallyAvailableCapabilities() {
        HistoryDataService history = mock(HistoryDataService.class);
        when(history.isEnabled()).thenReturn(true);
        SystemCapabilitiesApplicationService service = newService(history, mock(CollectionManager.class), mock(ShadowManager.class));

        SystemCapabilitiesResponse response = service.getCapabilities();

        assertTrue(response.getHistory().isAvailable());
        assertTrue(response.getShadow().isAvailable());
        assertTrue(response.getControl().isAvailable());
        assertFalse(response.getRealtime().isWebsocketAvailable());
        assertFalse(response.getRealtime().isSseAvailable());
        assertTrue(response.getRealtime().isPollingAvailable());
        assertTrue("HTTP_POLLING".equals(response.getRealtime().getBrowserTransport()));
        assertFalse(response.getCloud().isManagementAvailable());
    }

    @Test
    void shouldFailClosedWhenOptionalBeansAreAbsentOrDisabled() {
        HistoryDataService history = mock(HistoryDataService.class);
        when(history.isEnabled()).thenReturn(false);
        SystemCapabilitiesApplicationService service = newService(history, null, null);

        SystemCapabilitiesResponse response = service.getCapabilities();

        assertFalse(response.getHistory().isAvailable());
        assertFalse(response.getControl().isAvailable());
        assertFalse(response.getShadow().isAvailable());
        assertFalse(response.getCloud().isMonitoringAvailable());
        assertTrue(response.getHistory().getReason() != null && !response.getHistory().getReason().isBlank());
    }

    private SystemCapabilitiesApplicationService newService(HistoryDataService history,
                                                            CollectionManager collectionManager,
                                                            ShadowManager shadowManager) {
        ObjectProvider<HistoryDataService> historyProvider = provider(history);
        ObjectProvider<CollectionManager> collectionProvider = provider(collectionManager);
        ObjectProvider<ShadowManager> shadowProvider = provider(shadowManager);
        ObjectProvider<CloudReportMonitorService> monitorProvider = provider(null);
        ObjectProvider<CloudOutboxService> outboxProvider = provider(null);
        ObjectProvider<CloudOperationsApplicationService> operationsProvider = provider(null);
        return new SystemCapabilitiesApplicationService(historyProvider, collectionProvider, shadowProvider,
                monitorProvider, outboxProvider, operationsProvider);
    }

    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
