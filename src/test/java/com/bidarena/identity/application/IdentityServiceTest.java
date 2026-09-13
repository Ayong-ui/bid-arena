package com.bidarena.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.bootstrap.Services;
import com.bidarena.identity.domain.Principal;
import com.bidarena.identity.domain.User;
import com.bidarena.identity.domain.UserRole;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.support.Fixtures;
import com.bidarena.support.TestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 登录与令牌的集成测试（真实 MySQL + 真实 BCrypt + 真实 JWT 签名）。
 *
 * <p>全部走生产接线得到的 {@link IdentityService}，不 mock 掉哈希或令牌：
 * P2 要证明的正是"文档里的演示口令能登进系统，并且拿到的令牌能过过滤器"，
 * 把这两步替成假实现就等于没测。
 */
class IdentityServiceTest {

    private static final String PASSWORD = "Test123456!";

    private DataSource ds;
    private Services services;

    @BeforeEach
    void setUp() {
        ds = TestDatabase.dataSource();
        TestDatabase.wipe();
        services = Fixtures.services(ds);
        Fixtures.userWithPassword(ds, "usr_a", "a@test.local", PASSWORD, "BIDDER", 500);
        Fixtures.userWithPassword(ds, "usr_admin", "admin@test.local", PASSWORD, "ADMIN", 500);
    }

    @Test
    @DisplayName("登录成功：返回可验证的令牌，身份与角色正确")
    void loginIssuesVerifiableToken() {
        IdentityService.LoginResult result = services.identity.login("a@test.local", PASSWORD);

        assertEquals("usr_a", result.user().id());
        assertEquals(UserRole.BIDDER, result.user().role());
        assertTrue(result.expiresAt().isAfter(java.time.Instant.now()));

        Principal principal = services.tokens.verify(result.accessToken());
        assertEquals("usr_a", principal.userId());
        assertEquals(UserRole.BIDDER, principal.role());
        assertFalse(principal.isAdmin());
    }

    @Test
    @DisplayName("管理员的令牌能过管理员校验")
    void adminTokenCarriesAdminRole() {
        IdentityService.LoginResult result = services.identity.login("admin@test.local", PASSWORD);
        assertTrue(services.tokens.verify(result.accessToken()).isAdmin());
    }

    @Test
    @DisplayName("密码错误、邮箱不存在、账号被禁用：三种失败完全不可区分（防账号枚举）")
    void failuresAreIndistinguishable() {
        Fixtures.setUserStatus(ds, "usr_a", "DISABLED");

        BizException wrongPassword =
                assertThrows(BizException.class, () -> services.identity.login("a@test.local", "Wrong123456!"));
        BizException disabled =
                assertThrows(BizException.class, () -> services.identity.login("a@test.local", PASSWORD));
        BizException unknownEmail =
                assertThrows(BizException.class, () -> services.identity.login("nobody@test.local", PASSWORD));

        for (BizException e : new BizException[] {wrongPassword, disabled, unknownEmail}) {
            assertEquals(ErrorCode.UNAUTHENTICATED, e.code());
        }
        // 消息也必须一致：只要有一处不同，攻击者就能拿它当"这个邮箱存在吗"的探针。
        assertEquals(wrongPassword.getMessage(), disabled.getMessage());
        assertEquals(wrongPassword.getMessage(), unknownEmail.getMessage());
    }

    @Test
    @DisplayName("空参数是 400 而不是 401：调用方写错了，不是凭据错了")
    void blankCredentialsAreValidationErrors() {
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BizException.class, () -> services.identity.login("  ", PASSWORD)).code());
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BizException.class, () -> services.identity.login("a@test.local", "")).code());
    }

    @Test
    @DisplayName("被篡改的令牌一律 401，且不区分是签名不对还是过期")
    void tamperedTokensAreRejected() {
        String token = services.identity.login("a@test.local", PASSWORD).accessToken();

        // 只改签名段的最后一个字符：载荷与头部都完好，唯一的差别就是签名。
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertEquals(ErrorCode.UNAUTHENTICATED,
                assertThrows(BizException.class, () -> services.tokens.verify(tampered)).code());

        assertEquals(ErrorCode.UNAUTHENTICATED,
                assertThrows(BizException.class, () -> services.tokens.verify("not-a-jwt")).code());
        assertEquals(ErrorCode.UNAUTHENTICATED,
                assertThrows(BizException.class, () -> services.tokens.verify("")).code());
    }

    @Test
    @DisplayName("换一把密钥签发的令牌不被接受（密钥确实参与校验）")
    void tokenFromAnotherSecretIsRejected() {
        String foreign = Services.wire(ds, "another-secret-that-is-long-enough-0123456789", 3600L)
                .tokens.issue(new User("usr_a", "a@test.local", "a", UserRole.BIDDER,
                        com.bidarena.identity.domain.UserStatus.ACTIVE))
                .token();

        assertEquals(ErrorCode.UNAUTHENTICATED,
                assertThrows(BizException.class, () -> services.tokens.verify(foreign)).code());
    }

    @Test
    @DisplayName("过期的令牌被拒绝")
    void expiredTokenIsRejected() throws InterruptedException {
        // TTL=1 秒，等它过期。用真实时间而不是伪造时钟：这里要验证的是
        // "jjwt 确实按 exp 判定"，伪造时钟会把这一层换掉。
        Services shortLived = Services.wire(ds, "short-lived-secret-that-is-long-enough-01", 1L);
        String token = shortLived.identity.login("a@test.local", PASSWORD).accessToken();
        assertEquals("usr_a", shortLived.tokens.verify(token).userId());

        Thread.sleep(1500);
        assertEquals(ErrorCode.UNAUTHENTICATED,
                assertThrows(BizException.class, () -> shortLived.tokens.verify(token)).code());
    }

    @Test
    @DisplayName("密钥不足 32 字节时在构造期就失败，而不是等到第一次登录")
    void shortSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> Services.wire(ds, "too-short", 3600L));
    }

    @Test
    @DisplayName("令牌有效但用户不存在：404 而不是 401（否则客户端会陷入重登循环）")
    void missingUserIsNotFound() {
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(BizException.class, () -> services.identity.requireUser("usr_ghost")).code());
    }

    @Test
    @DisplayName("登录不返回密码哈希，返回的用户对象里也没有哈希")
    void passwordHashNeverLeavesTheRepository() {
        User user = services.identity.login("a@test.local", PASSWORD).user();
        // User 是 record，用 toString 检查最直接：只要有人往字段里加了哈希，这条断言就会失败。
        assertFalse(user.toString().contains("$2a$"), "User 不应包含 BCrypt 哈希：" + user);
        assertNotEquals(PASSWORD, user.toString());
    }
}
