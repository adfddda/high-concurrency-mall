package com.lxt.mall.common.api;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>HTTP 状态码与业务码分离：网络层统一 200，业务结果看 {@code code}。
 * 这样网关/负载均衡不会因为业务失败而重试，前端也只需要一套解析逻辑。
 */
@Data
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int code;

    private String message;

    private T data;

    private long timestamp = System.currentTimeMillis();

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(ResultCode code) {
        return fail(code.getCode(), code.getMessage());
    }

    public static <T> Result<T> fail(ResultCode code, String message) {
        return fail(code.getCode(), message);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
