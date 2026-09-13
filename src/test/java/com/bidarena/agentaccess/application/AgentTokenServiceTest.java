package com.bidarena.agentaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.agentaccess.domain.AgentScope;
import com.bidarena.agentaccess.domain.AgentToken;
import com.bidarena.bootstrap.Services;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.support.Fixtures;
import com.bidarena.support.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 凭证生命周期：签发、认证、授权、吊销、限流。
 *
 * <p>这里用<b>真实 MySQL</b>（原文要求关键路径不能全用内存 Mock 顶替：摘要唯一键、
 * 级联删除、外键都在数据库里），唯一被替换的是时钟——因为"过期"必须能确定地发生，
 * 而 {@code Thread.sleep} 既慢又不可靠。替换时钟是安全的：凭证的时间判断只读
 * {@link Clock#instant()}，与"拍卖截止用数据库时间"（D-5）是两条独立的路径。
 */
@DisplayName("Agent 凭证服务")
class AgentTokenServiceTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
    private static final String AUCTION = "auc_agent_1";
    private static final String OTHER_AUCTION = "auc_agent_2";

    private static DataSource ds;
    private static Services services;

    private MutableClock clock;
    private AgentTokenService tokens;

    @BeforeAll
    static void initDatabase() {
        ds = TestDatabase.dataSource();
        services = Fixtures.services(ds);
    }

    @BeforeEach
    void setUp() {
        TestDatabase.wipe();
        Fixtures.user(ds, "usr_agent_owner", 10_000);
        Fixtures.draftAuction(ds, AUCTION, 100, 10, 600);
        Fixtures.draftAuction(ds, OTHER_AUCTION, 100, 10, 600);

        clock = new MutableClock(T0);
        // 用生产同一套仓储与服务，只把时钟换成可控的（见类注释）。
        tokens = new AgentTokenService(services.agentTokenRepo, new AgentRateLimiter(),
                services.auctionQueries::existingIds, clock);
    }

    // ---------------------------- 签发 ----------------------------

    @Test
    @DisplayName("签发：返回明文一次，库里只有摘要")
    void issueStoresHashNotPlaintext() {
        AgentTokenService.Issued issued = tokens.issue(command("评审 Agent", List.of(AUCTION),
                List.of("auction:read", "auction:bid"), T0.plusSeconds(3600), null));

        assertNotNull(issued.token());
        assertTrue(issued.token().length() >= 40, "32 字节随机值的 base64url 至少 43 个字符");
        assertEquals(T0.plusSeconds(3600), issued.expiresAt());
        assertTrue(issued.tokenId().startsWith("agt_"));

        String storedHash = Fixtures.scalarString(ds,
                "SELECT token_hash FROM agent_tokens WHERE token_id = ?", issued.tokenId());
        assertNotNull(storedHash);
        assertNotEquals(issued.token(), storedHash, "明文绝不能入库");
        assertEquals(AgentTokenService.sha256Hex(issued.token()), storedHash, "库里应当是明文的 SHA-256");
        assertEquals(64, storedHash.length(), "SHA-256 十六进制固定 64 字符");
        assertEquals(0, Fixtures.count(ds,
                "SELECT COUNT(*) FROM agent_tokens WHERE token_hash = ?", issued.token()), "明文不得出现在摘要列");
    }

    @Test
    @DisplayName("签发：范围与权限落库，且不含明文")
    void issuePersistsScopesAndAuctions() {
        AgentTokenService.Issued issued = tokens.issue(command("评审 Agent",
                List.of(OTHER_AUCTION, AUCTION, OTHER_AUCTION), List.of("auction:read", "auction:bid"),
                T0.plusSeconds(60), 120));

        AgentToken loaded = services.agentTokenRepo.findByTokenId(issued.tokenId());
        assertNotNull(loaded);
        assertEquals(Set.of(AgentScope.READ, AgentScope.BID), loaded.scopes());
        assertEquals(Set.of(AUCTION, OTHER_AUCTION), loaded.auctionIds(), "重复的拍卖 ID 应当去重");
        assertEquals(120, loaded.rateLimitPerMinute());
        assertEquals("usr_agent_owner", loaded.agentUserId());
        assertFalse(loaded.revoked());
    }

    @Test
    @DisplayName("签发：缺省频率上限为 60")
    void issueDefaultsRateLimit() {
        AgentTokenService.Issued issued = tokens.issue(command("默认上限", List.of(AUCTION),
                List.of("auction:read"), T0.plusSeconds(60), null));

        assertEquals(60, services.agentTokenRepo.findByTokenId(issued.tokenId()).rateLimitPerMinute());
    }

    @Test
    @DisplayName("签发：不写 auctionIds 是合法的，但等于默认拒绝")
    void issueWithoutAuctionsIsAllowedButDeniesAll() {
        AgentTokenService.Issued issued = tokens.issue(command("无范围", null, List.of("auction:read"),
                T0.plusSeconds(60), null));

        AgentToken loaded = services.agentTokenRepo.findByTokenId(issued.tokenId());
        assertEquals(Set.of(), loaded.auctionIds());
        assertFalse(loaded.covers(AUCTION), "空范围不是“全部允许”");
    }

    @Test
    @DisplayName("签发：两次签发的明文与摘要都不同")
    void issueIsNeverDeterministic() {
        AgentTokenService.Issued first = tokens.issue(command("a", List.of(AUCTION), List.of("auction:read"),
                T0.plusSeconds(60), null));
        AgentTokenService.Issued second = tokens.issue(command("b", List.of(AUCTION), List.of("auction:read"),
                T0.plusSeconds(60), null));

        assertNotEquals(first.token(), second.token());
        assertNotEquals(first.tokenId(), second.tokenId());
    }

    // ---------------------------- 签发的参数校验 ----------------------------

    @Test
    @DisplayName("签发：参数非法一律 400，且不留下任何记录")
    void issueRejectsInvalidArguments() {
        assertRejected(null, ErrorCode.VALIDATION_FAILED);
        assertRejected(command(" ", List.of(AUCTION), List.of("auction:read"), T0.plusSeconds(60), null),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("x".repeat(81), List.of(AUCTION), List.of("auction:read"), T0.plusSeconds(60), null),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of(AUCTION), null, T0.plusSeconds(60), null),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of(AUCTION), List.of(), T0.plusSeconds(60), null),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of(AUCTION), List.of("auction:cancel"), T0.plusSeconds(60), null),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of(AUCTION), List.of("auction:read"), null, null),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of(AUCTION), List.of("auction:read"), T0, null),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of(AUCTION), List.of("auction:read"), T0.minusSeconds(1), null),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of(AUCTION), List.of("auction:read"), T0.plusSeconds(60), 0),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of(AUCTION), List.of("auction:read"), T0.plusSeconds(60), 6_001),
                ErrorCode.VALIDATION_FAILED);
        assertRejected(command("n", List.of("auc_不存在"), List.of("auction:read"), T0.plusSeconds(60), null),
                ErrorCode.VALIDATION_FAILED);

        assertEquals(0, Fixtures.count(ds, "SELECT COUNT(*) FROM agent_tokens"), "被拒的签发不得留下记录");
    }

    @Test
    @DisplayName("签发：空白 agentUserId 被拒（Token 必须挂在某个真实身份上）")
    void issueRejectsBlankOwner() {
        BizException error = assertThrows(BizException.class, () -> tokens.issue(
                new AgentTokenService.IssueCommand("n", "  ", List.of(AUCTION), List.of("auction:read"),
                        T0.plusSeconds(60), null)));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.code());
    }

    // ---------------------------- 认证 ----------------------------

    @Test
    @DisplayName("认证：明文能换回身份，乱码一律 401")
    void authenticateWithPlaintext() {
        AgentTokenService.Issued issued = issued();
        clock.advance(Duration.ofSeconds(1));

        AgentToken authenticated = tokens.authenticate(issued.token());
        assertEquals(issued.tokenId(), authenticated.tokenId());
        assertEquals("usr_agent_owner", authenticated.agentUserId());

        assertUnauthenticated(null);
        assertUnauthenticated("");
        assertUnauthenticated("   ");
        assertUnauthenticated("不是我们的 token");
        assertUnauthenticated(issued.token() + "x");
        assertUnauthenticated(issued.token().substring(0, issued.token().length() - 1));
    }

    @Test
    @DisplayName("认证：过期边界——到期前一秒可用，恰好到期即 401")
    void authenticateHonoursExpiryBoundary() {
        AgentTokenService.Issued issued = tokens.issue(command("短命", List.of(AUCTION), List.of("auction:read"),
                T0.plusSeconds(60), null));

        clock.set(T0.plusSeconds(59));
        assertNotNull(tokens.authenticate(issued.token()), "到期前一秒仍应有效");

        clock.set(T0.plusSeconds(60));
        assertThrows(BizException.class, () -> tokens.authenticate(issued.token()), "恰好等于 expiresAt 应视为过期");
    }

    @Test
    @DisplayName("认证：已吊销的 Token 立刻 401")
    void authenticateRejectsRevoked() {
        AgentTokenService.Issued issued = issued();

        tokens.revoke(issued.tokenId());

        BizException error = assertThrows(BizException.class, () -> tokens.authenticate(issued.token()));
        assertEquals(ErrorCode.UNAUTHENTICATED, error.code());
    }

    @Test
    @DisplayName("认证：令牌被截断后不能命中别人的摘要（防前缀匹配）")
    void truncatedTokenDoesNotMatchPrefix() {
        AgentTokenService.Issued issued = issued();

        assertThrows(BizException.class, () -> tokens.authenticate(issued.token().substring(0, 8)));
    }

    // ---------------------------- 吊销 ----------------------------

    @Test
    @DisplayName("吊销幂等：重复吊销返回 200 语义，且首次时间戳不被覆盖")
    void revokeIsIdempotentAndKeepsFirstTimestamp() {
        AgentTokenService.Issued issued = issued();

        clock.set(T0.plusSeconds(10));
        AgentToken first = tokens.revoke(issued.tokenId());
        assertNotNull(first.revokedAt());

        clock.set(T0.plusSeconds(20));
        AgentToken second = tokens.revoke(issued.tokenId());

        assertEquals(first.revokedAt(), second.revokedAt(), "重复吊销不得改写首次吊销时间（审计事实）");
        assertEquals(issued.expiresAt(), second.expiresAt());
    }

    @Test
    @DisplayName("吊销未知 tokenId 是 404，不是静默成功")
    void revokeUnknownIsNotFound() {
        BizException error = assertThrows(BizException.class, () -> tokens.revoke("agt_不存在"));

        assertEquals(ErrorCode.NOT_FOUND, error.code());
    }

    @Test
    @DisplayName("吊销只影响目标 Token，同一持有者的另一枚仍可用")
    void revokeDoesNotAffectSiblingToken() {
        AgentTokenService.Issued target = issued();
        AgentTokenService.Issued sibling = issued();

        tokens.revoke(target.tokenId());

        assertNotNull(tokens.authenticate(sibling.token()));
    }

    // ---------------------------- 授权 ----------------------------

    @Test
    @DisplayName("授权：范围内且有权限则通过")
    void authorizeAllowsWithinScope() {
        AgentToken token = tokens.authenticate(issued().token());

        tokens.authorize(token, AUCTION, AgentScope.BID);
        tokens.authorize(token, AUCTION, AgentScope.READ);
    }

    @Test
    @DisplayName("授权：缺权限是 403（不是 401——凭证是有效的，只是不够）")
    void authorizeRejectsMissingScope() {
        AgentToken token = tokens.authenticate(
                tokens.issue(command("只读", List.of(AUCTION), List.of("auction:read"), T0.plusSeconds(600), null)).token());

        BizException error = assertThrows(BizException.class,
                () -> tokens.authorize(token, AUCTION, AgentScope.BID));
        assertEquals(ErrorCode.FORBIDDEN, error.code());
    }

    @Test
    @DisplayName("授权：拍卖不在范围内是 403，范围外与权限缺失可区分")
    void authorizeRejectsForeignAuction() {
        AgentToken token = tokens.authenticate(
                tokens.issue(command("单场", List.of(AUCTION), List.of("auction:read", "auction:bid"),
                        T0.plusSeconds(600), null)).token());

        BizException error = assertThrows(BizException.class,
                () -> tokens.authorize(token, OTHER_AUCTION, AgentScope.READ));
        assertEquals(ErrorCode.FORBIDDEN, error.code());
        assertTrue(String.valueOf(error.details()).contains(OTHER_AUCTION), "错误里应指明是哪一场越权");
    }

    @Test
    @DisplayName("授权：空范围的 Token 对任何拍卖都是 403")
    void authorizeRejectsEverythingForEmptyScope() {
        AgentToken token = tokens.authenticate(tokens.issue(command("空范围", null,
                List.of("auction:read", "auction:bid"), T0.plusSeconds(600), null)).token());

        for (String auctionId : List.of(AUCTION, OTHER_AUCTION, "auc_任意")) {
            BizException error = assertThrows(BizException.class,
                    () -> tokens.authorize(token, auctionId, AgentScope.READ));
            assertEquals(ErrorCode.FORBIDDEN, error.code(), auctionId);
        }
    }

    @Test
    @DisplayName("授权：过期的 Token 先于权限被拒（401），不泄漏“其实有权”")
    void authorizeRejectsExpiredAsUnauthenticated() {
        AgentToken token = tokens.authenticate(issued().token());
        clock.set(T0.plusSeconds(3601));

        BizException error = assertThrows(BizException.class,
                () -> tokens.authorize(token, AUCTION, AgentScope.BID));
        assertEquals(ErrorCode.UNAUTHENTICATED, error.code());
    }

    // ---------------------------- 限流 ----------------------------

    @Test
    @DisplayName("限流：超过每分钟上限抛 429，窗口滚动后恢复")
    void rateLimitExhaustsAndRecovers() {
        AgentToken token = tokens.authenticate(tokens.issue(command("限流", List.of(AUCTION),
                List.of("auction:read"), T0.plusSeconds(3600), 2)).token());

        tokens.checkRateLimit(token);
        tokens.checkRateLimit(token);

        BizException error = assertThrows(BizException.class, () -> tokens.checkRateLimit(token));
        assertEquals(ErrorCode.RATE_LIMITED, error.code());

        clock.advance(Duration.ofSeconds(60));
        tokens.checkRateLimit(token);
    }

    @Test
    @DisplayName("限流按 Token 独立：一枚打满不影响另一枚")
    void rateLimitIsPerToken() {
        AgentToken first = tokens.authenticate(tokens.issue(command("a", List.of(AUCTION),
                List.of("auction:read"), T0.plusSeconds(3600), 1)).token());
        AgentToken second = tokens.authenticate(tokens.issue(command("b", List.of(AUCTION),
                List.of("auction:read"), T0.plusSeconds(3600), 1)).token());

        tokens.checkRateLimit(first);
        assertThrows(BizException.class, () -> tokens.checkRateLimit(first));

        tokens.checkRateLimit(second);
    }

    // ---------------------------- 辅助 ----------------------------

    private AgentTokenService.Issued issued() {
        return tokens.issue(command("评审 Agent", List.of(AUCTION), List.of("auction:read", "auction:bid"),
                T0.plusSeconds(3600), null));
    }

    private static AgentTokenService.IssueCommand command(String name, List<String> auctionIds,
            List<String> scopes, Instant expiresAt, Integer rateLimitPerMinute) {
        return new AgentTokenService.IssueCommand(name, "usr_agent_owner", auctionIds, scopes, expiresAt,
                rateLimitPerMinute);
    }

    private void assertRejected(AgentTokenService.IssueCommand command, ErrorCode expected) {
        BizException error = assertThrows(BizException.class, () -> tokens.issue(command));
        assertEquals(expected, error.code(), "参数校验的码应当是 VALIDATION_FAILED");
    }

    private void assertUnauthenticated(String presented) {
        BizException error = assertThrows(BizException.class, () -> tokens.authenticate(presented));
        assertEquals(ErrorCode.UNAUTHENTICATED, error.code());
    }

    /**
     * 可控时钟。只有 {@code instant()} 会被被测代码调用；
     * {@code withZone} 不会被用到（生产用的是 UTC 系统时钟），因此保持朴素实现。
     */
    private static final class MutableClock extends Clock {

        private Instant now;
        private final ZoneId zone;

        private MutableClock(Instant now) {
            this(now, ZoneOffset.UTC);
        }

        private MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advance(Duration duration) {
            this.now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requested) {
            return new MutableClock(now, requested);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
