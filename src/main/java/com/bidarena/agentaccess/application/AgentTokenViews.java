package com.bidarena.agentaccess.application;

import com.bidarena.agentaccess.persistence.AgentTokenRepository.TokenSummaryRow;
import com.bidarena.shared.ApiTime;
import java.time.Instant;
import java.util.Set;

/**
 * Agent 授权的对外投影（**永不含明文**）。
 *
 * <p>明文 Token 只在签发响应的那一刻存在；列表接口能给出的只有"这枚授权现在处于什么状态"。
 * 把所有状态判断收在一个 {@code of(row, now)} 里，而不是散在控制器：{@code ACTIVE} /
 * {@code EXPIRED} / {@code REVOKED} 的口径必须与鉴权路径上的 {@code activeAt} 完全一致，
 * 否则会出现"界面显示可用、实际 401"这种最难排查的分歧。
 */
public final class AgentTokenViews {

    private AgentTokenViews() {}

    /**
     * 一枚授权的管理摘要。
     *
     * <p>{@code status} 由服务端按当前时刻判定，客户端不得自行比时间：这与"前端不得自己
     * 算服务端事实"是同一条原则（D-27）；{@code revokedAt} / {@code createdAt} 为审计字段。
     */
    public record AgentTokenSummary(
            String tokenId, String name, String agentUserId, String status,
            Set<String> auctionIds, Set<String> scopes, int rateLimitPerMinute,
            String expiresAt, String revokedAt, String createdAt) {

        public static AgentTokenSummary of(TokenSummaryRow row, Instant now) {
            return new AgentTokenSummary(
                    row.tokenId(), row.name(), row.agentUserId(), status(row, now),
                    row.auctionIds(), row.scopes(), row.rateLimitPerMinute(),
                    ApiTime.format(row.expiresAt()),
                    row.revokedAt() == null ? null : ApiTime.format(row.revokedAt()),
                    ApiTime.format(row.createdAt()));
        }

        /** 优先级 REVOKED &gt; EXPIRED &gt; ACTIVE，与 {@code AgentToken.activeAt} 同口径。 */
        private static String status(TokenSummaryRow row, Instant now) {
            if (row.revokedAt() != null) {
                return "REVOKED";
            }
            return now.isBefore(row.expiresAt()) ? "ACTIVE" : "EXPIRED";
        }
    }
}
