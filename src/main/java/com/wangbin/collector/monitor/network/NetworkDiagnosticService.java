package com.wangbin.collector.monitor.network;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 受限网络检测服务。
 *
 * <p>仅允许检测本机地址和设备配置中已经登记的目标，避免形成任意网络扫描入口。</p>
 */
@Slf4j
@Service
public class NetworkDiagnosticService {

    private static final int DEFAULT_TIMEOUT_MILLIS = 3_000;
    private static final int MIN_TIMEOUT_MILLIS = 100;
    private static final int MAX_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_TRACE_HOPS = 12;
    private static final int MAX_TRACE_LINE_COUNT = 64;
    private static final int MAX_TRACE_OUTPUT_LENGTH = 32_768;
    private static final long TRACE_PROCESS_TIMEOUT_MILLIS = 15_000L;
    private static final Set<String> LOCAL_TARGETS = Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private final ConfigManager configManager;

    public NetworkDiagnosticService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 执行网络检测。
     *
     * @param request 检测请求
     * @return 检测结果
     */
    public NetworkDiagnosticResult diagnose(NetworkDiagnosticRequest request) {
        String target = normalizeTarget(request.target());
        authorizeTarget(request.deviceId(), target);
        int timeoutMillis = normalizeTimeout(request.timeoutMs());
        Integer port = validatePort(request.type(), request.port());
        long startedAt = System.nanoTime();
        InetAddress address;
        try {
            address = InetAddress.getByName(target);
        } catch (IOException exception) {
            return result(request, target, null, port, false, startedAt,
                    "目标地址解析失败：" + safeMessage(exception), List.of());
        }

        if (request.type() == NetworkDiagnosticType.TRACE) {
            return trace(request, target, address, startedAt, timeoutMillis);
        }

        try {
            boolean reachable = request.type() == NetworkDiagnosticType.TCP
                    ? testTcp(address, port, timeoutMillis)
                    : address.isReachable(timeoutMillis);
            String message = reachable
                    ? successMessage(request.type(), timeoutMillis)
                    : failureMessage(request.type(), timeoutMillis);
            return result(request, target, address.getHostAddress(), port, reachable, startedAt, message, List.of());
        } catch (IOException exception) {
            return result(request, target, address.getHostAddress(), port, false, startedAt,
                    "网络检测失败：" + safeMessage(exception), List.of());
        }
    }

    private NetworkDiagnosticResult trace(NetworkDiagnosticRequest request,
                                          String target,
                                          InetAddress address,
                                          long startedAt,
                                          int timeoutMillis) {
        Path outputPath = null;
        Process process = null;
        try {
            outputPath = Files.createTempFile("collector-network-trace-", ".log");
            ProcessBuilder processBuilder = new ProcessBuilder(traceCommand(target, timeoutMillis));
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(outputPath.toFile());
            process = processBuilder.start();
            boolean completed = process.waitFor(TRACE_PROCESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return result(request, target, address.getHostAddress(), null, false, startedAt,
                        "路由跟踪超过最大执行时间，已终止", readTraceLines(outputPath));
            }
            List<String> lines = readTraceLines(outputPath);
            boolean reachable = process.exitValue() == 0;
            String message = reachable ? "路由跟踪完成" : "路由跟踪命令已结束，但未确认目标可达";
            return result(request, target, address.getHostAddress(), null, reachable, startedAt, message, lines);
        } catch (IOException exception) {
            return result(request, target, address.getHostAddress(), null, false, startedAt,
                    "当前系统无法执行路由跟踪：" + safeMessage(exception), List.of());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return result(request, target, address.getHostAddress(), null, false, startedAt,
                    "路由跟踪被中断", List.of());
        } finally {
            deleteQuietly(outputPath);
        }
    }

    private List<String> traceCommand(String target, int timeoutMillis) {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (operatingSystem.contains("win")) {
            return List.of("tracert", "-d", "-h", String.valueOf(MAX_TRACE_HOPS),
                    "-w", String.valueOf(Math.min(timeoutMillis, 2_000)), target);
        }
        int timeoutSeconds = Math.max(1, Math.min(2, (int) Math.ceil(timeoutMillis / 1_000.0D)));
        return List.of("traceroute", "-n", "-m", String.valueOf(MAX_TRACE_HOPS),
                "-w", String.valueOf(timeoutSeconds), target);
    }

    private List<String> readTraceLines(Path outputPath) {
        if (outputPath == null || !Files.exists(outputPath)) {
            return List.of();
        }
        try {
            byte[] bytes = Files.readAllBytes(outputPath);
            int length = Math.min(bytes.length, MAX_TRACE_OUTPUT_LENGTH);
            String output = new String(bytes, 0, length, Charset.defaultCharset());
            return Arrays.stream(output.split("\\R"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .limit(MAX_TRACE_LINE_COUNT)
                    .toList();
        } catch (IOException exception) {
            return List.of("路由跟踪输出读取失败：" + safeMessage(exception));
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.debug("删除网络诊断临时文件失败：{}", path, exception);
        }
    }

    private void authorizeTarget(String deviceId, String target) {
        if (LOCAL_TARGETS.contains(target)) {
            return;
        }
        if (StringUtils.hasText(deviceId)) {
            DeviceInfo device = configManager.getDevice(deviceId.trim());
            if (device == null) {
                throw new IllegalArgumentException("检测设备不存在：" + deviceId.trim());
            }
            String configuredTarget = normalizeTarget(device.getIpAddress());
            if (!target.equals(configuredTarget)) {
                throw new IllegalArgumentException("检测目标必须与所选设备的配置地址一致");
            }
            return;
        }
        if (!configuredTargets().contains(target)) {
            throw new IllegalArgumentException("检测目标不在本机或设备配置白名单中");
        }
    }

    private Set<String> configuredTargets() {
        List<DeviceInfo> devices = configManager.getAllDevices();
        if (devices == null) {
            return Set.of();
        }
        return devices.stream()
                .map(DeviceInfo::getIpAddress)
                .filter(StringUtils::hasText)
                .map(this::normalizeTarget)
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean testTcp(InetAddress address, Integer port, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMillis);
            return socket.isConnected();
        }
    }

    private Integer validatePort(NetworkDiagnosticType type, Integer port) {
        if (type != NetworkDiagnosticType.TCP) {
            return null;
        }
        if (port == null || port < 1 || port > 65_535) {
            throw new IllegalArgumentException("TCP 检测需要 1 到 65535 之间的端口");
        }
        return port;
    }

    private int normalizeTimeout(Integer timeoutMillis) {
        if (timeoutMillis == null) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        return Math.max(MIN_TIMEOUT_MILLIS, Math.min(MAX_TIMEOUT_MILLIS, timeoutMillis));
    }

    private String normalizeTarget(String target) {
        if (!StringUtils.hasText(target)) {
            return "";
        }
        String normalized = target.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private NetworkDiagnosticResult result(NetworkDiagnosticRequest request,
                                           String target,
                                           String resolvedAddress,
                                           Integer port,
                                           boolean reachable,
                                           long startedAt,
                                           String message,
                                           List<String> details) {
        long durationMillis = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        return new NetworkDiagnosticResult(
                request.type(), request.deviceId(), target, resolvedAddress, port,
                reachable, durationMillis, message, List.copyOf(details), System.currentTimeMillis());
    }

    private String successMessage(NetworkDiagnosticType type, int timeoutMillis) {
        return type == NetworkDiagnosticType.TCP
                ? "TCP 端口连接成功"
                : "目标在 " + timeoutMillis + " 毫秒超时范围内可达";
    }

    private String failureMessage(NetworkDiagnosticType type, int timeoutMillis) {
        return type == NetworkDiagnosticType.TCP
                ? "TCP 端口无法连接或连接超时"
                : "目标在 " + timeoutMillis + " 毫秒内不可达；部分系统可能限制 ICMP 检测";
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return StringUtils.hasText(message) ? message : exception.getClass().getSimpleName();
    }
}