package com.bidarena.identity.application;

import com.bidarena.identity.adapter.UserRepository;
import com.bidarena.identity.adapter.UserRepository.StoredUser;
import com.bidarena.identity.domain.User;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.time.Instant;
import java.util.Map;

/**
 * 登录与当前用户查询。
 *
 * <h2>登录失败一律返回同一个 401</h2>
 * "邮箱不存在"与"密码错误"必须不可区分。若分别返回不同信息（或不同 code），
 * 登录接口就变成一个**账号枚举器**：攻击者可以先用它列出现有邮箱，再针对性撞库。
 * 因此三种失败（不存在 / 已禁用 / 密码不符）走同一条拒绝路径。
 *
 * <h2>时间来源</h2>
 * 令牌过期时间用 JVM 时钟（{@link Instant#now()}）而不是数据库时间。
 * 这是刻意的：数据库时间是**业务规则**（出价截止）的基准，D-5 要求它不能漂；
 * 而令牌有效期是**凭据生命周期**，由签发方与校验方各自的时钟决定，不参与任何资金判断。
 */
public class IdentityService {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final TokenService tokens;

    public IdentityService(UserRepository users, PasswordHasher hasher, TokenService tokens) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
    }

    public record LoginResult(User user, String accessToken, Instant expiresAt) {}

    public LoginResult login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "email 与 password 不能为空");
        }

        StoredUser stored = users.findByEmail(email.trim());
        if (stored == null || !stored.isActive() || !hasher.matches(password, stored.passwordHash())) {
            // 不区分具体原因，见类注释。这里也不审计"是谁尝试登录谁"：
            // 把尝试的邮箱写进日志等于把枚举结果落到日志里。
            throw new BizException(ErrorCode.UNAUTHENTICATED, "邮箱或密码错误");
        }

        TokenService.IssuedToken issued = tokens.issue(stored.toUser());
        return new LoginResult(stored.toUser(), issued.token(), issued.expiresAt());
    }

    /**
     * 按 ID 取用户，供 {@code GET /users/me} 使用。
     *
     * <p>令牌有效但用户已被删除时返回 404 而不是 401：令牌本身没问题，
     * 是它指向的资源不存在了。若返回 401，客户端会陷入"重新登录 → 仍然 401"的死循环。
     *
     * <p>这里**不**校验 {@code status}：仅把用户置为 DISABLED 不应让已签发的令牌立刻失效
     * （JWT 的已知取舍，见 D-3）；禁用只在登录那一刻起作用。真正的强制登出需要黑名单，本项目不做。
     */
    public User requireUser(String userId) {
        StoredUser stored = users.findById(userId);
        if (stored == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在", Map.of("userId", userId));
        }
        return stored.toUser();
    }
}
