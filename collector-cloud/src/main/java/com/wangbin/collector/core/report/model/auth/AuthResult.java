package com.wangbin.collector.core.report.model.auth;

import lombok.Data;

/**
 * 认证结果
 */
@Data
public class AuthResult {
    private boolean success;
    private String token;
    private String errorMessage;
    private long authTime;
    private long expireTime;
    private String clientId;

    /**
     * 构造标准业务结果。
     */
    public static AuthResult success(String token, String clientId, long expireTime) {
        AuthResult result = new AuthResult();
        result.setSuccess(true);
        result.setToken(token);
        result.setClientId(clientId);
        result.setExpireTime(expireTime);
        result.setAuthTime(System.currentTimeMillis());
        return result;
    }

    /**
     * 构造标准业务结果。
     */
    public static AuthResult error(String errorMessage) {
        AuthResult result = new AuthResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        result.setAuthTime(System.currentTimeMillis());
        return result;
    }
}