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

    /**
     * 创建当前组件实例。
     */
    public Iec101Frame(Iec101FrameType type, int control, int linkAddress, byte[] userData) {
        this.type = type;
        this.control = control & 0xFF;
        this.linkAddress = linkAddress;
        this.userData = userData == null ? new byte[0] : Arrays.copyOf(userData, userData.length);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static Iec101Frame singleAck() {
        return new Iec101Frame(Iec101FrameType.SINGLE_ACK, 0, 0, new byte[0]);
    }

    /**
     * 执行当前业务逻辑。
     */
    public Iec101FrameType type() {
        return type;
    }

    /**
     * 执行当前业务逻辑。
     */
    public int control() {
        return control;
    }

    /**
     * 执行当前业务逻辑。
     */
    public int linkAddress() {
        return linkAddress;
    }

    /**
     * 执行当前业务逻辑。
     */
    public byte[] userData() {
        return Arrays.copyOf(userData, userData.length);
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean accessDemand() {
        return type != Iec101FrameType.SINGLE_ACK && (control & 0x20) != 0;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean dataFlowControl() {
        return type != Iec101FrameType.SINGLE_ACK && (control & 0x10) != 0;
    }

    /**
     * 执行当前业务逻辑。
     */
    public int functionCode() {
        return control & 0x0F;
    }
}
