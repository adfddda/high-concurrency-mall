package com.lxt.mall.common.exception;

import com.lxt.mall.common.api.ResultCode;

/** 限流拒绝异常，由 RateLimitAspect 抛出，最终映射为 42900。 */
public class RateLimitException extends BusinessException {

    public RateLimitException(String message) {
        super(ResultCode.RATE_LIMITED, message);
    }

    public RateLimitException() {
        super(ResultCode.RATE_LIMITED);
    }
}
