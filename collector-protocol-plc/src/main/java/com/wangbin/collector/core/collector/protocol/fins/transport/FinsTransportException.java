package com.wangbin.collector.core.collector.protocol.fins.transport;

/** FINS wire/connection 故障分类；FINS EndCode 等业务错误仍由 FinsFrameCodec 处理。 */
public final class FinsTransportException extends IllegalStateException {
    public enum Code {
        CONNECT_TIMEOUT, SEND_TIMEOUT, READ_TIMEOUT, TCP_HANDSHAKE_ERROR, TCP_FRAME_ERROR,
        UDP_RECEIVE_ERROR, FINS_TCP_ERROR_CODE, REMOTE_CLOSED, FRAME_TOO_LARGE
    }

    private final Code code;

    public FinsTransportException(Code code, String detail, Throwable cause) {
        super(code + ": " + detail, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
