package com.lxt.mall.auth;

import com.lxt.mall.config.MallProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Token 黑名单。
 *
 * <p><b>为什么 JWT 还需要黑名单？</b>
 * JWT 是无状态的，签发之后服务端就"忘了"它——这正是它能横向扩展的原因，
 * 但也意味着<b>无法主动让它失效</b>。用户点了退出、账号被盗需要踢下线、
 * 管理员禁用账号，这些场景都必须让已签发的 token 立刻作废。
 *
 * <p><b>为什么 TTL 取 token 剩余有效期，而不是固定值？</b>
 * 黑名单 key 的存在意义只到"这个 token 本来就会过期"为止。
 * 过了 token 的 exp，任何人都不会再拿它来访，key 也就没有用了。
 * 按剩余有效期设置 TTL，黑名单的容量上限被牢牢钉在
 * {@code 登出速率 × token 有效期}，永远不会无限增长——这是这个方案能上生产的关键。
 *
 * <p><b>为什么 key 是 jti 而不是整个 token？</b>
 * JWT 有 200+ 字符，用它当 key 一是浪费内存，二是会把敏感令牌写进 Redis
 * 的 key 空间（慢查询日志、监控里都可能出现）。jti 是我们自己生成的不透明 ID，
 * 不带任何信息。
 *
 * <p><b>成本与替代方案（面试常追问）：</b>
 * 这个方案每个需要鉴权的请求都会多一次 Redis GET。
 * 如果连这一次 GET 都想省，可以改成"白名单"模型：
 * Redis 存 {@code auth:user:jti:{userId}} = 当前有效 jti，登出就 DEL。
 * 同样是 1 次 GET，但天然支持单点登录/踢人，且 key 数量与用户数同阶。
 * 代价是不支持多端同时在线。本项目按简历描述实现黑名单模型，
 * 两种方案的取舍见 docs/02-key-design.md。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    /** 常量占位：避免每个被拉黑的 jti 都存一个 value。 */
    private static final String PLACEHOLDER = "1";

    private final StringRedisTemplate redis;
    private final MallProperties properties;

    /**
     * 拉黑一个令牌。
     *
     * @param jti       令牌唯一 ID
     * @param remaining 令牌剩余有效期；<= 0 时无需拉黑（本来就已经过期）
     */
    public void blacklist(String jti, Duration remaining) {
        if (!properties.getAuth().isBlacklistEnabled() || jti == null) {
            return;
        }
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            return;
        }
        redis.opsForValue().set(key(jti), PLACEHOLDER, remaining);
    }

    public boolean isBlacklisted(String jti) {
        if (!properties.getAuth().isBlacklistEnabled() || jti == null) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(key(jti)));
    }

    private String key(String jti) {
        return properties.getAuth().getBlacklistKeyPrefix() + jti;
    }
}
