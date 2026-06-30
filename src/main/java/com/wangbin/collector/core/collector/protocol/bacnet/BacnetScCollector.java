package com.wangbin.collector.core.collector.protocol.bacnet;

public class BacnetScCollector extends BacnetIpCollector {

    @Override
    protected String protocolCode() {
        return "BACNET_SC";
    }

    @Override
    protected String protocolDisplayName() {
        return "BACnet/SC";
    }

    @Override
    protected String defaultTransportName() {
        return "WSS";
    }

    @Override
    protected String driverName() {
        return "SECURE_WS_TUNNEL_EXPERIMENTAL";
    }

    @Override
    protected boolean supportsForeignDeviceRegistration() {
        return false;
    }

    @Override
    protected String capabilityMessage() {
        return "BACnet/SC currently runs over a secure WebSocket binary tunnel with polling, segmented APDU assembly, WriteProperty and COV support; standard hub/node control framing remains experimental";
    }
}