package com.wangbin.collector.core.collector.protocol.bacnet;

/**
 * 实现当前协议或设备的采集能力。
 */
public class BacnetScCollector extends BacnetIpCollector {

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String protocolCode() {
        return "BACNET_SC";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String protocolDisplayName() {
        return "BACnet/SC";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String defaultTransportName() {
        return "WSS";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String driverName() {
        return "SECURE_WS_TUNNEL_EXPERIMENTAL";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean supportsForeignDeviceRegistration() {
        return false;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String capabilityMessage() {
        return "BACnet/SC currently runs over a secure WebSocket binary tunnel with polling, segmented APDU assembly, WriteProperty and COV support; standard hub/node control framing remains experimental";
    }
}