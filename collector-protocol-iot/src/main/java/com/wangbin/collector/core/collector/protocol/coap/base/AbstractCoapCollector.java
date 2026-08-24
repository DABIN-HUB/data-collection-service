package com.wangbin.collector.core.collector.protocol.coap.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.coap.domain.CoapPoint;
import com.wangbin.collector.core.collector.protocol.coap.util.CoapAddressParser;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.connection.adapter.CoapConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapObserveRelation;
import org.eclipse.californium.core.CoapResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CoAP 公共能力抽象。
 */
@Slf4j
public abstract class AbstractCoapCollector extends ConnectionBackedCollector {

    protected CoapConnectionAdapter coapConnection;
    protected String baseUri;
    protected int timeout = 5000;
    protected int port = 5683;

    protected final Map<String, CoapObserveRelation> observeRelations = new ConcurrentHashMap<>();

    /**
     * 处理组件生命周期。
     */
    protected void initCoapConnection() throws Exception {
        CoapConnectionAdapter coapAdapter = createAndConnectAdapter(CoapConnectionAdapter.class, "CoAP");
        this.coapConnection = coapAdapter;
        this.timeout = Math.toIntExact(coapAdapter.getRequestTimeout());
        this.baseUri = coapAdapter.getBaseUri();
        log.info("CoAP连接已建立 地址={} 超时={}", baseUri, timeout);
    }

    /**
     * 执行当前业务逻辑。
     */
    protected void closeCoapConnection() {
        observeRelations.values().forEach(relation -> {
            try {
                relation.proactiveCancel();
            } catch (Exception e) {
                log.debug("关闭观察异常", e);
            }
        });
        observeRelations.clear();
        removeManagedConnection("CoAP");
        coapConnection = null;
    }

    /**
     * 解析或转换业务数据。
     */
    protected CoapPoint parsePoint(DataPoint point) {
        return CoapAddressParser.parse(point);
    }

    /**
     * 执行当前业务逻辑。
     */
    protected CoapResponse send(CoapPoint point, byte[] payload) throws Exception {
        if (coapConnection == null) {
            throw new IllegalStateException("CoAP连接尚未建立");
        }
        return coapConnection.execute(adapter -> {
            CoapClient client = adapter.createClient(point.resolveUri(baseUri));
            client.setTimeout((long) timeout);
            return switch (point.getMethod()) {
                case GET -> client.get();
                case POST -> client.post(payload, point.getMediaType());
                case PUT -> client.put(payload, point.getMediaType());
                case DELETE -> client.delete();
            };
        }, (long) timeout);
    }

    /**
     * 处理组件生命周期。
     */
    protected void startObserve(CoapPoint point, CoapHandler handler) {
        CoapClient client = createClient(point);
        CoapObserveRelation relation = client.observe(handler);
        observeRelations.put(point.getObserveKey(), relation);
    }

    /**
     * 处理组件生命周期。
     */
    protected void stopObserve(CoapPoint point) {
        CoapObserveRelation relation = observeRelations.remove(point.getObserveKey());
        if (relation != null) {
            relation.proactiveCancel();
        }
    }

    /**
     * 解析或转换业务数据。
     */
    protected Object convertResponse(CoapResponse response, CoapPoint point) {
        if (response == null) {
            return null;
        }
        if (!response.isSuccess()) {
            log.warn("CoAP请求失败 状态码={} 地址={}", response.getCode(), point.getPath());
            return null;
        }
        if (point.isBinary()) {
            return response.getPayload();
        }
        return response.getResponseText();
    }

    /**
     * 创建并返回业务对象。
     */
    private CoapClient createClient(CoapPoint point) {
        String uri = point.resolveUri(baseUri);
        if (coapConnection != null) {
            return coapConnection.createClient(uri);
        }
        return new CoapClient(uri);
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * 创建并返回业务对象。
     */
    protected CoapHandler createHandler(CoapPoint point) {
        return new CoapHandler() {
            /**
             * 执行当前业务逻辑。
             */
            @Override
            public void onLoad(CoapResponse response) {
                handleNotification(point, response);
            }

            /**
             * 执行当前业务逻辑。
             */
            @Override
            public void onError() {
                log.warn("CoAP观察发生错误 点位={}", point.getObserveKey());
            }
        };
    }

    /**
     * 处理当前业务流程。
     */
    protected void handleNotification(CoapPoint point, CoapResponse response) {
        // 子类覆盖处理
    }

}
