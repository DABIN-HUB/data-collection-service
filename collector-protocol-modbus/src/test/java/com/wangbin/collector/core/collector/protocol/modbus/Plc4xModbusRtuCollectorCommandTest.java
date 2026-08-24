package com.wangbin.collector.core.collector.protocol.modbus;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class Plc4xModbusRtuCollectorCommandTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "READ_EXCEPTION_STATUS",
            "DIAGNOSTICS",
            "GET_COMM_EVENT_COUNTER",
            "GET_COMM_EVENT_LOG"
    })
    void shouldRejectCommandsNotSupportedByPlc4xSerialTransport(String command) {
        TestCollector collector = new TestCollector();

        assertThrows(UnsupportedOperationException.class,
                () -> collector.executeWithoutConnection(command));
    }

    private static class TestCollector extends Plc4xModbusRtuCollector {

        private Object executeWithoutConnection(String command) throws Exception {
            return doExecuteCommand(1, command, Map.of());
        }
    }
}
