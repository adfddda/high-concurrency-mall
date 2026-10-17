package com.lxt.mall.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户上下文。
 *
 * <p>用 ThreadLocal 而不是把 userId 一层层当参数传：控制器到 Service 到 Mapper
 * 中间隔着好几层，透传 userId 会污染所有方法签名。
 *
 * <p><b>必须成对清理</b>：Tomcat 的工作线程是【池化复用】的，
 * 如果只在进入拦截器时 set、不在 afterCompletion 里 remove，
 * 下一个复用该线程的请求就会读到上一个用户的身份——
 * 这是一个真实发生过多次的越权漏洞。见 {@code LoginInterceptor#afterCompletion}。
 *
 * <p>如果用 {@code @Async} 或自定义线程池，ThreadLocal 不会自动传递，
 * 需要 {@code TaskDecorator} 显式拷贝；本项目里异步重建缓存的任务
 * 刻意不读用户身份，就是为了绕开这个坑。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getUserId();
    }

    public static String getUsername() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getUsername();
    }

    /** 取不到就抛，用于"必须登录"的分支，避免下游出现难以定位的 NPE。 */
    public static Long requireUserId() {
        Long id = getUserId();
        if (id == null) {
            throw new IllegalStateException("当前请求上下文中没有登录用户");
        }
        return id;
    }

    public static void clear() {
        HOLDER.remove();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginUser {
        private Long userId;
        private String username;
        /** token 的 jti，退出登录 / 单点踢人时用来定位要拉黑的那个令牌。 */
        private String jti;
    }
}
