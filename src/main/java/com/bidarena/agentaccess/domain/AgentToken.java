package com.bidarena.agentaccess.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 一枚竞拍 Agent 凭证的<b>已解密</b>视图：调用方拿着它做授权判断，但它不含明文 Token。
 *
 * <p>明文 Token 只在签发的那一刻存在（{@code AgentTokenService.Issued}），
 * 之后系统里只有 {@code tokenHash}。因此本类型可以被自由地放进日志、Context 与响应，
 * 而不必担心泄漏出可用的凭证——这是"数据库只存摘要"的落地方式。
 *
 * <p>{@code auctionIds} 是<b>允许访问的拍卖集合</b>，语义是默认拒绝（见 D-29）：
 * 空集合意味着这枚 Token 对任何一场拍卖都无权访问，而不是"全部允许"。
 * 最小权限原则要求"没写明允许的"就是"不允许"，否则一个漏填 auctionIds 的调用
 * 会得到一个比预期宽得多的 Token，而错误在签发时完全看不出来。
 *
 * @param tokenId           对外标识（非秘密），用于吊销与审计
 * @param agentUserId       以谁的身份出价。授权链的终点就是这个用户 ID
 * @param scopes            允许的动作
 * @param auctionIds        允许访问的拍卖；为空表示不允许任何一场
 * @param rateLimitPerMinute 每 Token 的请求频率上限
 * @param expiresAt         过期时刻（含等于即过期）
 * @param revokedAt         吊销时刻；非空即已吊销，且不可恢复
 */
public record AgentToken(
        String tokenId,
        String name,
        String agentUserId,
        Set<AgentScope> scopes,
        Set<String> auctionIds,
        int rateLimitPerMinute,
        Instant expiresAt,
        Instant revokedAt) {

    public AgentToken {
        scopes = Set.copyOf(scopes);
        auctionIds = Set.copyOf(auctionIds);
    }

    public boolean revoked() {
        return revokedAt != null;
    }

    /**
     * 是否已过期。
     *
     * <p>用 {@code !now.isBefore(expiresAt)} 而不是 {@code now.isAfter(expiresAt)}：
     * 恰好等于过期时刻应当算过期。这与出价截止的边界口径一致（原文："恰好等于截止时刻
     * 按迟到处理"），两处边界若不一致，日后很难解释哪个是对的。
     */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** 在 {@code now} 这一刻是否可用于认证：既未吊销也未过期。 */
    public boolean activeAt(Instant now) {
        return !revoked() && !expiredAt(now);
    }

    /** 是否被授权访问这场拍卖。默认拒绝：集合里没有就是没有。 */
    public boolean covers(String auctionId) {
        return auctionId != null && auctionIds.contains(auctionId);
    }

    public boolean allows(AgentScope scope) {
        return scopes.contains(scope);
    }
}
