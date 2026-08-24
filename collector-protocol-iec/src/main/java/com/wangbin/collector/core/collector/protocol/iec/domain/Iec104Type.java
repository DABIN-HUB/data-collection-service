package com.wangbin.collector.core.collector.protocol.iec.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * IEC60870-5-104 类型标识注册表。
 *
 * <p>The enum keeps the 协议 raw type id. Code that needs a base matching
 * family, such as 遥测 缓存 matching, should use {@link #familyTypeId()}.
 */
public enum Iec104Type {

    M_SP_NA_1(1, Category.MONITOR, ValueKind.SINGLE_POINT, TimestampKind.NONE, 1, true, false,
            "SINGLE_POINT", "SINGLE_POINT_INFORMATION"),
    M_SP_TA_1(2, Category.MONITOR, ValueKind.SINGLE_POINT, TimestampKind.CP24, 1, true, false),
    M_DP_NA_1(3, Category.MONITOR, ValueKind.DOUBLE_POINT, TimestampKind.NONE, 3, true, false,
            "DOUBLE_POINT", "DOUBLE_POINT_INFORMATION"),
    M_DP_TA_1(4, Category.MONITOR, ValueKind.DOUBLE_POINT, TimestampKind.CP24, 3, true, false),
    M_ST_NA_1(5, Category.MONITOR, ValueKind.STEP_POSITION, TimestampKind.NONE, 5, true, false,
            "STEP_POSITION", "STEP_POSITION_INFORMATION"),
    M_ST_TA_1(6, Category.MONITOR, ValueKind.STEP_POSITION, TimestampKind.CP24, 5, true, false),
    M_BO_NA_1(7, Category.MONITOR, ValueKind.BIT_STRING, TimestampKind.NONE, 7, true, false,
            "BITSTRING_STATUS", "BINARY_STATE_INFORMATION", "BIT_STRING_STATUS"),
    M_BO_TA_1(8, Category.MONITOR, ValueKind.BIT_STRING, TimestampKind.CP24, 7, true, false),
    M_ME_NA_1(9, Category.MONITOR, ValueKind.NORMALIZED_VALUE, TimestampKind.NONE, 9, true, false,
            "NORMALIZED_VALUE", "MEASURED_VALUE_NORMALIZED", "MEASURED_NORMALIZED"),
    M_ME_TA_1(10, Category.MONITOR, ValueKind.NORMALIZED_VALUE, TimestampKind.CP24, 9, true, false),
    M_ME_NB_1(11, Category.MONITOR, ValueKind.SCALED_VALUE, TimestampKind.NONE, 11, true, false,
            "SCALED_VALUE", "MEASURED_VALUE_SCALED", "MEASURED_SCALED"),
    M_ME_TB_1(12, Category.MONITOR, ValueKind.SCALED_VALUE, TimestampKind.CP24, 11, true, false),
    M_ME_NC_1(13, Category.MONITOR, ValueKind.SHORT_FLOAT, TimestampKind.NONE, 13, true, false,
            "SHORT_FLOAT", "SHORT_FLOATING_POINT_NUMBER",
            "MEASURED_VALUE_SHORT_FLOAT", "MEASURED_VALUE_FLOAT"),
    M_ME_TC_1(14, Category.MONITOR, ValueKind.SHORT_FLOAT, TimestampKind.CP24, 13, true, false),
    M_IT_NA_1(15, Category.MONITOR, ValueKind.INTEGRATED_TOTAL, TimestampKind.NONE, 15, true, false,
            "INTEGRATED_TOTAL", "BINARY_COUNTER", "BINARY_COUNTER_READING"),
    M_IT_TA_1(16, Category.MONITOR, ValueKind.INTEGRATED_TOTAL, TimestampKind.CP24, 15, true, false),
    M_EP_TA_1(17, Category.MONITOR, ValueKind.PROTECTION_EVENT, TimestampKind.CP16, 17, false, false,
            "PROTECTION_EVENT", "SINGLE_PROTECTION_EVENT"),
    M_EP_TB_1(18, Category.MONITOR, ValueKind.PROTECTION_START_EVENT, TimestampKind.CP16, 18, false, false,
            "PROTECTION_START_EVENT", "PACKED_START_EVENTS"),
    M_EP_TC_1(19, Category.MONITOR, ValueKind.PROTECTION_OUTPUT_CIRCUIT, TimestampKind.CP16, 19, false, false,
            "PROTECTION_OUTPUT_CIRCUIT", "PACKED_OUTPUT_CIRCUIT_INFO"),
    M_PS_NA_1(20, Category.MONITOR, ValueKind.PACKED_SINGLE_POINT, TimestampKind.NONE, 20, false, false,
            "PACKED_SINGLE_POINT", "PACKED_SINGLE_POINT_WITH_QUALITY"),
    M_ME_ND_1(21, Category.MONITOR, ValueKind.NORMALIZED_VALUE, TimestampKind.NONE, 9, true, false,
            "NORMALIZED_VALUE_WITHOUT_QUALITY", "MEASURED_VALUE_NORMALIZED_NO_QUALITY"),

    M_SP_TB_1(30, Category.MONITOR, ValueKind.SINGLE_POINT, TimestampKind.CP56, 1, true, false),
    M_DP_TB_1(31, Category.MONITOR, ValueKind.DOUBLE_POINT, TimestampKind.CP56, 3, true, false),
    M_ST_TB_1(32, Category.MONITOR, ValueKind.STEP_POSITION, TimestampKind.CP56, 5, true, false),
    M_BO_TB_1(33, Category.MONITOR, ValueKind.BIT_STRING, TimestampKind.CP56, 7, true, false),
    M_ME_TD_1(34, Category.MONITOR, ValueKind.NORMALIZED_VALUE, TimestampKind.CP56, 9, true, false),
    M_ME_TE_1(35, Category.MONITOR, ValueKind.SCALED_VALUE, TimestampKind.CP56, 11, true, false),
    M_ME_TF_1(36, Category.MONITOR, ValueKind.SHORT_FLOAT, TimestampKind.CP56, 13, true, false),
    M_IT_TB_1(37, Category.MONITOR, ValueKind.INTEGRATED_TOTAL, TimestampKind.CP56, 15, true, false),
    M_EP_TD_1(38, Category.MONITOR, ValueKind.PROTECTION_EVENT, TimestampKind.CP56, 17, false, false),
    M_EP_TE_1(39, Category.MONITOR, ValueKind.PROTECTION_START_EVENT, TimestampKind.CP56, 18, false, false),
    M_EP_TF_1(40, Category.MONITOR, ValueKind.PROTECTION_OUTPUT_CIRCUIT, TimestampKind.CP56, 19, false, false),

    C_SC_NA_1(45, Category.CONTROL, ValueKind.SINGLE_COMMAND, TimestampKind.NONE, 45, false, true,
            "SINGLE_COMMAND", "SINGLE_CONTROL", "SINGLE_POINT_COMMAND"),
    C_DC_NA_1(46, Category.CONTROL, ValueKind.DOUBLE_COMMAND, TimestampKind.NONE, 46, false, true,
            "DOUBLE_COMMAND", "DOUBLE_CONTROL"),
    C_RC_NA_1(47, Category.CONTROL, ValueKind.REGULATING_STEP_COMMAND, TimestampKind.NONE, 47, false, true,
            "REGULATING_STEP", "REGULATING_STEP_COMMAND", "STEP_COMMAND", "RAISE_LOWER_COMMAND"),
    C_SE_NA_1(48, Category.CONTROL, ValueKind.SETPOINT_NORMALIZED, TimestampKind.NONE, 48, false, true,
            "SETPOINT_NORMALIZED", "SET_POINT_NORMALIZED", "ANALOG_OUTPUT_NORMALIZED"),
    C_SE_NB_1(49, Category.CONTROL, ValueKind.SETPOINT_SCALED, TimestampKind.NONE, 49, false, true,
            "SETPOINT_SCALED", "SET_POINT_SCALED", "ANALOG_OUTPUT_SCALED"),
    C_SE_NC_1(50, Category.CONTROL, ValueKind.SETPOINT_SHORT_FLOAT, TimestampKind.NONE, 50, false, true,
            "SETPOINT_SHORT_FLOAT", "SET_POINT_SHORT_FLOAT", "SETPOINT_FLOAT", "ANALOG_OUTPUT_FLOAT"),
    C_BO_NA_1(51, Category.CONTROL, ValueKind.BIT_STRING_COMMAND, TimestampKind.NONE, 51, false, true,
            "BITSTRING_COMMAND", "BIT_STRING_COMMAND", "BITSTRING", "BIT_STRING"),

    C_SC_TA_1(58, Category.CONTROL, ValueKind.SINGLE_COMMAND, TimestampKind.CP56, 45, false, true,
            "SINGLE_COMMAND_TIMED", "SINGLE_POINT_COMMAND_TIMED"),
    C_DC_TA_1(59, Category.CONTROL, ValueKind.DOUBLE_COMMAND, TimestampKind.CP56, 46, false, true,
            "DOUBLE_COMMAND_TIMED"),
    C_RC_TA_1(60, Category.CONTROL, ValueKind.REGULATING_STEP_COMMAND, TimestampKind.CP56, 47, false, true,
            "REGULATING_STEP_TIMED", "STEP_COMMAND_TIMED"),
    C_SE_TA_1(61, Category.CONTROL, ValueKind.SETPOINT_NORMALIZED, TimestampKind.CP56, 48, false, true,
            "SETPOINT_NORMALIZED_TIMED"),
    C_SE_TB_1(62, Category.CONTROL, ValueKind.SETPOINT_SCALED, TimestampKind.CP56, 49, false, true,
            "SETPOINT_SCALED_TIMED"),
    C_SE_TC_1(63, Category.CONTROL, ValueKind.SETPOINT_SHORT_FLOAT, TimestampKind.CP56, 50, false, true,
            "SETPOINT_SHORT_FLOAT_TIMED", "SETPOINT_FLOAT_TIMED"),
    C_BO_TA_1(64, Category.CONTROL, ValueKind.BIT_STRING_COMMAND, TimestampKind.CP56, 51, false, true,
            "BITSTRING_COMMAND_TIMED", "BIT_STRING_COMMAND_TIMED"),

    M_EI_NA_1(70, Category.SYSTEM, ValueKind.INITIALIZATION_END, TimestampKind.NONE, 70, false, false),

    C_IC_NA_1(100, Category.SYSTEM, ValueKind.INTERROGATION_COMMAND, TimestampKind.NONE, 100, false, false,
            "INTERROGATION", "GENERAL_INTERROGATION"),
    C_CI_NA_1(101, Category.SYSTEM, ValueKind.COUNTER_INTERROGATION_COMMAND, TimestampKind.NONE, 101, false, false,
            "COUNTER_INTERROGATION"),
    C_RD_NA_1(102, Category.SYSTEM, ValueKind.READ_COMMAND, TimestampKind.NONE, 102, false, false,
            "READ_COMMAND"),
    C_CS_NA_1(103, Category.SYSTEM, ValueKind.CLOCK_SYNC_COMMAND, TimestampKind.CP56, 103, false, false,
            "CLOCK_SYNC", "CLOCK_SYNCHRONIZATION"),
    C_TS_NA_1(104, Category.SYSTEM, ValueKind.TEST_COMMAND, TimestampKind.NONE, 104, false, false,
            "TEST_COMMAND"),
    C_RP_NA_1(105, Category.SYSTEM, ValueKind.RESET_PROCESS_COMMAND, TimestampKind.NONE, 105, false, false,
            "RESET_PROCESS_COMMAND"),
    C_CD_NA_1(106, Category.SYSTEM, ValueKind.DELAY_ACQUISITION_COMMAND, TimestampKind.CP16, 106, false, false,
            "DELAY_ACQUISITION_COMMAND"),
    C_TS_TA_1(107, Category.SYSTEM, ValueKind.TEST_COMMAND, TimestampKind.CP56, 104, false, false,
            "TEST_COMMAND_TIMED"),

    P_ME_NA_1(110, Category.PARAMETER, ValueKind.PARAMETER_NORMALIZED, TimestampKind.NONE, 110, false, false),
    P_ME_NB_1(111, Category.PARAMETER, ValueKind.PARAMETER_SCALED, TimestampKind.NONE, 111, false, false),
    P_ME_NC_1(112, Category.PARAMETER, ValueKind.PARAMETER_SHORT_FLOAT, TimestampKind.NONE, 112, false, false),
    P_AC_NA_1(113, Category.PARAMETER, ValueKind.PARAMETER_ACTIVATION, TimestampKind.NONE, 113, false, false),

    F_FR_NA_1(120, Category.FILE, ValueKind.FILE_READY, TimestampKind.NONE, 120, false, false),
    F_SR_NA_1(121, Category.FILE, ValueKind.SECTION_READY, TimestampKind.NONE, 121, false, false),
    F_SC_NA_1(122, Category.FILE, ValueKind.FILE_CALL_SELECT, TimestampKind.NONE, 122, false, false),
    F_LS_NA_1(123, Category.FILE, ValueKind.LAST_SECTION_SEGMENT, TimestampKind.NONE, 123, false, false),
    F_AF_NA_1(124, Category.FILE, ValueKind.FILE_ACK, TimestampKind.NONE, 124, false, false),
    F_SG_NA_1(125, Category.FILE, ValueKind.FILE_SEGMENT, TimestampKind.NONE, 125, false, false),
    F_DR_TA_1(126, Category.FILE, ValueKind.FILE_DIRECTORY, TimestampKind.CP56, 126, false, false);

    private static final Map<Integer, Iec104Type> BY_ID;
    private static final Map<String, Iec104Type> BY_TOKEN;

    static {
        Map<Integer, Iec104Type> byId = new LinkedHashMap<>();
        Map<String, Iec104Type> byToken = new LinkedHashMap<>();
        for (Iec104Type type : values()) {
            byId.put(type.typeId, type);
            registerToken(byToken, type.name(), type);
            registerToken(byToken, String.valueOf(type.typeId), type);
            for (String alias : type.aliases) {
                registerToken(byToken, alias, type);
            }
        }
        BY_ID = Collections.unmodifiableMap(byId);
        BY_TOKEN = Collections.unmodifiableMap(byToken);
    }

    private final int typeId;
    private final Category category;
    private final ValueKind valueKind;
    private final TimestampKind timestampKind;
    private final int familyTypeId;
    private final boolean readSupported;
    private final boolean writeSupported;
    private final String[] aliases;

    /**
     * 创建当前组件实例。
     */
    Iec104Type(int typeId,
               Category category,
               ValueKind valueKind,
               TimestampKind timestampKind,
               int familyTypeId,
               boolean readSupported,
               boolean writeSupported,
               String... aliases) {
        this.typeId = typeId;
        this.category = category;
        this.valueKind = valueKind;
        this.timestampKind = timestampKind;
        this.familyTypeId = familyTypeId;
        this.readSupported = readSupported;
        this.writeSupported = writeSupported;
        this.aliases = aliases;
    }

    /**
     * 创建并返回业务对象。
     */
    public static Optional<Iec104Type> fromTypeId(int typeId) {
        return Optional.ofNullable(BY_ID.get(typeId));
    }

    /**
     * 创建并返回业务对象。
     */
    public static Optional<Iec104Type> fromToken(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw instanceof Number number) {
            return fromTypeId(number.intValue());
        }
        String text = raw.toString();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        try {
            return fromTypeId(Integer.parseInt(trimmed));
        } catch (NumberFormatException ignored) {
            return Optional.ofNullable(BY_TOKEN.get(normalizeToken(trimmed)));
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    public static Iec104Type requireToken(Object raw) {
        return fromToken(raw)
                .orElseThrow(() -> new IllegalArgumentException("Invalid IEC 104 type token: " + raw));
    }

    /**
     * 执行当前业务逻辑。
     */
    public static int canonicalTypeId(int typeId) {
        return fromTypeId(typeId)
                .map(Iec104Type::familyTypeId)
                .orElse(typeId);
    }

    /**
     * 执行当前业务逻辑。
     */
    public int typeId() {
        return typeId;
    }

    /**
     * 执行当前业务逻辑。
     */
    public String typeName() {
        return name();
    }

    /**
     * 执行当前业务逻辑。
     */
    public Category category() {
        return category;
    }

    /**
     * 执行当前业务逻辑。
     */
    public ValueKind valueKind() {
        return valueKind;
    }

    /**
     * 执行当前业务逻辑。
     */
    public TimestampKind timestampKind() {
        return timestampKind;
    }

    /**
     * 执行当前业务逻辑。
     */
    public int familyTypeId() {
        return familyTypeId;
    }

    /**
     * 查询并返回业务数据。
     */
    public boolean readSupported() {
        return readSupported;
    }

    /**
     * 写入或持久化业务数据。
     */
    public boolean writeSupported() {
        return writeSupported;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean timed() {
        return timestampKind != TimestampKind.NONE;
    }

    /**
     * 维护注册或订阅关系。
     */
    private static void registerToken(Map<String, Iec104Type> map, String token, Iec104Type type) {
        map.put(normalizeToken(token), type);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalizeToken(String raw) {
        return raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    /**
     * 定义当前模块的枚举值。
     */
    public enum Category {
        MONITOR,
        CONTROL,
        SYSTEM,
        PARAMETER,
        FILE
    }

    /**
     * 定义当前模块的枚举值。
     */
    public enum TimestampKind {
        NONE,
        CP16,
        CP24,
        CP56
    }

    /**
     * 定义当前模块的枚举值。
     */
    public enum ValueKind {
        SINGLE_POINT,
        DOUBLE_POINT,
        STEP_POSITION,
        BIT_STRING,
        NORMALIZED_VALUE,
        SCALED_VALUE,
        SHORT_FLOAT,
        INTEGRATED_TOTAL,
        PROTECTION_EVENT,
        PROTECTION_START_EVENT,
        PROTECTION_OUTPUT_CIRCUIT,
        PACKED_SINGLE_POINT,
        SINGLE_COMMAND,
        DOUBLE_COMMAND,
        REGULATING_STEP_COMMAND,
        SETPOINT_NORMALIZED,
        SETPOINT_SCALED,
        SETPOINT_SHORT_FLOAT,
        BIT_STRING_COMMAND,
        INITIALIZATION_END,
        INTERROGATION_COMMAND,
        COUNTER_INTERROGATION_COMMAND,
        READ_COMMAND,
        CLOCK_SYNC_COMMAND,
        TEST_COMMAND,
        RESET_PROCESS_COMMAND,
        DELAY_ACQUISITION_COMMAND,
        PARAMETER_NORMALIZED,
        PARAMETER_SCALED,
        PARAMETER_SHORT_FLOAT,
        PARAMETER_ACTIVATION,
        FILE_READY,
        SECTION_READY,
        FILE_CALL_SELECT,
        LAST_SECTION_SEGMENT,
        FILE_ACK,
        FILE_SEGMENT,
        FILE_DIRECTORY
    }
}
