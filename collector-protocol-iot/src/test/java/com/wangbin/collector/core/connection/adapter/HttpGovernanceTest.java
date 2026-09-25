package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.http.extractor.JsonPathHttpResponseExtractor;
import com.wangbin.collector.core.collector.protocol.http.extractor.PointArrayHttpResponseExtractor;
import com.wangbin.collector.core.collector.protocol.http.extractor.RawHttpResponseExtractor;
import com.wangbin.collector.core.config.validator.HttpConfigurationContract;
import com.wangbin.collector.core.collector.protocol.http.HttpCollector;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class HttpGovernanceTest {
    private DeviceConnection config() {
        DeviceConnection config = new DeviceConnection();
        config.setHost("127.0.0.1");
        config.setPort(8080);
        return config;
    }

    private DataPoint point(String id, String code, String address) {
        DataPoint point = new DataPoint();
        point.setPointId(id);
        point.setPointCode(code);
        point.setAddress(address);
        return point;
    }

    @Test
    void urlCombinesBasePathEndpointAndExistingQuery() {
        assertEquals("http://device/api/v1?x=1&y=hello+world", HttpUrlResolver.resolve(
                "http://device/api/?x=1", "/v1", Map.of("y", "hello world")).toString());
        assertEquals("http://device/api/v1", HttpUrlResolver.resolve("http://device/api/", "v1", null).toString());
        assertEquals("http://device/api", HttpUrlResolver.resolve("http://device/api", "", null).toString());
        assertEquals("http://device/api/v1", HttpUrlResolver.resolve("http://device", "/api/v1", null).toString());
        assertThrows(IllegalArgumentException.class, () -> HttpUrlResolver.resolve("http://device", "/a", java.util.Collections.singletonMap("x", null)));
    }

    @Test
    void modesAndLegacyPathAreValidated() {
        DeviceConnection config = config();
        assertEquals("AUTO_COMPAT", HttpConfigurationContract.requestMode(config));
        assertEquals("RAW", HttpConfigurationContract.responseMode(config));
        config.setExtJson(new LinkedHashMap<>(Map.of("apiPrefix", "/legacy")));
        assertEquals("/legacy", HttpConfigurationContract.path(config));
        config.getExtJson().put("path", "/canonical");
        assertEquals("/canonical", HttpConfigurationContract.path(config));
        config.getExtJson().put("requestMode", "DIREC");
        assertThrows(IllegalArgumentException.class, () -> HttpConfigurationContract.validate(config));
        config.getExtJson().put("requestMode", "DIRECT");
        config.getExtJson().put("responseMode", "POINT_ARRY");
        assertThrows(IllegalArgumentException.class, () -> HttpConfigurationContract.validate(config));
    }

    @Test
    void validatorAndRuntimeRejectBadModesAndConflictingAuth() {
        DeviceConnection config = config();
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("http-device");
        device.setProtocolType("HTTP");
        config.setExtJson(new LinkedHashMap<>(Map.of("requestMode", "UNKNOWN")));
        assertThrows(RuntimeException.class, () -> new ProtocolConnectionValidator().validate(device, config));
        assertThrows(IllegalArgumentException.class, () -> new HttpConnectionAdapter(device, config));
        config.getExtJson().put("requestMode", "DIRECT");
        config.setUsername("user");
        config.setPassword("password");
        config.setAuthToken("token");
        assertThrows(RuntimeException.class, () -> new ProtocolConnectionValidator().validate(device, config));
    }

    @Test
    void writeAckRejectsEmptyMalformedAndExplicitFailure() throws Exception {
        HttpCollector collector = new HttpCollector();
        Method parse = HttpCollector.class.getDeclaredMethod("parseWriteAck", byte[].class);
        parse.setAccessible(true);
        assertEquals(true, parse.invoke(collector, (Object) "{\"success\":true}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(false, parse.invoke(collector, (Object) "{\"success\":false}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(true, parse.invoke(collector, (Object) "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(false, parse.invoke(collector, (Object) "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(false, parse.invoke(collector, (Object) "{broken".getBytes(StandardCharsets.UTF_8)));
        assertEquals(false, parse.invoke(collector, (Object) new byte[0]));
    }

    @Test
    void tlsRequiresExplicitOptInForInsecureContext() throws Exception {
        DeviceConnection config = config();
        config.setSslEnabled(true);
        HttpConnectionAdapter secure = new HttpConnectionAdapter(new DeviceInfo(), config);
        assertSame(SSLContext.getDefault(), secure.getClient().sslContext());
        config.setExtJson(Map.of("insecureSkipVerify", true));
        HttpConnectionAdapter insecure = new HttpConnectionAdapter(new DeviceInfo(), config);
        assertNotSame(SSLContext.getDefault(), insecure.getClient().sslContext());
        config.setSslEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> new HttpConnectionAdapter(new DeviceInfo(), config));
    }

    @Test
    void receiveTimeoutAppliesToResponseNotJustConnect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(700);
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var stream = exchange.getResponseBody()) { stream.write(body); }
            } catch (Exception ignored) {
                exchange.close();
            }
        });
        server.start();
        try {
            DeviceConnection config = config();
            config.setPort(server.getAddress().getPort());
            config.setConnectTimeout(3000);
            config.setReadTimeout(3000);
            config.setExtJson(Map.of("receiveEndpoint", "/slow"));
            HttpConnectionAdapter adapter = new HttpConnectionAdapter(new DeviceInfo(), config);
            adapter.connect(); // No implicit /health request.
            assertThrows(UnsupportedOperationException.class, () -> adapter.receive(100));
            assertEquals("ok", new String(adapter.receive(3000), StandardCharsets.UTF_8));
            adapter.disconnect();
        } finally { server.stop(0); }
    }

    @Test
    void extractorsShareIdentityAndPreservePartialBatch() {
        DataPoint first = point("id-1", "code-1", "/sensor/alpha");
        DataPoint missing = point("id-2", "code-2", "/sensor/beta");
        List<DataPoint> points = List.of(first, missing);
        byte[] raw = "{\"values\":{\"alpha\":11}}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Map.of("id-1", 11), new RawHttpResponseExtractor().extract(raw, points, Map.of()));
        byte[] nested = "{\"data\":{\"alpha\":11}}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Map.of("id-1", 11), new JsonPathHttpResponseExtractor().extract(nested, points, Map.of("responsePath", "$.data")));
        byte[] array = "{\"points\":[{\"name\":\"alpha\",\"value\":11}]}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Map.of("id-1", 11), new PointArrayHttpResponseExtractor().extract(array, points, Map.of()));
        byte[] duplicate = "{\"points\":[{\"name\":\"alpha\",\"value\":11},{\"name\":\"alpha\",\"value\":12}]}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new PointArrayHttpResponseExtractor().extract(duplicate, points, Map.of()));
        byte[] incomplete = "{\"points\":[{\"name\":\"alpha\",\"value\":11},{\"value\":12},{\"name\":\"beta\",\"value\":null}]}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Map.of("id-1", 11), new PointArrayHttpResponseExtractor().extract(incomplete, points, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PointArrayHttpResponseExtractor().extract(
                "{\"points\":{}}".getBytes(StandardCharsets.UTF_8), points, Map.of()));
        assertTrue(new RawHttpResponseExtractor().extract("plain".getBytes(StandardCharsets.UTF_8), points, Map.of()).isEmpty());
        assertEquals(Map.of("id-1", "plain"), new RawHttpResponseExtractor().extract(
                "plain".getBytes(StandardCharsets.UTF_8), List.of(first), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RawHttpResponseExtractor().extract(
                "{broken".getBytes(StandardCharsets.UTF_8), List.of(first), Map.of()));
    }
}
