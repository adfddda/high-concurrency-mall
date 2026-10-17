package com.lxt.mall.auth;

import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.context.UserContext;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.config.MallProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员鉴权拦截器（只拦 {@code /api/admin/**}）。
 *
 * <h3>它补的是一个真实的越权漏洞</h3>
 * 原实现里「登录拦截器」只验证了"你是谁"，没有验证"你能不能"。
 * 于是任何注册用户只要拿到 token，就能调用：
 * <pre>
 *   POST /api/admin/seckill/reconcile   → 触发库存对账并自动修复（可改写 Redis 库存）
 *   POST /api/seckill/activities/{id}/preheat → 覆盖预热快照
 *   POST /api/admin/cache/product/{id}/evict  → 清缓存
 * </pre>
 * 这些不是"看看数据"的只读接口，而是能改变系统状态的运维操作。
 * 一个普通账号能把库存改错，属于典型的权限设计缺失。
 *
 * <h3>为什么放在拦截器而不是每个方法上加注解</h3>
 * 拦截器按路径统一生效，新增 admin 接口时<b>默认受保护</b>；
 * 注解式鉴权一旦漏写，默认就是裸奔。安全默认值必须站在安全的一侧。
 *
 * <h3>为什么用配置白名单而不是 RBAC</h3>
 * 见 {@link MallProperties.Admin} 的注释：演示项目引入完整 RBAC 是过度设计，
 * 但"任何人都能调"是不可接受的。用一个显式的、可配置的边界把洞堵上，
 * 同时在文档里写清楚生产环境该怎么做。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final MallProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String username = UserContext.getUsername();
        if (username == null || !properties.getAdmin().getUsernames().contains(username)) {
            log.warn("拒绝非管理员访问运维接口 user={} uri={}", username, request.getRequestURI());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问运维接口");
        }
        return true;
    }
}
