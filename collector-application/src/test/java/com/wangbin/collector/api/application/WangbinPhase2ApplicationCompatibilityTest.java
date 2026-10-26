package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.*;
import com.wangbin.collector.common.domain.entity.*;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.scheduler.DeviceLifecycleCoordinator;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.config.manager.*;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WangbinPhase2ApplicationCompatibilityTest {
    private final ConfigManager manager = mock(ConfigManager.class);
    private final CollectionService collection = mock(CollectionService.class);
    private final DeviceLifecycleCoordinator lifecycle = mock(DeviceLifecycleCoordinator.class);
    private final SensitiveConfigSanitizer sanitizer = new SensitiveConfigSanitizer();
    private final LocalDeviceConfigApplicationService local = new LocalDeviceConfigApplicationService(manager, collection, lifecycle);
    private final ConfigImportExportApplicationService exchange = new ConfigImportExportApplicationService(manager, collection, sanitizer);
    private final ConfigConsoleApplicationService console = new ConfigConsoleApplicationService(manager, mock(ConfigSyncService.class),
            sanitizer, mock(PointRuntimeStateService.class), local, exchange, new ConfigDiffCalculator());

    private DeviceInfo device(String id) {
        DeviceInfo d = new DeviceInfo(); d.setDeviceId(id); d.setConfigSource("local"); d.setTemporaryConfig(true); return d;
    }
    private DeviceConnection connection(String id) {
        DeviceConnection c = new DeviceConnection(); c.setDeviceId(id); c.setPassword("test-only-secret"); return c;
    }

    @Test void localGetThenPutRestoresMaskedPassword() {
        DeviceInfo d = device("masked"); DeviceConnection c = connection("masked");
        when(manager.getDeviceConfigurationSnapshot("masked")).thenReturn(new ConfigManager.DeviceConfigurationSnapshot(DeviceContext.of(d,c,List.of()),3));
        when(manager.getConnectionConfig("masked")).thenReturn(c);
        when(manager.saveLocalDeviceConfigWithResult(any(),any(),anyList(),eq(true),eq(3L)))
                .thenReturn(new ConfigManager.DeviceConfigCommitResult("masked",3,4,0));
        var read = local.getLocalDevice("masked").getData();
        ConfigBundle b = read.getBundle();
        assertEquals(SensitiveConfigSanitizer.MASKED_VALUE,b.getConnection().getPassword());
        var saved = local.updateLocalDevice("masked",LocalDeviceConfigRequest.builder().device(b.getDevice())
                .connection(b.getConnection()).points(List.of()).baseVersion(read.getConfigVersion()).build()).getData();
        ArgumentCaptor<DeviceConnection> captured = ArgumentCaptor.forClass(DeviceConnection.class);
        verify(manager).saveLocalDeviceConfigWithResult(any(),captured.capture(),anyList(),eq(true),eq(3L));
        // 掩码交给 Manager 在版本校验后的写锁内恢复，不允许应用层锁外提前取出秘密。
        assertEquals(SensitiveConfigSanitizer.MASKED_VALUE,captured.getValue().getPassword());
        assertEquals("test-only-secret",c.getPassword());
        assertTrue(saved.getSaved());
        assertEquals(4,saved.getConfigVersion());
        assertFalse(saved.getStartRequested());
    }

    @Test void emptyPointListDeletesLastPointButNullIsRejected() {
        when(manager.updateDataPoints("empty",List.of())).thenReturn(true);
        assertEquals(0,console.updatePoints("empty",List.of()).getData().getCount());
        assertThrows(com.wangbin.collector.api.exception.ConfigApiException.class,()->console.updatePoints("empty",null));
    }

    @Test void importEmptyPointsClearsInsteadOfFallingBackAndDoesNotMutateCachedConnection() {
        DeviceInfo d = device("import"); DeviceConnection c = connection("import");
        DataPoint p = new DataPoint(); p.setPointId("p"); p.setDeviceId("import");
        when(manager.getDevice("import")).thenReturn(d); when(manager.getConnectionConfig("import")).thenReturn(c);
        when(manager.getDataPoints("import")).thenReturn(List.of(p)); when(manager.replaceDeviceContextsAtomically(anyList())).thenReturn(true);
        ConfigImportRequest request = new ConfigImportRequest();
        request.setBundles(List.of(ConfigBundle.builder().device(d).points(List.of()).build()));
        exchange.importConfigs(request);
        ArgumentCaptor<List<DeviceContext>> captured = ArgumentCaptor.forClass(List.class);
        verify(manager).replaceDeviceContextsAtomically(captured.capture());
        assertTrue(captured.getValue().get(0).getDataPoints().isEmpty());
        assertEquals("import",c.getDeviceId());
        assertNotSame(c,captured.getValue().get(0).getConnectionConfig());
    }

    @Test void importNullPointsKeepsExisting() {
        DeviceInfo d = device("keep"); DeviceConnection c = connection("keep");
        DataPoint p = new DataPoint(); p.setPointId("p"); p.setDeviceId("keep");
        when(manager.getDevice("keep")).thenReturn(d); when(manager.getConnectionConfig("keep")).thenReturn(c);
        when(manager.getDataPoints("keep")).thenReturn(List.of(p)); when(manager.replaceDeviceContextsAtomically(anyList())).thenReturn(true);
        ConfigImportRequest request = new ConfigImportRequest();
        request.setBundles(List.of(ConfigBundle.builder().device(d).points(null).build()));
        exchange.importConfigs(request);
        ArgumentCaptor<List<DeviceContext>> captured = ArgumentCaptor.forClass(List.class);
        verify(manager).replaceDeviceContextsAtomically(captured.capture()); assertEquals("p",captured.getValue().get(0).getDataPoints().get(0).getPointId());
    }
}
