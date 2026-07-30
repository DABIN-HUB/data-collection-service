package com.wangbin.collector.core.collector.protocol.fins.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public enum FinsMemoryArea {
    CIO(0xB0, 0x30, "CIO", "C"),
    WR(0xB1, 0x31, "WR", "W"),
    HR(0xB2, 0x32, "HR", "H"),
    AR(0xB3, 0x33, "AR", "A"),
    DM(0x82, 0x02, "DM", "D"),
    EM0(0xA0, 0x20, "EM0"),
    EM1(0xA1, 0x21, "EM1"),
    EM2(0xA2, 0x22, "EM2"),
    EM3(0xA3, 0x23, "EM3"),
    EM4(0xA4, 0x24, "EM4"),
    EM5(0xA5, 0x25, "EM5"),
    EM6(0xA6, 0x26, "EM6"),
    EM7(0xA7, 0x27, "EM7"),
    EM8(0xA8, 0x28, "EM8"),
    EM9(0xA9, 0x29, "EM9"),
    EMA(0xAA, 0x2A, "EMA"),
    EMB(0xAB, 0x2B, "EMB"),
    EMC(0xAC, 0x2C, "EMC"),
    EMD(0xAD, 0x2D, "EMD"),
    EME(0xAE, 0x2E, "EME"),
    EMF(0xAF, 0x2F, "EMF");

    private final int wordCode;
    private final int bitCode;
    private final Set<String> tokens;

    FinsMemoryArea(int wordCode, int bitCode, String... tokens) {
        this.wordCode = wordCode;
        this.bitCode = bitCode;
        this.tokens = Arrays.stream(tokens)
                .map(token -> token.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public int code(boolean bitUnit) {
        return bitUnit ? bitCode : wordCode;
    }

    public static FinsMemoryArea fromToken(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("FINS memory area cannot be empty");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (FinsMemoryArea area : values()) {
            if (area.tokens.contains(normalized)) {
                return area;
            }
        }
        throw new IllegalArgumentException("Unsupported FINS memory area: " + value);
    }
}