package com.bidarena.identity.domain;

/**
 * 用户实体（不含密码哈希）。
 *
 * <p>刻意不把 {@code passwordHash} 放进这个类型：领域代码拿着一个"用户"时，
 * 不应该有办法把哈希序列化出去或不小心写进日志。哈希只存在于
 * {@code identity.persistence.UserRepository.StoredUser} 里，且只在登录校验的一瞬间被使用。
 *
 * @param displayName 展示名；契约里的 {@code User.name} 就是它
 */
public record User(String id, String email, String displayName, UserRole role, UserStatus status) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
