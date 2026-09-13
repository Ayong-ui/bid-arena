package com.bidarena.agentaccess.persistence;

import com.bidarena.agentaccess.domain.AgentProxyStatus;
import com.bidarena.shared.Db;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/**
 * 托管 AI 代理的读写。
 *
 * <h2>为什么查询都要 JOIN auctions</h2>
 * 调度器每轮要回答的问题只有一个：<b>"这个代理此刻该不该出价"</b>。它需要同时看到
 * 代理的意图（预算、状态）与拍卖的实时事实（状态、当前价、最小加价、谁领先）。
 * 分成两次查询会出现一个窗口：读到"我落后"，再查时已经被人反超两次，
 * 于是照着旧价格出价被 {@code BID_TOO_LOW} 拒绝，白白空转一轮。
 * 一条 JOIN 语句读到的是一致快照，这个窗口就不存在。
 *
 * <p>行列映射用同一张 {@link ProxyRow}：列表、详情、调度取的是同一组字段，
 * 分成三个 record 只会让"加了一个字段忘了改另一处"变成可能。
 */
public class AgentProxyRepository {

    /**
     * 代理行 + 它绑定的拍卖的实时事实。
     *
     * <p>{@code auctionStatus / currentPrice / minIncrement / leaderId} 属于**拍卖**上下文，
     * 这里只作为一次查询的副产品读出，不落库、不回写——它们随时会变，
     * 复制进 {@code agent_proxies} 就等于制造第二份会过期的真相。
     */
    public record ProxyRow(
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
            Instant revokedAt,
            String auctionTitle,
            String auctionStatus,
            long currentPrice,
            long minIncrement,
            String leaderId) {

        /** 代理自己是不是当前最高价。 */
        public boolean leading() {
            return ownerUserId.equals(leaderId);
        }

        /** 下一口跟价（与 BidService 的最小加价规则同源）。 */
        public long nextBidAmount() {
            return currentPrice + minIncrement;
        }

        /** 下次跟价是否还在预算内。 */
        public boolean canAffordNext() {
            return nextBidAmount() <= budgetLimit;
        }
    }

    private static final String SELECT_PROXY =
            "SELECT p.id, p.owner_user_id, p.auction_id, p.budget_limit, p.status, p.bid_count, "
                    + "p.last_bid_amount, p.budget_reached_at, p.won, p.final_price, "
                    + "p.created_at, p.updated_at, p.revoked_at, "
                    + "a.title AS auction_title, a.status AS auction_status, a.current_price, "
                    + "a.min_increment, a.leader_id "
                    + "FROM agent_proxies p JOIN auctions a ON a.id = p.auction_id ";

    private final DataSource dataSource;

    public AgentProxyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ------------------------------------------------------------------ 写

    public void insert(String proxyId, String ownerUserId, String auctionId, long budgetLimit) {
        Db.tx(dataSource, conn -> Db.update(conn,
                "INSERT INTO agent_proxies (id, owner_user_id, auction_id, budget_limit, status, "
                        + "bid_count, created_at) VALUES (?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP(6))",
                proxyId, ownerUserId, auctionId, budgetLimit, AgentProxyStatus.PENDING.name()));
    }

    /**
     * 把一条已撤销的代理重置为全新状态。
     *
     * <p>唯一键 {@code (owner_user_id, auction_id)} 决定了"撤销后重建"必须复用同一行，
     * 否则第二次创建会撞唯一键。重置会把计数与提醒标记一并清掉——用户重新开启的是一个
     * 新代理，旧的出价次数留在列表里只会让人误判。
     */
    public void reset(String proxyId, long budgetLimit) {
        Db.tx(dataSource, conn -> Db.update(conn,
                "UPDATE agent_proxies SET budget_limit = ?, status = 'PENDING', bid_count = 0, "
                        + "last_bid_amount = NULL, budget_reached_at = NULL, won = NULL, final_price = NULL, "
                        + "revoked_at = NULL WHERE id = ?",
                budgetLimit, proxyId));
    }

    /** 记录一次成功出价。状态同时从 PENDING 提升为 BIDDING（"已进场"）。 */
    public void markBidPlaced(String proxyId, int bidCount, long lastBidAmount) {
        Db.tx(dataSource, conn -> Db.update(conn,
                "UPDATE agent_proxies SET status = 'BIDDING', bid_count = ?, last_bid_amount = ? "
                        + "WHERE id = ?",
                bidCount, lastBidAmount, proxyId));
    }

    /** 已加入但未出价（例如创建时代理就已领先）时，仅把状态推进为 BIDDING。 */
    public void markBidding(String proxyId) {
        Db.tx(dataSource, conn -> Db.update(conn,
                "UPDATE agent_proxies SET status = 'BIDDING' WHERE id = ? AND status = 'PENDING'", proxyId));
    }

    /**
     * 标记"已达预算"。{@code budget_reached_at IS NULL} 使这次写入具备幂等性：
     * 调度器每轮都会重复判定触顶，但只有第一次能改到行，提醒因此只发一次。
     */
    public boolean markBudgetReached(String proxyId) {
        Integer affected = Db.read(dataSource, conn -> Db.update(conn,
                "UPDATE agent_proxies SET status = 'BUDGET_REACHED', budget_reached_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE id = ? AND status IN ('PENDING', 'BIDDING')",
                proxyId));
        return affected != null && affected > 0;
    }

    /** 拍卖结束后落结果快照。对 PENDING/BIDDING/BUDGET_REACHED 三个活状态都生效。 */
    public void markFinished(String proxyId, Boolean won, Long finalPrice) {
        Db.tx(dataSource, conn -> Db.update(conn,
                "UPDATE agent_proxies SET status = 'FINISHED', won = ?, final_price = ? "
                        + "WHERE id = ? AND status <> 'REVOKED'",
                won, finalPrice, proxyId));
    }

    /** 用户撤销。返回影响行数：0 表示"不存在或不属于本人"，两种情况对外都是 404。 */
    public int revoke(String proxyId, String ownerUserId) {
        Integer affected = Db.read(dataSource, conn -> Db.update(conn,
                "UPDATE agent_proxies SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE id = ? AND owner_user_id = ? AND status <> 'REVOKED'",
                proxyId, ownerUserId));
        return affected == null ? 0 : affected;
    }

    // ------------------------------------------------------------------ 读

    public ProxyRow load(String proxyId) {
        return Db.read(dataSource, conn -> Db.queryOne(conn,
                SELECT_PROXY + "WHERE p.id = ?", AgentProxyRepository::mapRow, proxyId));
    }

    public ProxyRow findByOwnerAndAuction(String ownerUserId, String auctionId) {
        return Db.read(dataSource, conn -> Db.queryOne(conn,
                SELECT_PROXY + "WHERE p.owner_user_id = ? AND p.auction_id = ?",
                AgentProxyRepository::mapRow, ownerUserId, auctionId));
    }

    public List<ProxyRow> pageByOwner(String ownerUserId, int limit, int offset) {
        return Db.read(dataSource, conn -> Db.queryList(conn,
                SELECT_PROXY + "WHERE p.owner_user_id = ? ORDER BY p.created_at DESC, p.id LIMIT ? OFFSET ?",
                AgentProxyRepository::mapRow, ownerUserId, limit, offset));
    }

    public long countByOwner(String ownerUserId) {
        Long total = Db.read(dataSource, conn -> Db.queryOne(conn,
                "SELECT COUNT(*) FROM agent_proxies WHERE owner_user_id = ?", rs -> rs.getLong(1), ownerUserId));
        return total == null ? 0 : total;
    }

    public List<ProxyRow> pageAll(int limit, int offset) {
        return Db.read(dataSource, conn -> Db.queryList(conn,
                SELECT_PROXY + "ORDER BY p.created_at DESC, p.id LIMIT ? OFFSET ?",
                AgentProxyRepository::mapRow, limit, offset));
    }

    public long countAll() {
        Long total = Db.read(dataSource, conn -> Db.queryOne(conn,
                "SELECT COUNT(*) FROM agent_proxies", rs -> rs.getLong(1)));
        return total == null ? 0 : total;
    }

    /**
     * 调度器每轮取待办代理。
     *
     * <p>只取活状态（{@code PENDING} / {@code BIDDING}）：{@code BUDGET_REACHED} 不会再出价，
     * 但它仍要等拍卖结束后被改成 {@code FINISHED}——所以那条路径由
     * {@link #listFrozenForFinishedAuctions} 单独兜底，而不是让调度器每轮重扫全部代理。
     */
    public List<ProxyRow> findLive(int limit) {
        return Db.read(dataSource, conn -> Db.queryList(conn,
                SELECT_PROXY + "WHERE p.status IN ('PENDING', 'BIDDING') "
                        + "ORDER BY p.created_at, p.id LIMIT ?",
                AgentProxyRepository::mapRow, limit));
    }

    /**
     * 已触顶但拍卖已经结束的代理。
     *
     * <p>它们不在 {@link #findLive} 的结果里，若没人管就会永远停在 {@code BUDGET_REACHED}，
     * 用户界面上表现为"这场早结束了，我的 AI 还在喊预算不够"。
     */
    public List<ProxyRow> listFrozenForFinishedAuctions(int limit) {
        return Db.read(dataSource, conn -> Db.queryList(conn,
                SELECT_PROXY + "WHERE p.status = 'BUDGET_REACHED' AND a.status IN ('FINISHED', 'CANCELLED') "
                        + "ORDER BY p.created_at, p.id LIMIT ?",
                AgentProxyRepository::mapRow, limit));
    }

    private static ProxyRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProxyRow(
                rs.getString("id"),
                rs.getString("owner_user_id"),
                rs.getString("auction_id"),
                rs.getLong("budget_limit"),
                AgentProxyStatus.parse(rs.getString("status")),
                rs.getInt("bid_count"),
                (Long) rs.getObject("last_bid_amount"),
                Db.instant(rs, "budget_reached_at"),
                (Boolean) rs.getObject("won"),
                (Long) rs.getObject("final_price"),
                Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"),
                Db.instant(rs, "revoked_at"),
                rs.getString("auction_title"),
                rs.getString("auction_status"),
                rs.getLong("current_price"),
                rs.getLong("min_increment"),
                rs.getString("leader_id"));
    }
}
