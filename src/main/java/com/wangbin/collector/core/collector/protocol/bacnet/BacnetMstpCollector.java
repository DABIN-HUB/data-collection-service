package com.wangbin.collector.core.collector.protocol.bacnet;

/**
 * 实现当前协议或设备的采集能力。
 */
public class BacnetMstpCollector extends BacnetIpCollector {

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String protocolCode() {
        return "BACNET_MSTP";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String protocolDisplayName() {
        return "BACnet MS/TP";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String defaultTransportName() {
        return "MS/TP";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected String driverName() {
        return "SELF_IMPLEMENTED_MSTP";
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
        return "BACnet MS/TP supports polling, segmented APDU assembly, WriteProperty, COV subscriptions and token-based RS485 transport";
    }
}