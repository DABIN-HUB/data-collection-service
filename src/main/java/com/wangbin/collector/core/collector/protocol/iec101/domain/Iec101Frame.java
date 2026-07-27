package com.wangbin.collector.core.collector.protocol.iec101.domain;

import java.util.Arrays;

/**
 * IEC101 FT1.2 逻辑帧。
 */
public final class Iec101Frame {

    private final Iec101FrameType type;
    private final int control;
    private final int linkAddress;
    private final byte[] userData;

    public Iec101Frame(Iec101FrameType type, int control, int linkAddress, byte[] userData) {
        this.type = type;
        this.control = control & 0xFF;
        this.linkAddress = linkAddress;
        this.userData = userData == null ? new byte[0] : Arrays.copyOf(userData, userData.length);
    }

    public static Iec101Frame singleAck() {
        return new Iec101Frame(Iec101FrameType.SINGLE_ACK, 0, 0, new byte[0]);
    }

    public Iec101FrameType type() {
        return type;
    }

    public int control() {
        return control;
    }

    public int linkAddress() {
        return linkAddress;
    }

    public byte[] userData() {
        return Arrays.copyOf(userData, userData.length);
    }

    public boolean accessDemand() {
        return type != Iec101FrameType.SINGLE_ACK && (control & 0x20) != 0;
    }

    public boolean dataFlowControl() {
        return type != Iec101FrameType.SINGLE_ACK && (control & 0x10) != 0;
    }

    public int functionCode() {
        return control & 0x0F;
    }
}
