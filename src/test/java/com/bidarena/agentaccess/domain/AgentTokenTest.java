package com.bidarena.agentaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Agent 凭证的授权判断：范围、权限、过期与吊销的<b>边界</b>。
 *
 * <p>这些是纯计算，不碰数据库——因此可以精确地钉住边界（例如"恰好等于 expiresAt"），
 * 而不必靠 sleep 去撞一个时间点。
 */
@DisplayName("Agent 凭证授权判断")
class AgentTokenTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static AgentToken token(Set<AgentScope> scopes, Set<String> auctionIds, Instant revokedAt) {
        return new AgentToken("agt_test", "评审用", "usr_bidder_a", scopes, auctionIds, 60, EXPIRES_AT, revokedAt);
    }

    @Test
    @DisplayName("范围为空 = 默认拒绝：不是“全部允许”")
    void emptyScopeDeniesEverything() {
        AgentToken token = token(Set.of(AgentScope.READ), Set.of(), null);

        assertFalse(token.covers("auc_1"), "没有写明允许的拍卖就不能访问");
        assertFalse(token.covers("auc_2"));
    }

    @Test
    @DisplayName("范围只覆盖写明的拍卖，且是精确匹配（auc_1 不能命中 auc_10）")
    void scopeIsExactMatch() {
        AgentToken token = token(Set.of(AgentScope.READ), Set.of("auc_1"), null);

        assertTrue(token.covers("auc_1"));
        assertFalse(token.covers("auc_10"), "前缀相同但不是同一场拍卖，必须拒绝");
        assertFalse(token.covers(null));
    }

    @Test
    @DisplayName("权限按项判断：只有 auction:read 的 Token 不能出价")
    void scopeSeparatesReadAndBid() {
        AgentToken reader = token(Set.of(AgentScope.READ), Set.of("auc_1"), null);
        AgentToken bidder = token(Set.of(AgentScope.BID), Set.of("auc_1"), null);

        assertTrue(reader.allows(AgentScope.READ));
        assertFalse(reader.allows(AgentScope.BID));
        assertFalse(bidder.allows(AgentScope.READ));
        assertTrue(bidder.allows(AgentScope.BID));
    }

    @Test
    @DisplayName("过期边界：恰好等于 expiresAt 即过期（与出价截止同一口径）")
    void expiresExactlyAtBoundary() {
        AgentToken token = token(Set.of(AgentScope.READ), Set.of("auc_1"), null);

        assertFalse(token.expiredAt(EXPIRES_AT.minusNanos(1)), "过期前一纳秒仍未过期");
        assertTrue(token.activeAt(EXPIRES_AT.minusNanos(1)));
        assertTrue(token.expiredAt(EXPIRES_AT), "恰好等于 expiresAt 必须算过期");
        assertFalse(token.activeAt(EXPIRES_AT));
        assertTrue(token.expiredAt(EXPIRES_AT.plusSeconds(1)));
    }

    @Test
    @DisplayName("吊销立即生效，且不受过期时间影响")
    void revokedTokenIsNeverActive() {
        AgentToken token = token(Set.of(AgentScope.READ), Set.of("auc_1"), EXPIRES_AT.minusSeconds(3600));

        assertTrue(token.revoked());
        assertFalse(token.expiredAt(EXPIRES_AT.minusSeconds(1800)), "吊销时点远早于过期时点");
        assertFalse(token.activeAt(EXPIRES_AT.minusSeconds(1800)), "已吊销就必须不可用");
    }

    @Test
    @DisplayName("集合做了防御性拷贝：外部改动不影响已判定的凭证")
    void collectionsAreDefensivelyCopied() {
        Set<String> auctions = new java.util.HashSet<>(Set.of("auc_1"));
        AgentToken token = token(Set.of(AgentScope.READ), auctions, null);

        auctions.add("auc_2");

        assertFalse(token.covers("auc_2"), "构造之后追加的范围不应生效");
        assertEquals(Set.of("auc_1"), token.auctionIds());
    }
}
