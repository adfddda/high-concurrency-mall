package com.lxt.mall.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.auth.dto.LoginRequest;
import com.lxt.mall.auth.dto.LoginResponse;
import com.lxt.mall.auth.dto.RegisterRequest;
import com.lxt.mall.auth.dto.UserVO;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.context.UserContext;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.User;
import com.lxt.mall.domain.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 认证服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final TokenBlacklist tokenBlacklist;
    private final MallProperties properties;

    public UserVO register(RegisterRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        // BCrypt 自带随机盐，同一个密码每次加密结果都不同，无法被彩虹表命中
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname())
                ? request.getNickname() : request.getUsername());
        user.setPhone(StringUtils.hasText(request.getPhone()) ? request.getPhone() : null);
        user.setStatus(1);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 靠唯一索引而不是"先查再插"：
            // 两个并发注册请求同时通过"用户名不存在"的检查是必然发生的，
            // 唯一索引是唯一能在并发下兜住的方案。
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
        return toVO(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, request.getUsername()));

        // 用户不存在与密码错误返回同一个错误码：
        // 区分开来等于给攻击者一个免费的"用户名枚举"接口。
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        IssuedToken issued = jwtTokenService.issue(user.getId(), user.getUsername());
        return new LoginResponse(
                issued.token(),
                properties.getJwt().getAccessTokenTtl().toSeconds(),
                user.getId(),
                user.getUsername(),
                user.getNickname());
    }

    /**
     * 主动退出：把当前 token 的 jti 写进黑名单。
     *
     * <p>TTL 精确取 token 剩余有效期，而不是配置里的固定值。
     * 这样即使有人反复登录-登出几百万次，黑名单的内存占用也有上界。
     */
    public void logout(String token) {
        if (!StringUtils.hasText(token)) {
            // 没有 token 时用上下文里的 jti 兜底
            UserContext.LoginUser current = UserContext.get();
            if (current != null) {
                tokenBlacklist.blacklist(current.getJti(), properties.getJwt().getAccessTokenTtl());
            }
            return;
        }
        try {
            Claims claims = jwtTokenService.parse(token);
            Duration remaining = jwtTokenService.remainingTtl(claims);
            tokenBlacklist.blacklist(claims.getId(), remaining);
            log.info("用户退出登录 userId={} 黑名单TTL={}s",
                    claims.getSubject(), remaining.toSeconds());
        } catch (Exception e) {
            // token 本来就非法/已过期，"退出"这个诉求已经天然满足了，
            // 不必也不应该报错——否则用户会遇到"退出失败"这种诡异提示。
            log.debug("退出登录时令牌已失效: {}", e.getMessage());
        }
    }

    public UserVO currentUser() {
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return toVO(user);
    }

    private UserVO toVO(User user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getPhone());
    }
}
