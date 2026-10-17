package com.lxt.mall.common.exception;

import com.lxt.mall.common.api.Result;
import com.lxt.mall.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>设计原则：
 * <ol>
 *   <li>所有异常都必须在这里被"翻译"成统一的 Result，
 *       绝不让 Tomcat 默认错误页/堆栈直接暴露给调用方。</li>
 *   <li>可预期的业务异常打 WARN 且不打印堆栈；
 *       不可预期的异常打 ERROR 并打印完整堆栈——否则线上出问题时没有抓手。</li>
 *   <li>HTTP 状态码统一 200，业务语义走 Result.code，
 *       避免网关/Nginx 把 5xx 当成服务不可用而触发熔断与重试放大。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** @RequestBody 上的 @Valid 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return Result.fail(ResultCode.PARAM_ERROR, detail);
    }

    /** 表单/普通对象绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.PARAM_ERROR, detail);
    }

    /** 方法参数上的 @Validated 约束失败（如 @Min、@NotNull 直接标在参数上）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.PARAM_ERROR, detail);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public Result<Void> handleBadRequest(Exception e) {
        log.warn("请求解析失败: {}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR, "请求参数格式不正确");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED, e.getMessage());
    }

    /**
     * 唯一键冲突。
     *
     * <p>在本项目里 DuplicateKeyException 大多是【预期内】的：
     * 秒杀订单撞 uk_user_activity / uk_message_id / uk_order_no，
     * 说明这是一次重复请求。但也可能是 idempotent 之外的 bug，
     * 所以交给上层决定语义——这里只做兜底翻译。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMostSpecificCause().getMessage());
        return Result.fail(ResultCode.ORDER_STATUS_CONFLICT, "重复提交，请勿多次点击");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR, e.getMessage());
    }

    /**
     * 兜底。
     *
     * <p>注意这里不把 e.getMessage() 返回给前端——异常细节可能包含
     * SQL 片段、内网地址等敏感信息，是典型的越权信息泄露入口。
     * 调用方只拿到一个 requestId，靠它去日志里捞完整堆栈。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("未处理异常 uri={} method={}", request.getRequestURI(), request.getMethod(), e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
