package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.sun.net.httpserver.HttpServer;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.http.extractor.PointArrayHttpResponseExtractor;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class HttpConnectionAdapterTest {

    @Test
    void httpConnectionAdapterShouldUseInjectedExecutorWhenPresent() {
        Executor injected = Runnable::run;
        HttpConnectionAdapter adapter = new HttpConnectionAdapter(device(), connection(), injected);

        assertSame(injected, adapter.resolveHttpExecutor());
    }

    @Test
    void directRequestToFullUrlShouldNotAppendSlash() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1", exchange -> {
            byte[] body = exchange.getRequestURI().getRawPath().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            DeviceConnection config = connection();
            config.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
            HttpConnectionAdapter adapter = new HttpConnectionAdapter(device(), config);
            assertEquals("/api/v1", new String(adapter.request("GET", ""), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pointArrayExtractorShouldMapObservedHttpResponseByName() {
        String response = """
                {"device_id":"[REDACTED_DEVICE_ID]","points":[
                {"name":"temperature","value":21.6354,"unit":"°C"},
                {"name":"humidity","value":65.467,"unit":"%RH"},
                {"name":"pressure","value":1009.2087,"unit":"hPa"},
                {"name":"status","value":"normal","unit":""}]}
                """;
        DataPoint temperature = point("point-temperature", "temperature");
        DataPoint humidity = point("point-humidity", "humidity");
        DataPoint pressure = point("point-pressure", "pressure");
        DataPoint status = point("point-status", "status");
        Map<String, Object> values = new PointArrayHttpResponseExtractor().extract(
                response.getBytes(StandardCharsets.UTF_8),
                List.of(temperature, humidity, pressure, status), Map.of());
        assertEquals(4, values.size());
        assertEquals(21.6354, ((Number) values.get("point-temperature")).doubleValue());
        assertEquals(65.467, ((Number) values.get("point-humidity")).doubleValue());
        assertEquals(1009.2087, ((Number) values.get("point-pressure")).doubleValue());
        assertEquals("normal", values.get("point-status"));
    }

    private DataPoint point(String id, String code) {
        DataPoint point = new DataPoint();
        point.setPointId(id);
        point.setPointCode(code);
        return point;
    }

    private DeviceInfo device() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("dev-http");
        device.setProtocolType("HTTP");
        return device;
    }

    private DeviceConnection connection() {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("HTTP");
        connection.setHost("127.0.0.1");
        connection.setPort(8080);
        return connection;
    }
}
