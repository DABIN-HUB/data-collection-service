package com.wangbin.collector.core.collector.protocol.bacnet;

public class BacnetMstpCollector extends BacnetIpCollector {

    @Override
    protected String protocolCode() {
        return "BACNET_MSTP";
    }

    @Override
    protected String protocolDisplayName() {
        return "BACnet MS/TP";
    }

    @Override
    protected String defaultTransportName() {
        return "MS/TP";
    }

    @Override
    protected String driverName() {
        return "SELF_IMPLEMENTED_MSTP";
    }

    @Override
    protected boolean supportsForeignDeviceRegistration() {
        return false;
    }

    @Override
    protected String capabilityMessage() {
        return "BACnet MS/TP supports polling, segmented APDU assembly, WriteProperty, COV subscriptions and token-based RS485 transport";
    }
}