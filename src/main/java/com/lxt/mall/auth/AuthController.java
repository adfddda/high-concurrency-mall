package com.lxt.mall.auth;

import com.lxt.mall.auth.dto.LoginRequest;
import com.lxt.mall.auth.dto.LoginResponse;
import com.lxt.mall.auth.dto.RegisterRequest;
import com.lxt.mall.auth.dto.UserVO;
import com.lxt.mall.common.api.Result;
import com.lxt.mall.common.enums.RateLimitDimension;
import com.lxt.mall.ratelimit.RateLimit;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /**
     * 登录。
     *
     * <p>加令牌桶限流：登录是典型的"必须限制尝试次数"的接口——
     * 不限流的话，攻击者可以用一个字典把用户密码暴力跑一遍。
     * 用令牌桶而不是滑动窗口，是因为正常用户偶尔连点几下不该被拒，
     * 但持续高速尝试必须被压住。生产环境还应叠加"按用户名锁定"策略，
     * 只靠 IP 维度挡不住 IP 池。
     */
    @PostMapping("/login")
    @RateLimit(rule = "login", dimensions = {RateLimitDimension.IP})
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String token = authorization;
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }
        authService.logout(token);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.currentUser());
    }
}
