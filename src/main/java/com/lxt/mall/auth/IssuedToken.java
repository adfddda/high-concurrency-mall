package com.lxt.mall.auth;

import java.time.Instant;

/**
 * 签发结果。
 *
 * @param token     压缩后的 JWT 字符串
 * @param jti       令牌唯一 ID，退出登录时用它拉黑
 * @param expiresAt 过期时间点
 */
public record IssuedToken(String token, String jti, Instant expiresAt) {
}
