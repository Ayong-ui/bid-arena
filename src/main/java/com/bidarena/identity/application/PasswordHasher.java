package com.bidarena.identity.application;

/**
 * 密码哈希校验。
 *
 * <p>抽象成接口而不是在服务里直接调 {@code BCrypt.checkpw}，是为了让
 * {@code IdentityService} 的登录分支（用户不存在 / 已禁用 / 密码错误 → 同一个 401）
 * 能在纯单元测试里断言，不必为"验证一次拒绝"去准备真实的 BCrypt 哈希。
 *
 * <p>只有 {@code matches} 而没有 {@code hash}：本项目的用户由迁移脚本播种，
 * 没有注册接口，因此"生成哈希"不是运行时能力，不放进端口。
 */
public interface PasswordHasher {

    boolean matches(String rawPassword, String storedHash);
}
