package com.bidarena.support;

import com.bidarena.auction.adapter.AuctionRepository;
import com.bidarena.auction.application.BidService;
import com.bidarena.wallet.adapter.WalletRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import javax.sql.DataSource;

/**
 * 测试夹具：直接用 SQL 造数据，**不经过被测代码**。
 *
 * <p>若用被测的写路径造前置状态，一旦写路径本身有错，测试会因为"造出了一个错误的前提"
 * 而失败或误通过，届时很难判断问题出在造数据还是在被验证的逻辑上。
 *
 * <h2>时间一律由数据库算</h2>
 * 所有相对时间都用 {@code DATE_ADD(NOW(6), INTERVAL ? SECOND)} 表达，而不是在 Java 里
 * 算好再传进去。出价事务的时间基准是数据库时间（D-5），若夹具用 JVM 时钟构造截止时间，
 * 两台机器有偏差时测试结果会随环境漂移——那种失败最难排查。
 */
public final class Fixtures {

    private Fixtures() {}

    /** 按生产接线方式装配被测服务，避免测试自己拼一套注入关系而与线上不一致。 */
    public static BidService bidService(DataSource ds) {
        return new BidService(ds, new AuctionRepository(ds), new WalletRepository(ds));
    }

    /** 建一个可用余额为 {@code balance} 的用户。 */
    public static void user(DataSource ds, String id, long balance) {
        exec(ds, "INSERT INTO users (id, email, display_name, password_hash, role, status) "
                + "VALUES (?, ?, ?, ?, 'BIDDER', 'ACTIVE')", id, id + "@test.local", id, "x");
        exec(ds, "INSERT INTO wallets (user_id, total_balance, frozen_amount) VALUES (?, ?, 0)", id, balance);
    }

    /** 建一个草稿拍品（{@code ends_at} 为 NULL，不倒计时）。 */
    public static void draftAuction(DataSource ds, String id, long startPrice, long minIncrement,
            int durationSeconds) {
        exec(ds, "INSERT INTO auctions (id, title, description, status, start_price, min_increment, "
                        + "duration_seconds, current_price, leader_id, ends_at, extension_count, seq) "
                        + "VALUES (?, ?, NULL, 'DRAFT', ?, ?, ?, ?, NULL, NULL, 0, 0)",
                id, "测试拍品 " + id, startPrice, minIncrement, durationSeconds, startPrice);
    }

    /** 开始拍卖：截止时间 = 数据库当前时间 + {@code secondsFromNow} 秒。 */
    public static void startAuction(DataSource ds, String id, int secondsFromNow) {
        exec(ds, "UPDATE auctions SET status = 'RUNNING', "
                + "ends_at = DATE_ADD(NOW(6), INTERVAL ? SECOND) WHERE id = ?", secondsFromNow, id);
    }

    /**
     * 直接把截止时间改到"数据库当前时间 + n 秒"。
     *
     * <p>用于验证延时上限：一次合法出价会把截止时间推后 10 秒，若不重置就无法在
     * 几秒内连续触发三次延时。这里改的是测试自己造的前置状态，不是绕过被测逻辑。
     */
    public static void setEndsAtIn(DataSource ds, String id, int secondsFromNow) {
        exec(ds, "UPDATE auctions SET ends_at = DATE_ADD(NOW(6), INTERVAL ? SECOND) WHERE id = ?",
                secondsFromNow, id);
    }

    public static void join(DataSource ds, String auctionId, String userId) {
        exec(ds, "INSERT INTO auction_participants (auction_id, user_id, participant_type) VALUES (?, ?, 'HUMAN')",
                auctionId, userId);
    }

    // ---------------------------- 断言用的读 ----------------------------

    public static String leader(DataSource ds, String auctionId) {
        return scalarString(ds, "SELECT leader_id FROM auctions WHERE id = ?", auctionId);
    }

    public static long currentPrice(DataSource ds, String auctionId) {
        return scalar(ds, "SELECT current_price FROM auctions WHERE id = ?", auctionId);
    }

    public static int extensionCount(DataSource ds, String auctionId) {
        return (int) scalar(ds, "SELECT extension_count FROM auctions WHERE id = ?", auctionId);
    }

    public static long seq(DataSource ds, String auctionId) {
        return scalar(ds, "SELECT seq FROM auctions WHERE id = ?", auctionId);
    }

    public static long frozen(DataSource ds, String userId) {
        return scalar(ds, "SELECT frozen_amount FROM wallets WHERE user_id = ?", userId);
    }

    public static long available(DataSource ds, String userId) {
        return scalar(ds, "SELECT total_balance - frozen_amount FROM wallets WHERE user_id = ?", userId);
    }

    public static long auctionFrozen(DataSource ds, String auctionId) {
        return scalar(ds, "SELECT COALESCE(SUM(frozen_amount), 0) FROM auction_participants WHERE auction_id = ?",
                auctionId);
    }

    public static int count(DataSource ds, String sql, Object... args) {
        return (int) scalar(ds, sql, args);
    }

    /** 某用户在某类型上的流水条数。 */
    public static int ledgerCount(DataSource ds, String userId, String auctionId, String entryType) {
        return count(ds, "SELECT COUNT(*) FROM ledger_entries WHERE user_id = ? AND auction_id = ? "
                + "AND entry_type = ?", userId, auctionId, entryType);
    }

    public static long ledgerSum(DataSource ds, String userId, String auctionId, String entryType) {
        return scalar(ds, "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE user_id = ? "
                + "AND auction_id = ? AND entry_type = ?", userId, auctionId, entryType);
    }

    // ---------------------------- 执行 ----------------------------

    public static void exec(DataSource ds, String sql, Object... args) {
        try (Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("执行失败: " + sql + " -> " + e.getMessage(), e);
        }
    }

    public static long scalar(DataSource ds, String sql, Object... args) {
        try (Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("期望一行结果: " + sql);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql + " -> " + e.getMessage(), e);
        }
    }

    public static String scalarString(DataSource ds, String sql, Object... args) {
        try (Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql + " -> " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else {
                ps.setObject(i + 1, args[i]);
            }
        }
    }
}
