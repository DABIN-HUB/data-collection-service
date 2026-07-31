package com.wangbin.collector.common.domain.entity;

/**
 * 承载当前模块的数据传输内容。
 */
public class ApiResponse<T> {
    private Integer code;
    private String msg;
    private T data;

    // 访问器方法。
    public Integer getCode() { return code; }
    public void setCode(Integer code) { this.code = code; }

    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public boolean isSuccess() {
        return code != null && code == 0;
    }
}
