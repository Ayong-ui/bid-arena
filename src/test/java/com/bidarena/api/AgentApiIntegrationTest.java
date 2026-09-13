package com.bidarena.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.support.ApiTestHarness;
import com.bidarena.support.Fixtures;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 竞拍 Agent 的端到端契约：签发 → 认证 → 读取 → 出价 → 结果。
 *
 * <h2>为什么必须走真实 HTTP 而不是直接调服务</h2>
 * P5 的验收项是"独立的认证凭据与最小权限边界"。边界存在于**传输层**：
 * 哪个端口、哪个过滤器、哪种凭证。直接调 {@code AgentAuctionService} 只能证明
 * "授权判断的实现是对的"，证明不了"用用户 JWT 调 Agent 接口会被挡住"、
 * "Agent 端口上不存在管理接口"这类真正被要求的东西。
 *
 * <h2>为什么 Agent 请求要打到另一个端口</h2>
 * 因为那是 P5 的部署事实（见 {@code bootstrap.AgentApiPlugin}）。
 * 用 {@link ApiTestHarness#agentCall} 而不是 {@code call} 是刻意的：
 * 若 Agent 只有在 8080 上才通，用例应当失败，而不是悄悄通过。
 */
@DisplayName("Agent API 端到端")
class AgentApiIntegrationTest extends ApiTestHarness {

    private static final String AGENT_OWNER = BIDDER_A_ID;

    /**
     * 明文 → tokenId。
     *
     * <p>签发时服务端把 tokenId 一并返回，这里把它记下来：吊销接口用的是 tokenId，
     * 而测试手里只有明文。**刻意不在测试里复算 tokenId 的生成规则**——那是服务端实现细节，
     * 复算等于在测试里养一份需要同步维护的影子实现。JUnit 每个用例新建一次实例，不会串台。
     */
    private final Map<String, String> issuedTokenIds = new HashMap<>();

    // ---------------------------------------------------------------- 读

    @Test
    @DisplayName("签发 Token 后，Agent 能读到权威快照")
    void agentReadsSnapshot() {
        String auctionId = createRunningAuction("Agent 读取", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read", "auction:bid"));

        Resp resp = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, token, null);

        assertEquals(200, resp.code(), resp.raw());
        assertCode("OK", resp);
        assertEquals(auctionId, resp.data("id"));
        assertEquals(100, resp.dataLong("currentPrice"));
        assertFalse(resp.data("serverTime").isEmpty(), "服务端时间必须回传，客户端据此算倒计时");
    }

    @Test
    @DisplayName("Agent 读的是服务端事实：真人出价后 Agent 立刻看到新价格")
    void agentSeesAuthoritativePriceAfterHumanBid() {
        String auctionId = createRunningAuction("Agent 与真人共读", 100, 10, 600);
        String agentToken = issueToken(auctionId, List.of("auction:read"));
        join(auctionId, bidderToken());
        assertEquals(200, placeBid(auctionId, bidderToken(), "human-1", 150).code());

        Resp resp = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, agentToken, null);

        assertEquals(150, resp.dataLong("currentPrice"));
        assertEquals(BIDDER_A_ID, resp.data("leader"));
    }

    @Test
    @DisplayName("拍卖尚未结算时读结果返回 404；取消后能读到结果")
    void agentReadsResultAfterCancel() {
        String auctionId = createRunningAuction("Agent 读结果", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read"));

        Resp pending = agentCall("GET", "/api/v1/agent/auctions/" + auctionId + "/result", token, null);
        assertEquals(404, pending.code(), pending.raw());
        assertCode("NOT_FOUND", pending);

        Resp cancelled = call("POST", "/api/v1/admin/auctions/" + auctionId + "/cancel", adminToken(), null);
        assertEquals(200, cancelled.code(), cancelled.raw());

        Resp result = agentCall("GET", "/api/v1/agent/auctions/" + auctionId + "/result", token, null);
        assertEquals(200, result.code(), result.raw());
        assertEquals("CANCELLED", result.data("status"));
        assertTrue(result.body().path("data").path("winner").isMissingNode()
                        || result.body().at("/data/winner").isNull(),
                "取消的拍卖没有赢家：" + result.raw());
    }

    // ---------------------------------------------------------------- 出价

    @Test
    @DisplayName("Agent 出价成功，并以 AGENT 类型自动加入拍卖间")
    void agentBidAutoJoinsAsAgent() {
        String auctionId = createRunningAuction("Agent 出价", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read", "auction:bid"));

        Resp resp = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("requestId", "agent-r1", "amount", 110));

        assertEquals(200, resp.code(), resp.raw());
        assertCode("OK", resp);
        assertTrue(resp.body().at("/data/accepted").asBoolean());
        assertEquals(110, resp.dataLong("price"));
        assertEquals(AGENT_OWNER, resp.data("leader"));

        // 契约里没有 Agent 的 join 接口，因此参与记录必须在出价事务里补上（D-30）。
        assertEquals("AGENT", Fixtures.scalarString(ds,
                "SELECT participant_type FROM auction_participants WHERE auction_id = ? AND user_id = ?",
                auctionId, AGENT_OWNER));
        // 出价落到与真人同一条路径上：价格、冻结、出价行都必须一致。
        assertEquals(110, Fixtures.currentPrice(ds, auctionId));
        assertEquals(110, Fixtures.frozen(ds, AGENT_OWNER));
        assertEquals(1, Fixtures.count(ds, "SELECT COUNT(*) FROM bids WHERE auction_id = ?", auctionId));
    }

    @Test
    @DisplayName("Agent 出价与真人出价互相同步：Agent 加价会释放真人的冻结")
    void agentBidReleasesPreviousLeaderFreeze() {
        String auctionId = createRunningAuction("Agent 加价", 100, 10, 600);
        // 真人用 bidder_b：Agent Token 挂在 bidder_a 上，两个不同的人才能看清“超越”这件事。
        String human = bidderTokenB();
        join(auctionId, human);
        assertEquals(200, placeBid(auctionId, human, "human-1", 110).code());
        String token = issueToken(auctionId, List.of("auction:bid"));

        Resp resp = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("requestId", "agent-r2", "amount", 130));

        assertEquals(200, resp.code(), resp.raw());
        assertEquals(130, Fixtures.currentPrice(ds, auctionId));
        assertEquals(130, Fixtures.frozen(ds, AGENT_OWNER));
        assertEquals(0, Fixtures.frozen(ds, BIDDER_B_ID), "被超越的真人出价应当被释放");
        assertEquals(130, Fixtures.auctionFrozen(ds, auctionId), "本场冻结总额等于当前最高价（INV-2）");
    }

    @Test
    @DisplayName("同一个 requestId 重放：返回首次结果且不重复出价")
    void agentBidIsIdempotent() {
        String auctionId = createRunningAuction("Agent 幂等", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:bid"));

        Resp first = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("requestId", "same-key", "amount", 110));
        Resp replay = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("requestId", "same-key", "amount", 110));

        assertEquals(200, first.code(), first.raw());
        assertCode("OK", first);
        assertEquals(200, replay.code(), replay.raw());
        assertCode("IDEMPOTENCY_REPLAY", replay);
        assertEquals(first.dataLong("price"), replay.dataLong("price"));
        assertEquals(first.dataLong("seq"), replay.dataLong("seq"));
        assertEquals(1, Fixtures.count(ds, "SELECT COUNT(*) FROM bids WHERE auction_id = ?", auctionId));
        assertEquals(110, Fixtures.frozen(ds, AGENT_OWNER), "重放不得再次冻结");
    }

    @Test
    @DisplayName("幂等键可以只放在 Idempotency-Key 头；两处不一致则 400")
    void agentBidAcceptsHeaderIdempotencyKey() {
        String auctionId = createRunningAuction("Agent 幂等头", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:bid"));

        Resp fromHeader = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("amount", 110), "Idempotency-Key", "header-key");
        assertEquals(200, fromHeader.code(), fromHeader.raw());
        assertCode("OK", fromHeader);

        Resp mismatch = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("requestId", "body-key", "amount", 120), "Idempotency-Key", "other-key");
        assertEquals(400, mismatch.code(), mismatch.raw());
        assertCode("VALIDATION_FAILED", mismatch);

        Resp missing = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("amount", 130));
        assertEquals(400, missing.code(), missing.raw());
        assertCode("VALIDATION_FAILED", missing);
    }

    @Test
    @DisplayName("业务规则仍归出价事务：出价过低返回 BID_TOO_LOW，不是静默接受")
    void agentBidStillSubjectToBusinessRules() {
        String auctionId = createRunningAuction("Agent 低价", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:bid"));

        Resp resp = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("requestId", "low-1", "amount", 105));

        assertEquals(409, resp.code(), resp.raw());
        assertCode("BID_TOO_LOW", resp);
        assertEquals("100", resp.data("currentPrice"));
    }

    @Test
    @DisplayName("Agent 用的是 Token 所属用户的钱：余额不足时 INSUFFICIENT_BALANCE")
    void agentBidUsesTokenOwnerWallet() {
        String auctionId = createRunningAuction("Agent 余额", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:bid"));

        Resp resp = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("requestId", "rich-1", "amount", 50_000));

        assertEquals(409, resp.code(), resp.raw());
        assertCode("INSUFFICIENT_BALANCE", resp);
        assertEquals(0, Fixtures.frozen(ds, AGENT_OWNER));
        assertEquals(0, Fixtures.count(ds, "SELECT COUNT(*) FROM auction_participants WHERE auction_id = ?",
                auctionId), "被拒的出价不应留下参与记录（自动加入与出价同一事务）");
    }

    // ---------------------------------------------------------------- 最小权限边界

    @Test
    @DisplayName("缺权限：只有 auction:read 的 Token 出价被拒（403）")
    void readOnlyTokenCannotBid() {
        String auctionId = createRunningAuction("只读 Token", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read"));

        Resp resp = agentCall("POST", "/api/v1/agent/auctions/" + auctionId + "/bids", token,
                json("requestId", "nope", "amount", 110));

        assertEquals(403, resp.code(), resp.raw());
        assertCode("FORBIDDEN", resp);
        assertEquals(0, Fixtures.count(ds, "SELECT COUNT(*) FROM bids WHERE auction_id = ?", auctionId));
    }

    @Test
    @DisplayName("越界：范围只含 A 的 Token 读 / 出价 B 都是 403")
    void tokenScopedToOtherAuctionIsForbidden() {
        String allowed = createRunningAuction("允许的场", 100, 10, 600);
        String forbidden = createRunningAuction("禁止的场", 100, 10, 600);
        String token = issueToken(allowed, List.of("auction:read", "auction:bid"));

        Resp read = agentCall("GET", "/api/v1/agent/auctions/" + forbidden, token, null);
        assertEquals(403, read.code(), read.raw());
        assertCode("FORBIDDEN", read);

        Resp bid = agentCall("POST", "/api/v1/agent/auctions/" + forbidden + "/bids", token,
                json("requestId", "out-of-scope", "amount", 110));
        assertEquals(403, bid.code(), bid.raw());
        assertCode("FORBIDDEN", bid);
        assertEquals(0, Fixtures.count(ds, "SELECT COUNT(*) FROM bids WHERE auction_id = ?", forbidden));

        Resp stillAllowed = agentCall("GET", "/api/v1/agent/auctions/" + allowed, token, null);
        assertEquals(200, stillAllowed.code(), stillAllowed.raw());
    }

    @Test
    @DisplayName("范围前缀相近的拍卖不会互相命中（auc 与 auc10）")
    void scopeIsNotPrefixMatched() {
        String auctionId = createRunningAuction("前缀陷阱", 100, 10, 600);
        String decoy = auctionId + "0";
        Fixtures.draftAuction(ds, decoy, 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read"));

        Resp resp = agentCall("GET", "/api/v1/agent/auctions/" + decoy, token, null);

        assertEquals(403, resp.code(), resp.raw());
        assertCode("FORBIDDEN", resp);
    }

    @Test
    @DisplayName("过期的 Token 立即 401")
    void expiredTokenIsRejected() throws InterruptedException {
        String auctionId = createRunningAuction("过期 Token", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read"), Instant.now().plusSeconds(1), null);

        // 刚好在有效期内可用。
        assertEquals(200, agentCall("GET", "/api/v1/agent/auctions/" + auctionId, token, null).code());

        Thread.sleep(1200);

        Resp resp = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, token, null);
        assertEquals(401, resp.code(), resp.raw());
        assertCode("UNAUTHENTICATED", resp);
    }

    @Test
    @DisplayName("吊销后立即 401；重复吊销幂等；未知 tokenId 是 404")
    void revokedTokenIsRejected() {
        String auctionId = createRunningAuction("吊销 Token", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read", "auction:bid"));

        Resp revoked = call("POST", "/api/v1/admin/agent-tokens/" + tokenIdOf(token) + "/revoke", adminToken(), null);
        assertEquals(200, revoked.code(), revoked.raw());
        assertCode("OK", revoked);
        assertTrue(revoked.body().path("data").path("token").isMissingNode()
                        || revoked.body().at("/data/token").isNull(),
                "吊销响应不得回显明文 Token：" + revoked.raw());

        Resp afterRevoke = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, token, null);
        assertEquals(401, afterRevoke.code(), afterRevoke.raw());
        assertCode("UNAUTHENTICATED", afterRevoke);

        Resp again = call("POST", "/api/v1/admin/agent-tokens/" + tokenIdOf(token) + "/revoke", adminToken(), null);
        assertEquals(200, again.code(), again.raw());
        assertCode("OK", again);

        Resp unknown = call("POST", "/api/v1/admin/agent-tokens/agt_missing/revoke", adminToken(), null);
        assertEquals(404, unknown.code(), unknown.raw());
        assertCode("NOT_FOUND", unknown);
    }

    @Test
    @DisplayName("缺 Token / 乱码 Token 一律 401，且不泄漏是哪种原因")
    void missingOrGarbageTokenIsUnauthenticated() {
        String auctionId = createRunningAuction("无凭证", 100, 10, 600);

        Resp missing = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, null, null);
        assertEquals(401, missing.code(), missing.raw());
        assertCode("UNAUTHENTICATED", missing);

        Resp garbage = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, "not-a-real-token", null);
        assertEquals(401, garbage.code(), garbage.raw());
        assertCode("UNAUTHENTICATED", garbage);
    }

    @Test
    @DisplayName("频率上限：超出即 429，且不落到业务层")
    void rateLimitReturnsTooManyRequests() {
        String auctionId = createRunningAuction("限流", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read"), Instant.now().plusSeconds(3600), 2);

        assertEquals(200, agentCall("GET", "/api/v1/agent/auctions/" + auctionId, token, null).code());
        assertEquals(200, agentCall("GET", "/api/v1/agent/auctions/" + auctionId, token, null).code());

        Resp limited = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, token, null);
        assertEquals(429, limited.code(), limited.raw());
        assertCode("RATE_LIMITED", limited);
    }

    // ---------------------------------------------------------------- 端口与凭证隔离

    @Test
    @DisplayName("两种凭证互不通用：用户 JWT 过不了 Agent 接口，Agent Token 过不了用户接口")
    void credentialsAreNotInterchangeable() {
        String auctionId = createRunningAuction("凭证隔离", 100, 10, 600);
        String agentToken = issueToken(auctionId, List.of("auction:read", "auction:bid"));
        String jwt = bidderToken();

        Resp jwtOnAgentApi = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, jwt, null);
        assertEquals(401, jwtOnAgentApi.code(), jwtOnAgentApi.raw());
        assertCode("UNAUTHENTICATED", jwtOnAgentApi);

        Resp agentTokenOnUserApi = call("GET", "/api/v1/auctions/" + auctionId, agentToken, null);
        assertEquals(401, agentTokenOnUserApi.code(), agentTokenOnUserApi.raw());
        assertCode("UNAUTHENTICATED", agentTokenOnUserApi);
    }

    @Test
    @DisplayName("Agent Token 不能用于管理接口（连 403 都到不了，先被 JWT 过滤器挡下）")
    void agentTokenCannotReachAdminApi() {
        String auctionId = createRunningAuction("越权管理", 100, 10, 600);
        String agentToken = issueToken(auctionId, List.of("auction:read", "auction:bid"));

        Resp resp = call("POST", "/api/v1/admin/agent-tokens", agentToken,
                tokenRequestJson("偷签", List.of(auctionId), List.of("auction:read"),
                        Instant.now().plusSeconds(600), null));

        assertEquals(401, resp.code(), resp.raw());
        assertCode("UNAUTHENTICATED", resp);
    }

    @Test
    @DisplayName("签发需要管理员：普通用户 403，匿名 401")
    void issuingRequiresAdmin() {
        String auctionId = createRunningAuction("签发权限", 100, 10, 600);
        String body = tokenRequestJson("提权", List.of(auctionId), List.of("auction:read"),
                Instant.now().plusSeconds(600), null);

        Resp asBidder = call("POST", "/api/v1/admin/agent-tokens", bidderToken(), body);
        assertEquals(403, asBidder.code(), asBidder.raw());
        assertCode("FORBIDDEN", asBidder);

        Resp anonymous = call("POST", "/api/v1/admin/agent-tokens", null, body);
        assertEquals(401, anonymous.code(), anonymous.raw());
        assertCode("UNAUTHENTICATED", anonymous);
    }

    @Test
    @DisplayName("签发请求非法（过期时间在过去 / 未知权限 / 不存在的拍卖）都是 400")
    void issuanceValidatesInput() {
        String auctionId = createRunningAuction("签发校验", 100, 10, 600);
        String admin = adminToken();

        assertCode("VALIDATION_FAILED", call("POST", "/api/v1/admin/agent-tokens", admin,
                tokenRequestJson("过期", List.of(auctionId), List.of("auction:read"),
                        Instant.now().minusSeconds(60), null)));
        assertCode("VALIDATION_FAILED", call("POST", "/api/v1/admin/agent-tokens", admin,
                tokenRequestJson("未知权限", List.of(auctionId), List.of("auction:cancel"),
                        Instant.now().plusSeconds(600), null)));
        assertCode("VALIDATION_FAILED", call("POST", "/api/v1/admin/agent-tokens", admin,
                tokenRequestJson("不存在的拍卖", List.of("auc_nope"), List.of("auction:read"),
                        Instant.now().plusSeconds(600), null)));
        assertCode("VALIDATION_FAILED", call("POST", "/api/v1/admin/agent-tokens", admin,
                tokenRequestJson("频率上限非法", List.of(auctionId), List.of("auction:read"),
                        Instant.now().plusSeconds(600), 0)));
    }

    @Test
    @DisplayName("不写 auctionIds 是允许的（等于一张空白的购买清单），但对任何拍卖都读不到")
    void tokenWithoutAuctionsIsIssuedButDeniesEverything() {
        String auctionId = createRunningAuction("空范围 Token", 100, 10, 600);

        String token = issueToken(auctionId, List.of("auction:read", "auction:bid"));
        Resp issued = call("POST", "/api/v1/admin/agent-tokens", adminToken(),
                tokenRequestJson("空范围", null, List.of("auction:read", "auction:bid"),
                        Instant.now().plusSeconds(600), null));
        assertEquals(201, issued.code(), issued.raw());
        String blankToken = issued.body().at("/data/token").asText();

        // 空范围不是“全部允许”：默认拒绝（D-29）。
        Resp read = agentCall("GET", "/api/v1/agent/auctions/" + auctionId, blankToken, null);
        assertEquals(403, read.code(), read.raw());
        assertCode("FORBIDDEN", read);

        // 而明写了范围的另一枚 Token 不受影响。
        assertEquals(200, agentCall("GET", "/api/v1/agent/auctions/" + auctionId, token, null).code());
    }

    @Test
    @DisplayName("Agent 端口只提供 Agent 接口：健康检查与用户接口都是 404 封套")
    void agentPortExposesOnlyAgentApi() {
        for (String path : List.of("/api/v1/health", "/api/v1/auctions", "/api/v1/admin/agent-tokens",
                "/api/v1/auth/login", "/api/v1/agent", "/")) {
            Resp resp = agentCall("GET", path, null, null);
            assertEquals(404, resp.code(), path + " -> " + resp.raw());
            assertCode("NOT_FOUND", resp);
        }
    }

    @Test
    @DisplayName("Agent 接口在主端口上也可用（同一套路由，独立端口是额外一层收敛）")
    void agentApiIsAlsoServedOnMainPort() {
        String auctionId = createRunningAuction("双端口", 100, 10, 600);
        String token = issueToken(auctionId, List.of("auction:read"));

        Resp resp = call("GET", "/api/v1/agent/auctions/" + auctionId, token, null);

        assertEquals(200, resp.code(), resp.raw());
        assertCode("OK", resp);
    }

    // ---------------------------------------------------------------- 工具

    private String issueToken(String auctionId, List<String> scopes) {
        return issueToken(auctionId, scopes, Instant.now().plusSeconds(3600), null);
    }

    /** 签发一枚 Token 并返回明文（明文只在此刻可得，因此签发和取用必须在同一个用例里）。 */
    private String issueToken(String auctionId, List<String> scopes, Instant expiresAt, Integer rateLimit) {
        Resp resp = call("POST", "/api/v1/admin/agent-tokens", adminToken(),
                tokenRequestJson("集成测试 Token", List.of(auctionId), scopes, expiresAt, rateLimit));
        assertEquals(201, resp.code(), resp.raw());
        assertCode("OK", resp);
        String token = resp.body().at("/data/token").asText();
        assertFalse(token.isEmpty(), resp.raw());
        String tokenId = resp.body().at("/data/tokenId").asText();
        assertFalse(tokenId.isEmpty(), resp.raw());
        issuedTokenIds.put(token, tokenId);
        return token;
    }

    /** 取出签发时返回的 tokenId（见 {@link #issuedTokenIds}）。 */
    private String tokenIdOf(String plaintext) {
        String tokenId = issuedTokenIds.get(plaintext);
        assertNotNull(tokenId, "本用例应当先通过 issueToken 拿到这枚 Token");
        return tokenId;
    }

    private static String tokenRequestJson(String name, List<String> auctionIds, List<String> scopes,
            Instant expiresAt, Integer rateLimit) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":\"").append(name).append("\",");
        sb.append("\"agentUserId\":\"").append(AGENT_OWNER).append("\",");
        if (auctionIds != null) {
            sb.append("\"auctionIds\":").append(jsonArray(auctionIds)).append(',');
        }
        sb.append("\"scopes\":").append(jsonArray(scopes)).append(',');
        sb.append("\"expiresAt\":\"").append(expiresAt).append('"');
        if (rateLimit != null) {
            sb.append(",\"rateLimitPerMinute\":").append(rateLimit);
        }
        return sb.append('}').toString();
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]").orElse("[]");
    }
}
