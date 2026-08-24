package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolDescriptorRegistryTest {

    @Test
    void providersShouldRegisterDescriptorsAliasesAndDefaults() {
        ProtocolDescriptorRegistry registry = ProtocolDescriptorTestProviders.registry();

        assertEquals(Set.of("MODBUS_TCP", "BACNET_IP"), primaryCodes(registry));
        assertEquals("BACNET_IP", registry.resolve("BACNET/IP").code());

        DeviceConnection connection = new DeviceConnection();
        assertEquals("MODBUS_TCP", registry.applyConnectionDefaults("modbus-tcp", connection));
        assertEquals(502, connection.getPort());
    }

    @Test
    void duplicateProtocolCodeMustFailFast() {
        ProtocolDescriptorProvider first = registry -> registry.registerPrimary(descriptor("DUPLICATE", List.of()));
        ProtocolDescriptorProvider second = registry -> registry.registerPrimary(descriptor("DUPLICATE", List.of()));

        assertThrows(IllegalStateException.class, () -> new ProtocolDescriptorRegistry(List.of(first, second)));
    }

    @Test
    void duplicateAliasMustFailFast() {
        ProtocolDescriptorProvider first = registry -> registry.registerPrimary(descriptor("PRIMARY_A", List.of("SHARED")));
        ProtocolDescriptorProvider second = registry -> registry.registerPrimary(descriptor("PRIMARY_B", List.of("SHARED")));

        assertThrows(IllegalStateException.class, () -> new ProtocolDescriptorRegistry(List.of(first, second)));
    }

    @Test
    void aliasMustNotConflictWithPrimaryCode() {
        ProtocolDescriptorProvider first = registry -> registry.registerPrimary(descriptor("PRIMARY_A", List.of()));
        ProtocolDescriptorProvider second = registry -> registry.registerPrimary(descriptor("PRIMARY_B", List.of("PRIMARY_A")));

        assertThrows(IllegalStateException.class, () -> new ProtocolDescriptorRegistry(List.of(first, second)));
    }

    @Test
    void registryMustNotDependOnConcreteCollectorImplementations() throws Exception {
        Path sourcePath = Path.of("src/main/java/com/wangbin/collector/core/config/protocol/ProtocolDescriptorRegistry.java");
        if (!Files.exists(sourcePath)) {
            sourcePath = Path.of("collector-protocol-spi").resolve(sourcePath);
        }
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

        assertFalse(source.contains("core.collector.protocol.ads"));
        assertFalse(source.contains("core.collector.protocol.bacnet"));
        assertFalse(source.contains("core.collector.protocol.modbus"));
        assertFalse(source.contains("new ModbusProtocolDescriptorProvider"));
        assertFalse(source.contains("descriptor(\"MODBUS"));
        assertFalse(source.contains("descriptor(\"BACNET"));
    }

    private static Set<String> primaryCodes(ProtocolDescriptorRegistry registry) {
        return registry.primaryDescriptors().stream()
                .map(ProtocolDescriptor::code)
                .collect(Collectors.toSet());
    }

    private static ProtocolDescriptor descriptor(String code, List<String> aliases) {
        return new ProtocolDescriptor(code, code, code, aliases,
                ProtocolDescriptorTestProviders.registry().resolve("MODBUS_TCP").collectorClass(),
                code, null, ProtocolAddressingMode.NUMERIC, true, false, false, List.of(), List.of());
    }
}
