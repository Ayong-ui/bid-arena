package com.bidarena.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * 不变量校验：把 DESIGN.md 的 INV-1~4 写成可执行的 SQL。
 *
 * <h2>为什么单独成一个类</h2>
 * 这些 SQL 有两个用途，必须是同一份：
 * <ol>
 *   <li>测试断言——失败时能指出**哪一条**不变量、**哪一行**数据坏了；</li>
 *   <li>交付物里的对账脚本——评审可以直接在任意库上跑，验证"钱是平的"。</li>
 * </ol>
 * 若测试里现写一套、脚本里再写一套，两者迟早不一致，而"两套对账口径"本身就是缺陷。
 *
 * <h2>刻意不用被测代码</h2>
 * 这里全部走原生 JDBC，不复用 {@code Db} 或仓储类。用被测代码去验证被测代码，
 * 一旦辅助层有系统性错误（例如把异常翻译成"成功"），校验会跟着一起错。
 *
 * <h2>一次报告全部失败</h2>
 * 不变量之间会互相牵连，逐个抛出会让人修一个跑一次。因此收集所有失败后一次性报告。
 */
public final class Invariants {

    public record Failure(String rule, String detail) {
        @Override
        public String toString() {
            return "[" + rule + "] " + detail;
        }
    }

    private Invariants() {}

    /** 校验全部不变量；任一不成立即抛出，并列出全部问题。 */
    public static void assertAllHolds(DataSource ds, String auctionId) {
        List<Failure> failures = new ArrayList<>();
        failures.addAll(walletsAvailableNonNegative(ds));
        failures.addAll(frozenMatchesTwoLevels(ds));
        failures.addAll(ledgerReconcilesWithFrozen(ds));
        failures.addAll(auctionFrozenEqualsPrice(ds, auctionId));
        failures.addAll(bidChainStrictlyIncreasing(ds, auctionId));
        failures.addAll(oneBidPerRequest(ds));
        failures.addAll(bidRowsMatchSuccessfulRequests(ds, auctionId));
        failures.addAll(settlementIsConsistent(ds));
        failures.addAll(oneSettlementPerAuction(ds));

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder("不变量校验失败，共 " + failures.size() + " 项：\n");
            failures.forEach(f -> sb.append("  ").append(f).append('\n'));
            throw new AssertionError(sb.toString());
        }
    }

    /** INV-1：可用余额 = 总额 - 冻结额，任何时刻不得为负。 */
    public static List<Failure> walletsAvailableNonNegative(DataSource ds) {
        return query(ds,
                "SELECT user_id, total_balance, frozen_amount FROM wallets WHERE frozen_amount > total_balance",
                rs -> new Failure("INV-1 可用额非负",
                        "user=" + rs.getString(1) + " total=" + rs.getLong(2) + " frozen=" + rs.getLong(3)));
    }

    /**
     * INV-1：冻结额的两个粒度必须一致——钱包上的跨场冻结，
     * 等于该用户在所有场次的按场冻结之和。
     */
    public static List<Failure> frozenMatchesTwoLevels(DataSource ds) {
        return query(ds,
                "SELECT w.user_id, w.frozen_amount, COALESCE(x.s, 0) "
                        + "FROM wallets w "
                        + "LEFT JOIN (SELECT user_id AS uid, SUM(frozen_amount) AS s "
                        + "           FROM auction_participants GROUP BY user_id) x ON x.uid = w.user_id "
                        + "WHERE w.frozen_amount <> COALESCE(x.s, 0)",
                rs -> new Failure("INV-1 冻结额两级一致",
                        "user=" + rs.getString(1) + " 钱包冻结=" + rs.getLong(2) + " 按场冻结合计=" + rs.getLong(3)));
    }

    /**
     * INV-1：流水能解释当前冻结额。
     *
     * <p>{@code SETTLE} 与 {@code RELEASE} 一样消解冻结（赢家的冻结转为实际扣款），
     * 因此这里减掉它。这条不变量是"任意一次余额变化都能被单行解释"的汇总版本。
     */
    public static List<Failure> ledgerReconcilesWithFrozen(DataSource ds) {
        return query(ds,
                "SELECT l.user_id, l.net, w.frozen_amount "
                        + "FROM (SELECT user_id, "
                        + "        SUM(CASE entry_type WHEN 'FREEZE' THEN amount ELSE -amount END) AS net "
                        + "      FROM ledger_entries GROUP BY user_id) l "
                        + "JOIN wallets w ON w.user_id = l.user_id "
                        + "WHERE l.net <> w.frozen_amount",
                rs -> new Failure("INV-1 流水可解释冻结额",
                        "user=" + rs.getString(1) + " 流水净额=" + rs.getLong(2) + " 钱包冻结=" + rs.getLong(3)));
    }

    /**
     * INV-1：本场的冻结净额必须等于当前最高价。
     *
     * <p>这是最强的一条：它同时约束了"新领先者冻结多少"与"旧领先者释放多少"。
     * 任一侧算错，本场的冻结总额就不再等于价格，立刻暴露。
     *
     * <p>已结束的拍卖（{@code SETTLING}/{@code FINISHED}/{@code CANCELLED}）则要求本场冻结**归零**：
     * 结算的职责就是把冻结清干。若只写 {@code leader != null ⇒ 等于价格}，
     * 结算完成后领先者仍然挂着，这条不变量反而会误报——所以它必须跟着状态一起看。
     */
    public static List<Failure> auctionFrozenEqualsPrice(DataSource ds, String auctionId) {
        return query(ds,
                "SELECT a.status, a.leader_id, a.current_price, COALESCE(SUM(p.frozen_amount), 0) "
                        + "FROM auctions a LEFT JOIN auction_participants p ON p.auction_id = a.id "
                        + "WHERE a.id = ? GROUP BY a.status, a.leader_id, a.current_price",
                rs -> {
                    String status = rs.getString(1);
                    String leader = rs.getString(2);
                    long price = rs.getLong(3);
                    long frozen = rs.getLong(4);
                    boolean finished = "SETTLING".equals(status) || "FINISHED".equals(status)
                            || "CANCELLED".equals(status);
                    long expected = finished || leader == null ? 0L : price;
                    if (frozen == expected) {
                        return null;
                    }
                    return new Failure("INV-1 本场冻结等于最高价",
                            "status=" + status + " leader=" + leader + " 当前价=" + price
                                    + " 本场冻结合计=" + frozen + " 期望=" + expected);
                },
                auctionId);
    }

    /**
     * INV-2：领先者唯一，且出价链是严格递增的。
     *
     * <p>只断言"最终只有一个 leader"是不够的——那只能证明最后一刻没坏。
     * 这里校验整条历史：{@code server_seq} 严格递增无重复，金额每一步至少加一个最小加价，
     * 末条与拍卖行的领先者、当前价一致。任何一次丢失更新或错误接受都会破坏其中一条。
     */
    public static List<Failure> bidChainStrictlyIncreasing(DataSource ds, String auctionId) {
        List<Failure> failures = new ArrayList<>();
        try (Connection conn = ds.getConnection()) {
            long minIncrement;
            String leaderId;
            long currentPrice;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT min_increment, leader_id, current_price FROM auctions WHERE id = ?")) {
                ps.setString(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return List.of(new Failure("INV-2 出价链", "拍卖不存在: " + auctionId));
                    }
                    minIncrement = rs.getLong(1);
                    leaderId = rs.getString(2);
                    currentPrice = rs.getLong(3);
                }
            }

            long prevSeq = Long.MIN_VALUE;
            long prevAmount = Long.MIN_VALUE;
            String lastUser = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT server_seq, user_id, amount FROM bids WHERE auction_id = ? ORDER BY server_seq")) {
                ps.setString(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long seq = rs.getLong(1);
                        String user = rs.getString(2);
                        long amount = rs.getLong(3);
                        if (seq <= prevSeq) {
                            failures.add(new Failure("INV-2 seq 严格递增",
                                    "seq=" + seq + " 未大于上一条 " + prevSeq));
                        }
                        if (prevAmount != Long.MIN_VALUE && amount < prevAmount + minIncrement) {
                            failures.add(new Failure("INV-2 每步至少加一个最小加价",
                                    "上一条=" + prevAmount + " 本条=" + amount + " 最小加价=" + minIncrement));
                        }
                        prevSeq = seq;
                        prevAmount = amount;
                        lastUser = user;
                    }
                }
            }

            if (lastUser == null) {
                if (leaderId != null) {
                    failures.add(new Failure("INV-2 领先者与出价链一致", "无出价记录但领先者为 " + leaderId));
                }
            } else {
                if (!lastUser.equals(leaderId)) {
                    failures.add(new Failure("INV-2 领先者与出价链一致",
                            "末条出价用户=" + lastUser + " 拍卖行领先者=" + leaderId));
                }
                if (prevAmount != currentPrice) {
                    failures.add(new Failure("INV-2 当前价与出价链一致",
                            "末条出价=" + prevAmount + " 拍卖行当前价=" + currentPrice));
                }
            }
        } catch (SQLException e) {
            failures.add(new Failure("INV-2 出价链", "查询失败: " + e.getMessage()));
        }
        return failures;
    }

    /** INV-3：同一 (拍卖, 用户, requestId) 至多产生一条出价记录。 */
    public static List<Failure> oneBidPerRequest(DataSource ds) {
        return query(ds,
                "SELECT auction_id, user_id, request_id, COUNT(*) FROM bids "
                        + "GROUP BY auction_id, user_id, request_id HAVING COUNT(*) > 1",
                rs -> new Failure("INV-3 请求幂等",
                        "auction=" + rs.getString(1) + " user=" + rs.getString(2)
                                + " requestId=" + rs.getString(3) + " 出价条数=" + rs.getInt(4)));
    }

    /**
     * 无半完成状态：每一行成功出价都必须对应一行成功的幂等记录，反之亦然。
     *
     * <p>"价格已更新但资金未冻结"或"冻结了但没记账"这类半完成状态，会让上面几条
     * 各自看起来都成立，只有把两张表的计数对上才会暴露。
     */
    public static List<Failure> bidRowsMatchSuccessfulRequests(DataSource ds, String auctionId) {
        return query(ds,
                "SELECT (SELECT COUNT(*) FROM bids WHERE auction_id = ?) AS b, "
                        + "       (SELECT COUNT(*) FROM bid_requests WHERE auction_id = ? AND result_code = 'OK') AS r",
                rs -> {
                    int bids = rs.getInt("b");
                    int requests = rs.getInt("r");
                    if (bids == requests) {
                        return null;
                    }
                    return new Failure("无半完成状态",
                            "出价记录=" + bids + " 成功幂等记录=" + requests + "（应相等）");
                },
                auctionId, auctionId);
    }

    // ------------------------------------------------------------------

    /**
     * INV-4：一场拍卖至多一条成交记录，且成交记录必须能被流水解释。
     *
     * <p>这条覆盖了原文里"重复定时任务 / 重复请求 / 服务重启 / 两个实例同时触发
     * 都不能重复扣款、重复生成成交记录或重复记流水"的全部可观测后果。
     * 它比"查一下结算行数是不是 1"更强的地方在于：
     * <ul>
     *   <li>{@code settle_rows = 1} 同时否定了"漏扣"（0 行）与"重复扣款"（2 行）；</li>
     *   <li>{@code settle_sum} 与 {@code final_price} 相等，否定"扣两次钱记一条流水"；</li>
     *   <li>{@code settle_user} 与赢家一致，否定"扣错人"；</li>
     *   <li>{@code frozen_left = 0} 否定"结算了但冻结没清"——钱会永久悬着，是资金系统最隐蔽的缺陷。</li>
     * </ul>
     * 无赢家的场次（{@code NO_BIDS} / {@code CANCELLED}）则要求成交价为 0 且没有任何 SETTLE 流水，
     * 即原文的"不得产生扣款"。
     */
    public static List<Failure> settlementIsConsistent(DataSource ds) {
        return query(ds,
                "SELECT s.auction_id, s.winner_id, s.final_price, s.reason, "
                        + "  (SELECT COUNT(*) FROM ledger_entries l WHERE l.auction_id = s.auction_id "
                        + "     AND l.entry_type = 'SETTLE') AS settle_rows, "
                        + "  (SELECT COALESCE(SUM(l.amount), 0) FROM ledger_entries l WHERE l.auction_id = s.auction_id "
                        + "     AND l.entry_type = 'SETTLE') AS settle_sum, "
                        + "  (SELECT l.user_id FROM ledger_entries l WHERE l.auction_id = s.auction_id "
                        + "     AND l.entry_type = 'SETTLE' LIMIT 1) AS settle_user, "
                        + "  (SELECT COALESCE(SUM(p.frozen_amount), 0) FROM auction_participants p "
                        + "     WHERE p.auction_id = s.auction_id) AS frozen_left "
                        + "FROM settlements s",
                rs -> {
                    String auctionId = rs.getString("auction_id");
                    String winner = rs.getString("winner_id");
                    long finalPrice = rs.getLong("final_price");
                    String reason = rs.getString("reason");
                    int settleRows = rs.getInt("settle_rows");
                    long settleSum = rs.getLong("settle_sum");
                    String settleUser = rs.getString("settle_user");
                    long frozenLeft = rs.getLong("frozen_left");

                    if (frozenLeft != 0) {
                        return new Failure("INV-4 结算后冻结归零",
                                "auction=" + auctionId + " 本场残留冻结=" + frozenLeft);
                    }
                    if (settleRows > 1) {
                        return new Failure("INV-4 不得重复扣款",
                                "auction=" + auctionId + " SETTLE 流水条数=" + settleRows);
                    }
                    if (winner == null) {
                        if (finalPrice != 0 || settleRows != 0) {
                            return new Failure("INV-4 无赢家不得扣款",
                                    "auction=" + auctionId + " reason=" + reason + " 成交价=" + finalPrice
                                            + " SETTLE 流水条数=" + settleRows);
                        }
                        return null;
                    }
                    if (!"TIMEOUT".equals(reason)) {
                        return new Failure("INV-4 有赢家必有成交原因",
                                "auction=" + auctionId + " winner=" + winner + " reason=" + reason);
                    }
                    if (settleRows != 1 || settleSum != finalPrice || !winner.equals(settleUser)) {
                        return new Failure("INV-4 扣款金额与赢家可解释",
                                "auction=" + auctionId + " winner=" + winner + " 成交价=" + finalPrice
                                        + " SETTLE 条数=" + settleRows + " 合计=" + settleSum
                                        + " 扣款用户=" + settleUser);
                    }
                    return null;
                });
    }

    /** INV-4：一场拍卖不得有多条成交记录（主键应已阻止，此处再确认一次）。 */
    public static List<Failure> oneSettlementPerAuction(DataSource ds) {
        return query(ds,
                "SELECT auction_id, COUNT(*) FROM settlements GROUP BY auction_id HAVING COUNT(*) > 1",
                rs -> new Failure("INV-4 唯一结算",
                        "auction=" + rs.getString(1) + " 成交记录条数=" + rs.getInt(2)));
    }

    @FunctionalInterface
    private interface FailureMapper {
        /** 返回 null 表示这一行没问题。 */
        Failure map(ResultSet rs) throws SQLException;
    }

    private static List<Failure> query(DataSource ds, String sql, FailureMapper mapper, Object... args) {
        List<Failure> failures = new ArrayList<>();
        try (Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Failure failure = mapper.map(rs);
                    if (failure != null) {
                        failures.add(failure);
                    }
                }
            }
        } catch (SQLException e) {
            failures.add(new Failure("校验 SQL 执行失败", sql + " -> " + e.getMessage()));
        }
        return failures;
    }
}
