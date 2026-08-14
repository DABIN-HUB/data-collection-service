package com.wangbin.collector.core.collector.protocol.coap.domain;

import org.eclipse.californium.core.coap.CoAP;

/**
 * CoAP method 枚举。
 */
public enum CoapMethod {
    GET(CoAP.Code.GET),
    POST(CoAP.Code.POST),
    PUT(CoAP.Code.PUT),
    DELETE(CoAP.Code.DELETE);

    private final CoAP.Code code;

    /**
     * 创建当前组件实例。
     */
    CoapMethod(CoAP.Code code) {
        this.code = code;
    }

    public CoAP.Code getCode() {
        return code;
    }

    /**
     * 创建并返回业务对象。
     */
    public static CoapMethod fromText(String text) {
        if (text == null || text.isBlank()) {
            return GET;
        }
        return switch (text.trim().toUpperCase()) {
            case "POST" -> POST;
            case "PUT" -> PUT;
            case "DELETE" -> DELETE;
            case "GET" -> GET;
            default -> GET;
        };
    }
}
