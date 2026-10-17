package com.lxt.mall.auth;

import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.context.UserContext;
import com.lxt.mall.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截器。
 *
 * <p>完整校验链：<b>取令牌 → 验签 & 判过期 → 查黑名单 → 写入上下文</b>。
 * 任何一步失败都抛 {@link BusinessException}(UNAUTHORIZED)，
 * 由全局异常处理器翻译成 40100，绝不返回"匿名身份"让请求继续往下走——
 * 后者会在 Service 层退化成 NPE 或者更糟的"越权访问他人订单"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final TokenBlacklist tokenBlacklist;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "缺少访问令牌");
        }

        Claims claims;
        try {
            claims = jwtTokenService.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            // 不把底层异常信息抛给前端：区分"签名错误"和"已过期"是给攻击者的额外信息
            log.debug("令牌解析失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录状态无效，请重新登录");
        }

        String jti = claims.getId();
        if (tokenBlacklist.isBlacklisted(jti)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        UserContext.set(new UserContext.LoginUser(
                jwtTokenService.userIdOf(claims),
                jwtTokenService.usernameOf(claims),
                jti));
        return true;
    }

    /**
     * 请求结束【必须】清理 ThreadLocal。
     *
     * <p>Tomcat 线程池会复用工作线程，只在 preHandle 里 set 而不清理，
     * 下一个复用同一线程的请求就会带着上一个用户的身份进入业务逻辑——
     * 这是一类真实发生过的越权漏洞。放在 finally 里保证异常路径也会清理。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header)) {
            if (header.startsWith(BEARER_PREFIX)) {
                return header.substring(BEARER_PREFIX.length()).trim();
            }
            return header.trim();
        }
        // 兼容部分前端把 token 放在自定义头里的习惯
        String raw = request.getHeader("token");
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }
}
