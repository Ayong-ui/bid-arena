package com.bidarena.wallet.persistence;

import com.bidarena.shared.ActorType;
import com.bidarena.shared.Db;
import com.bidarena.shared.ErrorCode;
import com.bidarena.shared.BizException;
import com.bidarena.wallet.domain.LedgerType;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 资金写入的唯一出口。
 *
 * <p>本类承担两件事，它们必须在一起：
 * <ol>
 *   <li>{@code wallets}：总余额与冻结额（跨所有场次）；</li>
 *   <li>{@code auction_participants.frozen_amount}：某用户**在某一场**的冻结额。</li>
 * </ol>
 * 之所以必须有第二项：原文的余额口径是"出价校验本次需新增冻结的金额，即出价金额减去
 * 该用户在本场已冻结金额"。只看 {@code wallets.frozen_amount} 算不出这个差额。
 * 两者是同一份事实的两个粒度，必须同事务更新，因此由同一个类负责，不允许别处直接写这两张表。
 *
 * <p><b>锁顺序</b>（见 {@code DECISIONS.md} D-4）：
 * 调用方必须先锁拍卖行，再调 {@link #lockAuctionFreezes}，最后调 {@link #lockWallets}。
 * 两者内部都按 {@code user_id} 升序加锁——顺序固定是死锁避免的前提，
 * 不是可选的写法偏好。
 *
 * <p><b>为什么不加乐观锁版本号</b>：版本号在冲突后仍需重读全部校验条件，
 * 而这里的校验条件（余额、当前价、截止时间）本来就要求持有行锁才能读准，
 * 直接用悲观锁更少一层间接。
 */
public class WalletRepository {

    private final DataSource dataSource;

    public WalletRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 钱包的三种读法都是一致的：可用额永远由总额与冻结额算出，不单独存储。 */
    public record WalletRow(String userId, long totalBalance, long frozenAmount) {
        public long availableBalance() {
            return totalBalance - frozenAmount;
        }

        public WalletRow withFrozen(long newFrozen) {
            return new WalletRow(userId, totalBalance, newFrozen);
        }
    }

    public record LedgerRow(
            long id, ActorType actorType, LedgerType type, long amount, String auctionId, String requestId,
            Instant createdAt) {}

    // ------------------------------------------------------------------
    // 事务内：加锁读取
    // ------------------------------------------------------------------

    /**
     * 锁定这些用户在**本场**的冻结额，按 {@code user_id} 升序。
     *
     * <p>返回的 map 只包含确实存在的参与记录；调用方应把缺失视为 0（未参与）。
     */
    public Map<String, Long> lockAuctionFreezes(Connection conn, String auctionId, Collection<String> userIds)
            throws SQLException {
        List<String> ids = sortedDistinct(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        String sql =
                "SELECT user_id, frozen_amount FROM auction_participants "
                        + "WHERE auction_id = ? AND user_id IN (" + Db.placeholders(ids.size()) + ") "
                        + "ORDER BY user_id FOR UPDATE";

        List<Object> args = new ArrayList<>();
        args.add(auctionId);
        args.addAll(ids);

        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e :
                Db.queryList(conn, sql, rs -> Map.entry(rs.getString("user_id"), rs.getLong("frozen_amount")),
                                args.toArray())
                        .stream()
                        .toList()) {
            result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    /**
     * 锁定某场**全部**有参与记录的用户（结算用），按 {@code user_id} 升序。
     */
    public List<Map.Entry<String, Long>> lockAllAuctionFreezes(Connection conn, String auctionId)
            throws SQLException {
        String sql =
                "SELECT user_id, frozen_amount FROM auction_participants "
                        + "WHERE auction_id = ? ORDER BY user_id FOR UPDATE";
        return Db.queryList(conn, sql,
                rs -> Map.entry(rs.getString("user_id"), rs.getLong("frozen_amount")), auctionId);
    }

    /**
     * 锁定钱包行，按 {@code user_id} 升序。
     *
     * <p>{@code ORDER BY user_id} 与 {@code IN} 列表配合，使 InnoDB 按主键升序读行、
     * 因而按升序加锁。两场拍卖同时换领先者时，若一个按 A→B 加锁、另一个按 B→A，
     * 就会互相等对方持锁而死锁；固定顺序把这类死锁从"偶发"变成"不存在"。
     */
    public Map<String, WalletRow> lockWallets(Connection conn, Collection<String> userIds) throws SQLException {
        List<String> ids = sortedDistinct(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        String sql =
                "SELECT user_id, total_balance, frozen_amount FROM wallets "
                        + "WHERE user_id IN (" + Db.placeholders(ids.size()) + ") "
                        + "ORDER BY user_id FOR UPDATE";

        Map<String, WalletRow> result = new LinkedHashMap<>();
        for (WalletRow row :
                Db.queryList(conn, sql,
                        rs -> new WalletRow(rs.getString("user_id"), rs.getLong("total_balance"),
                                rs.getLong("frozen_amount")),
                        ids.toArray())) {
            result.put(row.userId(), row);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 事务内：写入（条件更新，不只依赖应用层判断）
    // ------------------------------------------------------------------

    /**
     * 增加冻结额。
     *
     * <p>WHERE 里带上了可用额守卫：即使调用方算错了 delta，也不可能把可用额压成负数。
     * 影响行数为 0 说明守卫不成立，必须报错而不是静默继续——
     * 这正是原文否定的"只靠应用层先查询再判断"。
     */
    public void increaseFrozen(Connection conn, String userId, long delta) throws SQLException {
        if (delta == 0) {
            return;
        }
        int affected =
                Db.update(conn,
                        "UPDATE wallets SET frozen_amount = frozen_amount + ? "
                                + "WHERE user_id = ? AND frozen_amount + ? <= total_balance",
                        delta, userId, delta);
        if (affected != 1) {
            throw new BizException(
                    ErrorCode.INSUFFICIENT_BALANCE,
                    "可用余额不足，无法冻结",
                    Map.of("userId", userId, "needDelta", delta));
        }
    }

    /** 减少冻结额（释放）。delta 必须为正数。 */
    public void decreaseFrozen(Connection conn, String userId, long delta) throws SQLException {
        if (delta == 0) {
            return;
        }
        int affected =
                Db.update(conn,
                        "UPDATE wallets SET frozen_amount = frozen_amount - ? "
                                + "WHERE user_id = ? AND frozen_amount - ? >= 0",
                        delta, userId, delta);
        if (affected != 1) {
            throw new BizException(
                    ErrorCode.INTERNAL_ERROR,
                    "释放冻结失败：冻结额不足以释放",
                    Map.of("userId", userId, "delta", delta));
        }
    }

    /**
     * 结算扣款：赢家的冻结额转为实际扣款。
     *
     * <p>总额与冻结额必须在**同一条 UPDATE** 里一起改。分开写会有一个中间状态
     * （总额已减、冻结未减）使 {@code frozen_amount <= total_balance} 不成立，
     * 被 CHECK 约束直接拒绝——这是约束在强制我们把语义写对，不是障碍。
     */
    public void settleWinner(Connection conn, String userId, long amount) throws SQLException {
        int affected =
                Db.update(conn,
                        "UPDATE wallets SET total_balance = total_balance - ?, frozen_amount = frozen_amount - ? "
                                + "WHERE user_id = ? AND total_balance >= ? AND frozen_amount >= ?",
                        amount, amount, userId, amount, amount);
        if (affected != 1) {
            throw new BizException(
                    ErrorCode.INTERNAL_ERROR,
                    "结算扣款失败：余额或冻结额不足",
                    Map.of("userId", userId, "amount", amount));
        }
    }

    /** 更新某用户在某场的冻结额（与上面的钱包变更同事务）。 */
    public void setAuctionFrozen(Connection conn, String auctionId, String userId, long newFrozen)
            throws SQLException {
        int affected =
                Db.update(conn,
                        "UPDATE auction_participants SET frozen_amount = ? WHERE auction_id = ? AND user_id = ?",
                        newFrozen, auctionId, userId);
        if (affected != 1) {
            throw new BizException(
                    ErrorCode.NOT_JOINED,
                    "参与记录不存在，无法更新本场冻结额",
                    Map.of("auctionId", auctionId, "userId", userId));
        }
    }

    /**
     * 追加一条资金流水。
     *
     * <p>流水只追加、不修改：它是对账与事后解释的依据。
     * 同时记录变更后的两个余额快照，使任意一次余额变化都能被单行解释，
     * 不必回放整个历史。
     */
    public void appendLedger(Connection conn, String userId, ActorType actorType, LedgerType type, long amount,
            String auctionId, String requestId, long totalAfter, long frozenAfter) throws SQLException {
        Db.update(conn,
                "INSERT INTO ledger_entries "
                        + "(user_id, actor_type, entry_type, amount, auction_id, request_id, total_after, frozen_after) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                userId, actorType.name(), type.name(), amount, auctionId, requestId, totalAfter, frozenAfter);
    }

    // ------------------------------------------------------------------
    // 事务外：读路径
    // ------------------------------------------------------------------

    public WalletRow load(String userId) {
        return Db.read(dataSource, conn -> Db.queryOne(conn,
                "SELECT user_id, total_balance, frozen_amount FROM wallets WHERE user_id = ?",
                rs -> new WalletRow(rs.getString("user_id"), rs.getLong("total_balance"),
                        rs.getLong("frozen_amount")),
                userId));
    }

    /**
     * 流水分页，按 {@code id} 倒序（最新在前）。
     *
     * <p>用 offset 而不是游标分页，是为了与契约的 {@code page}/{@code size} 语义一致：
     * 契约要求返回 {@code total}，而游标分页给不出总数。代价是深翻页会变慢（{@code OFFSET} 要扫过前面所有行），
     * 对“个人流水”这种规模的数据量可以接受；数据量真的变大时再换成游标并改契约。
     *
     * <p>排序用 {@code id} 而不是 {@code created_at}：同一毫秒内的多条流水时间戳可能相同，
     * 用时间排序会得到不稳定的顺序，翻页时出现重复或漏行。
     */
    public List<LedgerRow> pageLedger(String userId, int limit, int offset) {
        return Db.read(dataSource, conn -> Db.queryList(conn,
                "SELECT id, actor_type, entry_type, amount, auction_id, request_id, created_at FROM ledger_entries "
                        + "WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                rs -> new LedgerRow(rs.getLong("id"), ActorType.parse(rs.getString("actor_type")),
                        LedgerType.valueOf(rs.getString("entry_type")),
                        rs.getLong("amount"),
                        rs.getString("auction_id"), rs.getString("request_id"),
                        Db.instant(rs, "created_at")),
                userId, limit, offset));
    }

    public long countLedger(String userId) {
        return Db.read(dataSource, conn -> {
            Long total = Db.queryOne(conn, "SELECT COUNT(*) FROM ledger_entries WHERE user_id = ?",
                    rs -> rs.getLong(1), userId);
            return total == null ? 0L : total;
        });
    }

    /**
     * 某场拍卖的全部流水，按 {@code id} 倒序。
     *
     * <p>管理员"这场最后成交的是 AI 还是人"的入口。与个人流水共用同一个投影，
     * 因此 {@code actorType} 字段不会在两个读路径里长出两种含义。
     */
    public List<LedgerRow> pageLedgerByAuction(String auctionId, int limit, int offset) {
        return Db.read(dataSource, conn -> Db.queryList(conn,
                "SELECT id, actor_type, entry_type, amount, auction_id, request_id, created_at FROM ledger_entries "
                        + "WHERE auction_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                rs -> new LedgerRow(rs.getLong("id"), ActorType.parse(rs.getString("actor_type")),
                        LedgerType.valueOf(rs.getString("entry_type")),
                        rs.getLong("amount"),
                        rs.getString("auction_id"), rs.getString("request_id"),
                        Db.instant(rs, "created_at")),
                auctionId, limit, offset));
    }

    public long countLedgerByAuction(String auctionId) {
        return Db.read(dataSource, conn -> {
            Long total = Db.queryOne(conn, "SELECT COUNT(*) FROM ledger_entries WHERE auction_id = ?",
                    rs -> rs.getLong(1), auctionId);
            return total == null ? 0L : total;
        });
    }

    private static List<String> sortedDistinct(Collection<String> ids) {
        return ids.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }
}
