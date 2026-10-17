package com.lxt.mall.auth;

import com.lxt.mall.config.MallProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>几个刻意的设计选择：
 * <ol>
 *   <li><b>只放 userId / username / jti，不放权限和敏感信息</b>。
 *       JWT 的 payload 只是 Base64，不是加密——放手机号、角色列表等于明文泄露；
 *       而且权限一旦写进 token，改权限就必须等 token 过期才生效。</li>
 *   <li><b>必须带 jti</b>。没有 jti 就没法精确拉黑某一个令牌，
 *       主动退出只能退化成"改密钥让全站 token 失效"，代价太大。</li>
 *   <li><b>算法的显式指定</b>（{@code Jwts.SIG.HS256}）。
 *       JWT 最经典的漏洞是 alg 混淆攻击：攻击者把 header 改成
 *       {@code {"alg":"none"}}，某些库的实现会直接跳过验签。
 *       解析侧用 {@code verifyWith(key)} 固定密钥，签发侧固定算法，
 *       两端都不接受 header 里自称的算法。</li>
 *   <li><b>校验 issuer 与时钟偏移</b>。issuer 防止别的系统用同一个 secret
 *       签出来的 token 混进来；时钟偏移（默认 30s）避免多实例部署时
 *       机器时间差几秒就导致 token 被误判过期。</li>
 * </ol>
 */
@Slf4j
@Component
public class JwtTokenService {

    public static final String CLAIM_USERNAME = "username";

    private final MallProperties.Jwt props;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtTokenService(MallProperties properties) {
        this.props = properties.getJwt();
        String secret = this.props.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "mall.jwt.secret 长度不足：HS256 要求密钥至少 256 位（32 字节），"
                            + "当前 secret 不满足要求，请通过环境变量 JWT_SECRET 注入");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(this.props.getIssuer())
                .clockSkewSeconds(this.props.getClockSkew().toSeconds())
                .build();
    }

    /** 签发 access token。 */
    public IssuedToken issue(Long userId, String username) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.getAccessTokenTtl());
        String jti = UUID.randomUUID().toString().replace("-", "");

        String token = Jwts.builder()
                .issuer(props.getIssuer())
                .subject(String.valueOf(userId))
                .id(jti)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, jti, exp);
    }

    /**
     * 解析并验签。
     *
     * @throws JwtException 签名错误、已过期、issuer 不匹配等一切非法情况
     */
    public Claims parse(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    /** token 剩余有效期。用于给黑名单 key 设置精确的 TTL。 */
    public Duration remainingTtl(Claims claims) {
        Date exp = claims.getExpiration();
        if (exp == null) {
            return Duration.ZERO;
        }
        Duration d = Duration.between(Instant.now(), exp.toInstant());
        return d.isNegative() ? Duration.ZERO : d;
    }

    public Long userIdOf(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String usernameOf(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }
}
