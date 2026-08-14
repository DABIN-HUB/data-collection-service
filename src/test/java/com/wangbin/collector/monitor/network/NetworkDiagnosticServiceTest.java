package com.wangbin.collector.monitor.network;

import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class NetworkDiagnosticServiceTest {

    private final ConfigManager configManager = mock(ConfigManager.class);
    private final NetworkDiagnosticService service = new NetworkDiagnosticService(configManager);

    @Test
    void shouldConnectToLocalTcpPort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            NetworkDiagnosticRequest request = new NetworkDiagnosticRequest(
                    NetworkDiagnosticType.TCP,
                    null,
                    "127.0.0.1",
                    serverSocket.getLocalPort(),
                    1_000);

            NetworkDiagnosticResult result = service.diagnose(request);

            assertThat(result.reachable()).isTrue();
            assertThat(result.port()).isEqualTo(serverSocket.getLocalPort());
            assertThat(result.message()).contains("连接成功");
        }
    }

    @Test
    void shouldRejectTargetOutsideConfiguredAllowlist() {
        NetworkDiagnosticRequest request = new NetworkDiagnosticRequest(
                NetworkDiagnosticType.PING,
                null,
                "192.0.2.100",
                null,
                500);

        assertThatThrownBy(() -> service.diagnose(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void shouldRequirePortForTcpTest() {
        NetworkDiagnosticRequest request = new NetworkDiagnosticRequest(
                NetworkDiagnosticType.TCP,
                null,
                "127.0.0.1",
                null,
                500);

        assertThatThrownBy(() -> service.diagnose(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端口");
    }
}