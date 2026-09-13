package com.bidarena.auction.adapter;

import com.bidarena.auction.domain.AuctionStatus;
import com.bidarena.shared.BizException;
import com.bidarena.shared.Db;
import com.bidarena.shared.ErrorCode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * {@code auctions} / {@code auction_participants} / {@code bids} / {@code bid_requests} 的持久化。
 *
 * <p>所有会改变判断结果的读取都在事务内并显式加锁；本类不提供"裸读后由调用方自行决定"的方法。
 * {@code auction_participants.frozen_amount} 列由 wallet 上下文写入，本类只读不写。
 */
public class AuctionRepository {

    private static final String SELECT_AUCTION =
            "SELECT a.id, a.title, a.description, a.status, a.start_price, a.min_increment, "
                    + "a.current_price, a.leader_id, a.ends_at, a.extension_count, a.seq, a.duration_seconds, "
                    + "(SELECT COUNT(*) FROM auction_participants p WHERE p.auction_id = a.id) AS participant_count "
                    + "FROM auctions a ";

    private final DataSource dataSource;

    public AuctionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record AuctionRow(
            String id,
            String title,
            String description,
            AuctionStatus status,
            long startPrice,
            long minIncrement,
            long currentPrice,
            String leaderId,
            Instant endsAt,
            int extensionCount,
            long seq,
            int durationSeconds,
            int participantCount) {}

    /** 一条出价。{@code seq} 是单场拍卖内单调递增的服务端序号，客户端据此检测丢事件。 */
    public record BidRow(long id, String userId, long amount, String requestId, long seq, Instant serverTime) {}

    /** 参与记录；{@code joinedAt} 用于 join 接口回显。 */
    public record ParticipantRow(String auctionId, String userId, Instant joinedAt) {}

    /** 幂等记录。{@code resultPrice}/{@code resultSeq} 仅在成功完成时有值。 */
    public record RequestRow(String status, String resultCode, Long resultPrice, Long resultSeq) {
        public boolean done() {
            return "DONE".equals(status);
        }

        public boolean succeeded() {
            return done() && "OK".equals(resultCode);
        }
    }

    // ---------------------------- 事务内 ----------------------------

    /**
     * 锁定拍卖行并读取状态。这是出价事务的第一把锁，也是"唯一赢家"的全部依据：
     * 同一场拍卖的所有出价在此串行化，后到者看到的一定是先到者提交后的价格。
     */
    public AuctionRow lockAuction(Connection conn, String auctionId) throws SQLException {
        return Db.queryOne(conn, SELECT_AUCTION + "WHERE a.id = ? FOR UPDATE",
                AuctionRepository::mapAuction, auctionId);
    }

    /**
     * 更新价格与领先者。
     *
     * <p>{@code AND seq = ?} 是刻意加的守卫：要求"我以为的旧 seq"与库里一致。
     * 不一致说明这一行在我们身下被改过（锁失效或有人绕过服务层写库），
     * 此时必须报错而不是继续——静默丢失一次更新正是并发缺陷最典型的样子。
     */
    public void applyBid(Connection conn, String auctionId, long expectedSeq, long newPrice, String newLeaderId,
            Instant newEndsAt, int newExtensionCount, long newSeq) throws SQLException {
        int affected = Db.update(conn,
                "UPDATE auctions SET current_price = ?, leader_id = ?, ends_at = ?, extension_count = ?, seq = ? "
                        + "WHERE id = ? AND seq = ? AND status = 'RUNNING'",
                newPrice, newLeaderId, Db.ts(newEndsAt), newExtensionCount, newSeq, auctionId, expectedSeq);
        if (affected != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "出价未生效：拍卖行已被并发修改（seq 不匹配）",
                    Map.of("auctionId", auctionId, "expectedSeq", expectedSeq));
        }
    }

    public boolean isParticipant(Connection conn, String auctionId, String userId) throws SQLException {
        Integer count = Db.queryOne(conn,
                "SELECT COUNT(*) FROM auction_participants WHERE auction_id = ? AND user_id = ?",
                rs -> rs.getInt(1), auctionId, userId);
        return count != null && count > 0;
    }

    /**
     * 加入拍卖间。重复加入必须是幂等的（同一用户重复点"加入"不该报错），
     * 因此用 ON DUPLICATE KEY UPDATE 而非先查后插。
     *
     * <p>不能用 INSERT IGNORE：它会把外键失败一起吞掉，使"用户不存在"变成静默成功。
     */
    public void join(Connection conn, String auctionId, String userId, String participantType)
            throws SQLException {
        Db.update(conn,
                "INSERT INTO auction_participants (auction_id, user_id, participant_type) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE user_id = user_id",
                auctionId, userId, participantType);
    }

    public void insertBid(Connection conn, String auctionId, String userId, long amount, String requestId,
            long serverSeq, Instant serverTime) throws SQLException {
        Db.update(conn,
                "INSERT INTO bids (auction_id, user_id, amount, request_id, server_seq, server_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                auctionId, userId, amount, requestId, serverSeq, Db.ts(serverTime));
    }

    /** 读幂等记录并加锁；返回 null 表示该 requestId 从未出现过。 */
    public RequestRow lockRequest(Connection conn, String auctionId, String userId, String requestId)
            throws SQLException {
        return Db.queryOne(conn,
                "SELECT status, result_code, result_price, result_seq FROM bid_requests "
                        + "WHERE auction_id = ? AND user_id = ? AND request_id = ? FOR UPDATE",
                rs -> new RequestRow(rs.getString("status"), rs.getString("result_code"),
                        (Long) rs.getObject("result_price"), (Long) rs.getObject("result_seq")),
                auctionId, userId, requestId);
    }

    /** 占位：声明"这个 requestId 正在处理中"。主键本身就是并发控制，锁失效也不会出现两条。 */
    public void insertRequestPending(Connection conn, String auctionId, String userId, String requestId)
            throws SQLException {
        Db.update(conn,
                "INSERT INTO bid_requests (auction_id, user_id, request_id, result_code, status) "
                        + "VALUES (?, ?, ?, NULL, 'PENDING')",
                auctionId, userId, requestId);
    }

    /** 事务内成功收尾：占位行转终态并写入首次结果快照，供重放使用。 */
    public void markRequestDone(Connection conn, String auctionId, String userId, String requestId,
            String resultCode, Long resultPrice, Long resultSeq) throws SQLException {
        Db.update(conn,
                "UPDATE bid_requests SET status = 'DONE', result_code = ?, result_price = ?, result_seq = ? "
                        + "WHERE auction_id = ? AND user_id = ? AND request_id = ?",
                resultCode, resultPrice, resultSeq, auctionId, userId, requestId);
    }

    /**
     * 事务外记录一次业务拒绝。
     *
     * <p>业务变更必须回滚（被拒的出价不能留下任何痕迹），但"这个 requestId 已被处理过"这一事实
     * **必须留下**：否则重试会重新求值，在状态已变化时给出不同结果，幂等性就无从验证。
     *
     * <p>用 upsert 而非 UPDATE，因为被拒时占位行已随回滚消失。不使用 {@code VALUES()} 函数
     * （8.0.20 起废弃），改为重复传参。
     */
    public void upsertRejectedRequest(String auctionId, String userId, String requestId, String resultCode) {
        Db.tx(dataSource, conn -> {
            Db.update(conn,
                    "INSERT INTO bid_requests (auction_id, user_id, request_id, result_code, status) "
                            + "VALUES (?, ?, ?, ?, 'DONE') "
                            + "ON DUPLICATE KEY UPDATE status = 'DONE', result_code = ?",
                    auctionId, userId, requestId, resultCode, resultCode);
            return null;
        });
    }

    /** 结算扫描：走 idx_auctions_status_ends，不扫全表。 */
    public List<String> findDueAuctionIds(Connection conn, Instant now, int limit) throws SQLException {
        return Db.queryList(conn,
                "SELECT id FROM auctions WHERE status = 'RUNNING' AND ends_at IS NOT NULL AND ends_at <= ? "
                        + "ORDER BY ends_at ASC LIMIT ?",
                rs -> rs.getString("id"), Db.ts(now), limit);
    }

    /** 状态转换。带上 from 条件，使并发触发只有一次能成功。 */
    public void updateStatus(Connection conn, String auctionId, AuctionStatus from, AuctionStatus to) {
        try {
            int affected = Db.update(conn, "UPDATE auctions SET status = ? WHERE id = ? AND status = ?",
                    to.name(), auctionId, from.name());
            if (affected != 1) {
                throw new BizException(ErrorCode.INVALID_STATE,
                        "状态转换未生效：拍卖已不在 " + from + " 状态", Map.of("auctionId", auctionId));
            }
        } catch (SQLException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "状态转换失败", Map.of("raw", String.valueOf(e.getMessage())));
        }
    }

    /** 管理员创建拍品：落为 DRAFT 且 ends_at 为 NULL，不自动倒计时。 */
    public void insert(Connection conn, String id, String title, String description, long startPrice,
            long minIncrement, int durationSeconds) throws SQLException {
        Db.update(conn,
                "INSERT INTO auctions (id, title, description, status, start_price, min_increment, "
                        + "duration_seconds, current_price, leader_id, ends_at, extension_count, seq) "
                        + "VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, NULL, NULL, 0, 0)",
                id, title, description, startPrice, minIncrement, durationSeconds, startPrice);
    }

    /** 开始拍卖：写入截止时间，倒计时从这一刻起算。 */
    public void start(Connection conn, String auctionId, Instant endsAt) throws SQLException {
        int affected = Db.update(conn,
                "UPDATE auctions SET status = 'RUNNING', ends_at = ?, seq = seq + 1 "
                        + "WHERE id = ? AND status = 'DRAFT' AND ends_at IS NULL",
                Db.ts(endsAt), auctionId);
        if (affected != 1) {
            throw new BizException(ErrorCode.INVALID_STATE,
                    "拍卖无法开始：不是草稿状态或已经开始过", Map.of("auctionId", auctionId));
        }
    }

    public List<Long> recentBidSeqs(Connection conn, String auctionId, int limit) throws SQLException {
        return Db.queryList(conn,
                "SELECT server_seq FROM bids WHERE auction_id = ? ORDER BY server_seq DESC LIMIT ?",
                rs -> rs.getLong(1), auctionId, limit);
    }

    // ---------------------------- 事务外读 ----------------------------

    public AuctionRow load(String auctionId) {
        return Db.read(dataSource, conn -> Db.queryOne(conn, SELECT_AUCTION + "WHERE a.id = ?",
                AuctionRepository::mapAuction, auctionId));
    }

    /**
     * 列表分页；{@code status} 为 {@code null} 时不筛选。
     *
     * <p>筛选与分页都下推到 SQL：在 Java 里先取全表再过滤，会随数据量增长而变慢，
     * 而且 {@code total} 会算错（分页总数必须是**筛选后**的总数）。
     */
    public List<AuctionRow> list(AuctionStatus status, int limit, int offset) {
        return Db.read(dataSource, conn -> status == null
                ? Db.queryList(conn,
                        SELECT_AUCTION + "ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
                        AuctionRepository::mapAuction, limit, offset)
                : Db.queryList(conn,
                        SELECT_AUCTION + "WHERE a.status = ? ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
                        AuctionRepository::mapAuction, status.name(), limit, offset));
    }

    /** 与 {@link #list} 同一筛选条件下的总数，用于分页元数据。 */
    public long count(AuctionStatus status) {
        return Db.read(dataSource, conn -> {
            Long total = status == null
                    ? Db.queryOne(conn, "SELECT COUNT(*) FROM auctions", rs -> rs.getLong(1))
                    : Db.queryOne(conn, "SELECT COUNT(*) FROM auctions WHERE status = ?",
                            rs -> rs.getLong(1), status.name());
            return total == null ? 0L : total;
        });
    }

    /** 出价分页，按 {@code server_seq} 倒序（最新在前）。 */
    public List<BidRow> pageBids(String auctionId, int limit, int offset) {
        return Db.read(dataSource, conn -> Db.queryList(conn,
                "SELECT id, user_id, amount, request_id, server_seq, server_time FROM bids "
                        + "WHERE auction_id = ? ORDER BY server_seq DESC LIMIT ? OFFSET ?",
                rs -> new BidRow(rs.getLong("id"), rs.getString("user_id"), rs.getLong("amount"),
                        rs.getString("request_id"), rs.getLong("server_seq"), Db.instant(rs, "server_time")),
                auctionId, limit, offset));
    }

    public long countBids(String auctionId) {
        return Db.read(dataSource, conn -> {
            Long total = Db.queryOne(conn, "SELECT COUNT(*) FROM bids WHERE auction_id = ?",
                    rs -> rs.getLong(1), auctionId);
            return total == null ? 0L : total;
        });
    }

    /** 读参与记录；不存在返回 null。用于 join 后回显 {@code joinedAt}。 */
    public ParticipantRow findParticipant(String auctionId, String userId) {
        return Db.read(dataSource, conn -> findParticipant(conn, auctionId, userId));
    }

    /** 事务内版本：join 之后必须在同一事务里读回，否则可能读到别的连接尚未提交的状态。 */
    public ParticipantRow findParticipant(Connection conn, String auctionId, String userId) throws SQLException {
        return Db.queryOne(conn,
                "SELECT auction_id, user_id, joined_at FROM auction_participants "
                        + "WHERE auction_id = ? AND user_id = ?",
                rs -> new ParticipantRow(rs.getString("auction_id"), rs.getString("user_id"),
                        Db.instant(rs, "joined_at")),
                auctionId, userId);
    }

    public void insertOutsideTx(String id, String title, String description, long startPrice, long minIncrement,
            int durationSeconds) {
        Db.tx(dataSource, conn -> {
            insert(conn, id, title, description, startPrice, minIncrement, durationSeconds);
            return null;
        });
    }

    public void startOutsideTx(String auctionId, Instant endsAt) {
        Db.tx(dataSource, conn -> {
            start(conn, auctionId, endsAt);
            return null;
        });
    }

    /**
     * 管理员开始拍卖：在同一事务内锁行、读时长、算截止时间。
     *
     * <p>时长必须在锁内读取：若先在事务外读出 {@code duration_seconds}，
     * 再在事务里写截止时间，中间那一小段窗口里管理员改了时长也不会生效，
     * 而调用方会以为生效了。截止时间同样必须用 {@link Db#now} 的数据库时间。
     *
     * @return 实际写入的截止时间
     */
    public Instant startNow(String auctionId) {
        return Db.tx(dataSource, conn -> {
            AuctionRow auction = lockAuction(conn, auctionId);
            if (auction == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "拍卖不存在", Map.of("auctionId", auctionId));
            }
            Instant endsAt = Db.now(conn).plusSeconds(auction.durationSeconds());
            start(conn, auctionId, endsAt);
            return endsAt;
        });
    }

    private static AuctionRow mapAuction(ResultSet rs) throws SQLException {
        return new AuctionRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                AuctionStatus.parse(rs.getString("status")),
                rs.getLong("start_price"),
                rs.getLong("min_increment"),
                rs.getLong("current_price"),
                rs.getString("leader_id"),
                Db.instant(rs, "ends_at"),
                rs.getInt("extension_count"),
                rs.getLong("seq"),
                rs.getInt("duration_seconds"),
                rs.getInt("participant_count"));
    }
}
