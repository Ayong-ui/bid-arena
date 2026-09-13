package com.bidarena.identity.application;

import com.bidarena.identity.domain.Principal;
import com.bidarena.identity.domain.User;
import java.time.Instant;

/**
 * 会话令牌的签发与校验。
 *
 * <p>签发与校验放在同一个端口里，是因为它们共享**同一个密钥**：
 * 拆成两个接口后，接线时可能给签发方和校验方配不同的密钥，
 * 这个错误只会在"登录成功但立刻 401"时才暴露，且症状离原因很远。
 */
public interface TokenService {

    IssuedToken issue(User user);

    /**
     * 校验令牌并取出身份。
     *
     * @throws com.bidarena.shared.BizException {@code UNAUTHENTICATED}：格式错误、签名不符或已过期
     */
    Principal verify(String token);

    record IssuedToken(String token, Instant expiresAt) {}
}
