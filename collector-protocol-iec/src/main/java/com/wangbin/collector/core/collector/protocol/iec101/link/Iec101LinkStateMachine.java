package com.wangbin.collector.core.collector.protocol.iec101.link;

/**
 * IEC101 非平衡控制站链路状态机。
 */
public class Iec101LinkStateMachine {

    private boolean frameCountBit;

    /**
     * 执行当前业务逻辑。
     */
    public int primaryControl(int functionCode, boolean useFrameCount) {
        int control = 0x40 | (functionCode & 0x0F);
        if (useFrameCount) {
            control |= 0x10;
            if (frameCountBit) {
                control |= 0x20;
            }
        }
        return control;
    }

    /**
     * 记录或统计业务状态。
     */
    public void markConfirmedSuccess() {
        frameCountBit = !frameCountBit;
    }

    /**
     * 记录或统计业务状态。
     */
    public void reset() {
        frameCountBit = false;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean frameCountBit() {
        return frameCountBit;
    }
}
