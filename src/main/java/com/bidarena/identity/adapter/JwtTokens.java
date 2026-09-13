package com.bidarena.identity.adapter;

import com.bidarena.identity.application.TokenService;
import com.bidarena.identity.domain.Principal;
import com.bidarena.identity.domain.User;
import com.bidarena.identity.domain.UserRole;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/**
 * 基于 HMAC-SHA 的 JWT 实现（jjwt）。
 *
 * <h2>密钥长度是硬约束，不是建议</h2>
 * {@code HMAC-SHA256} 的密钥短于 256 位时，jjwt 会拒绝签名（{@code WeakKeyException}）。
 * 这里在构造时就检查并抛出，把失败提前到**启动**：若等到第一次登录才炸，
 * 配置错误就会被推迟到演示现场，且症状是"登录 500"而看不出是密钥太短。
 *
 * <h2>令牌里放什么</h2>
 * 只放 {@code sub}（用户 ID）与 {@code role}。不放邮箱、余额、昵称：
 * JWT 的 payload 是 Base64 编码而非加密，任何人都能读；把非必要信息放进去等于泄漏。
 * 更关键的是，放进去的余额会随令牌一起"冻结"到过期为止，客户端读到的是旧值。
 */
public class JwtTokens implements TokenService {

    /** 契约里 {@code User.role} 的取值。 */
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long ttlSeconds;

    /**
     * @param secret 至少 32 字节的随机串；来自环境变量 {@code JWT_SECRET}
     * @param ttlSeconds 令牌有效期；来自 {@code JWT_TTL_SECONDS}，默认 8 小时
     */
    public JwtTokens(String secret, long ttlSeconds) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少配置 JWT_SECRET。请从 .env.example 复制 .env 并导出环境变量。");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET 至少需要 32 字节（当前 " + bytes.length + "）。生成：openssl rand -base64 48");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalStateException("JWT_TTL_SECONDS 必须为正数，当前 " + ttlSeconds);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public IssuedToken issue(User user) {
        // 用 JVM 时钟：令牌生命周期不是业务截止时间，不参与资金判断（见 IdentityService 注释）。
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String token = Jwts.builder()
                .subject(user.id())
                .claim(CLAIM_ROLE, user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    @Override
    public Principal verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new Principal(claims.getSubject(), UserRole.parse(claims.get(CLAIM_ROLE, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名不符、格式错误在这里被合并成同一种对外结论。
            // 区分它们只会帮攻击者判断"签名对不对"或"这个令牌是不是只是过期了"。
            throw new BizException(ErrorCode.UNAUTHENTICATED, "令牌无效或已过期");
        }
    }
}
