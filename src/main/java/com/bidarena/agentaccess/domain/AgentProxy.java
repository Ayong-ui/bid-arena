package com.bidarena.agentaccess.domain;

import java.time.Instant;

/**
 * 一名托管 AI 代理的领域快照。
 *
 * <p>它**不含**任何拍卖的价格数据：当前价、是否领先这些每毫秒都在变的事实住在拍卖上下文里，
 * 需要时现查。把这些字段复制进代理行会产生两份真相，而两者不一致的时刻恰好就是出价的那一刻。
 *
 * <p>{@code budgetLimit} 是硬上限，不是余额：它表达"这个 AI 最多愿意出到多少"，
 * 与钱包可用额是两个独立约束（可用额不足时同样会停手，但那是另一个原因）。
 */
public record AgentProxy(
        String id,
        String ownerUserId,
        String auctionId,
        long budgetLimit,
        AgentProxyStatus status,
        int bidCount,
        Long lastBidAmount,
        Instant budgetReachedAt,
        Boolean won,
        Long finalPrice,
        Instant createdAt,
        Instant updatedAt,
        Instant revokedAt) {

    /**
     * 下一次跟价的金额。
     *
     * <p>与 {@code BidService} 的最小加价规则**同源**：服务端只接受
     * {@code amount >= currentPrice + minIncrement}。这里多算一分钱都是浪费，
     * 少算一分钱会被 409 {@code BID_TOO_LOW} 拒绝并让调度器空转。
     */
    public long nextBidAmount(long currentPrice, long minIncrement) {
        return currentPrice + minIncrement;
    }

    /** 这笔金额是否还在预算内（含等于）。 */
    public boolean affordable(long amount) {
        return amount <= budgetLimit;
    }
}
