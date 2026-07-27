package com.wangbin.collector.core.collector.protocol.iec101.transport;

import com.wangbin.collector.core.collector.protocol.iec101.Iec101ProtocolException;
import com.wangbin.collector.core.collector.protocol.iec101.codec.Iec101AsduCodec;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101Asdu;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101InformationObject;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101LinkConfig;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101Sample;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;

import java.util.ArrayList;
import java.util.List;

/**
 * IEC101 单个逻辑站点会话。
 */
public final class Iec101Session implements AutoCloseable {

    private static final int DEFAULT_MAX_CLASS_POLLS = 32;

    private final int linkAddress;
    private final int commonAddress;
    private final Iec101LinkConfig config;
    private final Iec101Bus bus;
    private final SharedSerialChannelManager.Lease lease;

    public Iec101Session(int linkAddress,
                         int commonAddress,
                         Iec101LinkConfig config,
                         Iec101Bus bus,
                         SharedSerialChannelManager.Lease lease) {
        this.linkAddress = linkAddress;
        this.commonAddress = commonAddress;
        this.config = config;
        this.bus = bus;
        this.lease = lease;
    }

    public void initialize() throws Exception {
        bus.resetLink(linkAddress);
        bus.requestLinkStatus(linkAddress);
    }

    public List<Iec101Sample> generalInterrogation(int qualifier) throws Exception {
        byte[] request = Iec101AsduCodec.encodeInterrogation(commonAddress, qualifier, config);
        return decodeResponses(bus.sendAsdu(linkAddress, request, DEFAULT_MAX_CLASS_POLLS));
    }

    public List<Iec101Sample> counterInterrogation(int qualifier) throws Exception {
        byte[] request = Iec101AsduCodec.encodeCounterInterrogation(commonAddress, qualifier, config);
        return decodeResponses(bus.sendAsdu(linkAddress, request, DEFAULT_MAX_CLASS_POLLS));
    }

    public List<Iec101Sample> read(int informationObjectAddress) throws Exception {
        byte[] request = Iec101AsduCodec.encodeRead(commonAddress, informationObjectAddress, config);
        return decodeResponses(bus.sendAsdu(linkAddress, request, 4));
    }

    public List<Iec101Sample> pollClassOne() throws Exception {
        return decodeResponses(bus.requestClass(linkAddress, true));
    }

    public List<Iec101Sample> pollClassTwo() throws Exception {
        return decodeResponses(bus.requestClass(linkAddress, false));
    }

    public void synchronizeClock(long timestamp) throws Exception {
        byte[] request = Iec101AsduCodec.encodeClockSynchronization(commonAddress, timestamp, config);
        validateCommandResponses(bus.sendAsdu(linkAddress, request, 4), 103);
    }

    public void command(int typeId,
                        int informationObjectAddress,
                        Object value,
                        boolean select,
                        int qualifier) throws Exception {
        byte[] request = Iec101AsduCodec.encodeCommand(typeId, commonAddress,
                informationObjectAddress, value, select, qualifier, config);
        validateCommandResponses(bus.sendAsdu(linkAddress, request, 8), typeId);
    }

    public boolean isOpen() {
        return bus.isOpen();
    }

    public int linkAddress() {
        return linkAddress;
    }

    public int commonAddress() {
        return commonAddress;
    }

    @Override
    public void close() throws Exception {
        lease.close();
    }

    private List<Iec101Sample> decodeResponses(List<byte[]> responses) {
        List<Iec101Sample> samples = new ArrayList<>();
        for (byte[] response : responses) {
            Iec101Asdu asdu = Iec101AsduCodec.decode(response, config);
            if (asdu.negativeConfirm()) {
                throw new IllegalArgumentException("IEC101 从站返回否定确认，TypeId=" + asdu.typeId());
            }
            for (Iec101InformationObject informationObject : asdu.informationObjects()) {
                if (informationObject.value() == null) {
                    continue;
                }
                samples.add(new Iec101Sample(
                        asdu.commonAddress(),
                        asdu.typeId(),
                        informationObject.address(),
                        informationObject.value(),
                        informationObject.quality(),
                        informationObject.rawQuality(),
                        informationObject.sourceTimestamp()));
            }
        }
        return samples;
    }

    private void validateCommandResponses(List<byte[]> responses,
                                          int expectedTypeId) throws Iec101ProtocolException {
        boolean confirmed = false;
        for (byte[] response : responses) {
            Iec101Asdu asdu = Iec101AsduCodec.decode(response, config);
            if (asdu.typeId() != expectedTypeId) {
                continue;
            }
            if (asdu.negativeConfirm()) {
                throw new Iec101ProtocolException("IEC101 命令收到否定确认，TypeId=" + expectedTypeId);
            }
            if (asdu.causeOfTransmission() == Iec101AsduCodec.COT_ACTIVATION_CONFIRMATION
                    || asdu.causeOfTransmission() == Iec101AsduCodec.COT_ACTIVATION_TERMINATION) {
                confirmed = true;
            }
        }
        if (!confirmed) {
            throw new Iec101ProtocolException("IEC101 命令未收到激活确认，TypeId=" + expectedTypeId);
        }
    }
}
