package com.wangbin.collector.core.collector.protocol.iec101.link;

/**
 * IEC101 非平衡控制站链路状态机。
 */
public class Iec101LinkStateMachine {

    private boolean frameCountBit;

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

    public void markConfirmedSuccess() {
        frameCountBit = !frameCountBit;
    }

    public void reset() {
        frameCountBit = false;
    }

    public boolean frameCountBit() {
        return frameCountBit;
    }
}
