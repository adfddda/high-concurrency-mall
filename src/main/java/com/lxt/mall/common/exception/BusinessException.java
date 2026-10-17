package com.lxt.mall.common.exception;

import com.lxt.mall.common.api.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>刻意继承 RuntimeException：业务校验失败不应该被上层强行 try-catch，
 * 统一由 {@code GlobalExceptionHandler} 兜住并翻译成 ResultCode。
 * 继承受检异常会污染每一层方法签名。
 *
 * <p>不填充堆栈（{@code super(msg, null, false, false)}）：
 * 业务异常在高并发下可能每秒产生上万次，填栈是纯粹的 CPU 浪费。
 * 真正的故障由日志和监控捕捉，不依赖这里的堆栈。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage(), null, false, false);
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message, null, false, false);
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause, false, false);
        this.code = resultCode.getCode();
    }

    public static BusinessException of(ResultCode code) {
        return new BusinessException(code);
    }
}
