package com.wangbin.collector.core.collector.protocol.iec101.link;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Iec101LinkStateMachineTest {

    @Test
    void shouldToggleFrameCountOnlyAfterConfirmedSuccess() {
        Iec101LinkStateMachine stateMachine = new Iec101LinkStateMachine();

        int first = stateMachine.primaryControl(3, true);
        stateMachine.markConfirmedSuccess();
        int second = stateMachine.primaryControl(3, true);

        assertFalse((first & 0x20) != 0);
        assertTrue((second & 0x20) != 0);
        assertEquals(3, first & 0x0F);
    }
}
