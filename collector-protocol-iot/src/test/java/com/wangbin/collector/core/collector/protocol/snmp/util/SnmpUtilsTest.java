package com.wangbin.collector.core.collector.protocol.snmp.util;

import org.junit.jupiter.api.Test;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.OctetString;

import static org.assertj.core.api.Assertions.assertThat;

class SnmpUtilsTest {

    @Test
    void parseSecurityLevelVariants() {
        assertThat(SnmpUtils.parseSecurityLevel("noAuthNoPriv"))
                .isEqualTo(SecurityLevel.NOAUTH_NOPRIV);
        assertThat(SnmpUtils.parseSecurityLevel("authNoPriv"))
                .isEqualTo(SecurityLevel.AUTH_NOPRIV);
        assertThat(SnmpUtils.parseSecurityLevel("authPriv"))
                .isEqualTo(SecurityLevel.AUTH_PRIV);
    }

    @Test
    void resolveProtocols() {
        assertThat(SnmpUtils.resolveAuthProtocol("sha256"))
                .isEqualTo(SnmpUtils.resolveAuthProtocol("sha"));
        assertThat(SnmpUtils.resolvePrivProtocol("AES-256").toDottedString())
                .isEqualTo(SnmpUtils.resolvePrivProtocol("AES256").toDottedString());
    }

    @Test
    void parseContextEngineIdFromHex() {
        OctetString value = SnmpUtils.parseContextEngineId("80:00:00:01:02:03:04");
        assertThat(value.getValue()).containsExactly(0x80, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04);
    }
}
