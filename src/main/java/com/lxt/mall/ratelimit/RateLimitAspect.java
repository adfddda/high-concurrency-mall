package com.lxt.mall.ratelimit;

import com.lxt.mall.common.context.UserContext;
import com.lxt.mall.common.enums.RateLimitAlgorithm;
import com.lxt.mall.common.enums.RateLimitDimension;
import com.lxt.mall.common.exception.RateLimitException;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.metrics.MallMetrics;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 限流切面。
 *
 * <p><b>为什么用 AOP 而不是在 Service 里手写限流代码？</b>
 * 限流是典型的横切关注点：它跟业务逻辑零耦合，却需要在十几个入口重复出现。
 * 写进业务方法会带来三个问题——业务代码被稀释、阈值改动要碰业务类、
 * 新增接口时容易漏加。注解 + 切面把"哪些接口、什么维度、多少阈值"变成
 * 一处可审计的声明。
 *
 * <p><b>关键设计：fail-open 而不是 fail-close。</b>
 * Redis 挂了的时候，限流判定会抛异常。此时如果直接拒绝所有请求，
 * 就等于"限流组件故障 → 全站不可用"，把一次局部故障放大成全局事故。
 * 默认策略是记一条 WARN 然后放行，把决定权交还给业务本身的容量
 * （DB 连接池、线程池都有各自的上限，不至于真的被打穿）。
 * 需要强保护的接口可以通过配置把 {@code fail-open} 关掉。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final MallProperties properties;
    private final RedisRateLimiter redisRateLimiter;
    private final MallMetrics metrics;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        MallProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return joinPoint.proceed();
        }

        MallProperties.Rule rule = config.getRules().get(rateLimit.rule());
        if (rule == null) {
            // 配置缺失属于部署问题，必须能被发现；但不要因此拒绝用户请求
            log.error("限流规则未配置，已跳过限流: rule={} method={}",
                    rateLimit.rule(), ((MethodSignature) joinPoint.getSignature()).getMethod().getName());
            return joinPoint.proceed();
        }

        String apiName = resolveApiName(joinPoint);

        try {
            for (RateLimitDimension dimension : rateLimit.dimensions()) {
                String target = resolveTarget(dimension, apiName);
                if (target == null) {
                    // 该维度取不到值（例如未登录用户没有 userId），跳过这一维，
                    // 由其它维度兜底，而不是直接放行或直接拒绝
                    continue;
                }

                boolean allowed = doAcquire(rateLimit.rule(), rule, dimension, target);
                if (!allowed) {
                    metrics.rateLimitRejected(apiName, dimension.name());
                    String message = StringUtils.hasText(rateLimit.message())
                            ? rateLimit.message() : "请求过于频繁，请稍后再试";
                    log.warn("触发限流 api={} dimension={} target={} rule={}",
                            apiName, dimension, target, rateLimit.rule());
                    throw new RateLimitException(message);
                }
            }
            return joinPoint.proceed();
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            if (config.isFailOpen()) {
                log.warn("限流组件异常，降级放行 api={} rule={} cause={}",
                        apiName, rateLimit.rule(), e.getMessage());
                metrics.rateLimitDegraded(apiName);
                return joinPoint.proceed();
            }
            log.error("限流组件异常，按 fail-close 策略拒绝 api={}", apiName, e);
            throw new RateLimitException("服务繁忙，请稍后重试");
        }
    }

    private boolean doAcquire(String ruleName, MallProperties.Rule rule,
                              RateLimitDimension dimension, String target) {
        if (rule.getAlgorithm() == RateLimitAlgorithm.TOKEN_BUCKET) {
            int capacity = rule.getCapacity() != null ? rule.getCapacity() : rule.getLimit();
            double rate = rule.getRate() != null ? rule.getRate() : Math.max(1.0, rule.getLimit() / 10.0);
            return redisRateLimiter.tryAcquireTokenBucket(
                    ruleName, dimension.name(), target, capacity, rate, 1);
        }
        return redisRateLimiter.tryAcquireSlidingWindow(
                ruleName, dimension.name(), target, rule.getLimit(), rule.getWindow());
    }

    /** 维度取值。取不到返回 null，表示该维度对本次请求不适用。 */
    private String resolveTarget(RateLimitDimension dimension, String apiName) {
        return switch (dimension) {
            case GLOBAL -> "all";
            case API -> apiName;
            case USER -> {
                Long userId = UserContext.getUserId();
                yield userId == null ? null : String.valueOf(userId);
            }
            case IP -> clientIp();
        };
    }

    private String resolveApiName(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    }

    /**
     * 取客户端真实 IP。
     *
     * <p>这里是【限流】而不是安全审计，所以按 X-Forwarded-For 的<b>第一个</b>地址取。
     * 注意 XFF 是客户端可伪造的：攻击者可以每次请求换一个假 IP 来绕过按 IP 限流。
     * 真正可信的来源应该是网关/负载均衡写入的、并且应用服务器只信任最后一跳。
     * 也正因如此，本项目所有限流策略里<b>一定会同时开启 USER 维度</b>——
     * 按 IP 限流只能挡住最粗糙的攻击，不能作为唯一的防线。
     */
    private String clientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
