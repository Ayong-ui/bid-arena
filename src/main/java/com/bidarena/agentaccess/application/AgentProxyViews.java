package com.bidarena.agentaccess.application;

import com.bidarena.agentaccess.persistence.AgentProxyRepository.ProxyRow;
import com.bidarena.shared.ApiTime;

/**
 * 托管 AI 代理的对外投影（{@code openapi.yaml} 的 {@code AgentProxySummary}）。
 *
 * <p>与仓储行分开的理由和其他上下文一致：行会随查询需要加列，而响应格式必须显式受控。
 *
 * <p>这里刻意<b>不</b>暴露钱包或余额：代理花的是用户自己的钱，
 * 余额该去钱包页看，在两个地方各算一份迟早会出现两个数字。
 */
public final class AgentProxyViews {

    private AgentProxyViews() {}

    public record Summary(
            String proxyId,
            String ownerUserId,
            String auctionId,
            String auctionTitle,
            String auctionStatus,
            String status,
            long budgetLimit,
            int bidCount,
            Long lastBidAmount,
            long currentPrice,
            long minIncrement,
            long nextBidAmount,
            boolean leading,
            boolean budgetReached,
            Boolean won,
            Long finalPrice,
            String budgetReachedAt,
            String createdAt,
            String updatedAt,
            String revokedAt) {

        public static Summary of(ProxyRow row) {
            return new Summary(
                    row.id(),
                    row.ownerUserId(),
                    row.auctionId(),
                    row.auctionTitle(),
                    row.auctionStatus(),
                    row.status().name(),
                    row.budgetLimit(),
                    row.bidCount(),
                    row.lastBidAmount(),
                    row.currentPrice(),
                    row.minIncrement(),
                    row.nextBidAmount(),
                    row.leading(),
                    row.budgetReachedAt() != null,
                    row.won(),
                    row.finalPrice(),
                    ApiTime.format(row.budgetReachedAt()),
                    ApiTime.format(row.createdAt()),
                    ApiTime.format(row.updatedAt()),
                    ApiTime.format(row.revokedAt()));
        }
    }
}
