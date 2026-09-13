package com.bidarena.auction.persistence;

import com.bidarena.auction.domain.SettlementReason;
import com.bidarena.shared.Db;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/**
 * {@code settlements} 表的持久化。一场拍卖最多一条成交记录。
 *
 * <p>唯一性有两层：主键 {@code (auction_id)} 是硬保证，{@link #lock} 是让重复触发
 * 能**返回已有结果**而不是撞错误。两层缺一不可——只有主键的话，重复触发会变成一个
 * 报错，而"重复触发"在这里是正常现象（重试、双实例、多轮定时任务都会产生）。
 */
public class SettlementRepository {

    /** {@code finalPrice} 为 0 表示没有成交发生（{@code winnerId} 必为 null）。 */
    public record SettlementRow(
            String auctionId, String winnerId, long finalPrice, SettlementReason reason, Instant createdAt) {}

    private final DataSource dataSource;

    public SettlementRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 事务内读取并加锁；返回 null 表示尚未结算。 */
    public SettlementRow lock(Connection conn, String auctionId) throws SQLException {
        return Db.queryOne(conn,
                "SELECT auction_id, winner_id, final_price, reason, created_at FROM settlements "
                        + "WHERE auction_id = ? FOR UPDATE",
                SettlementRepository::map, auctionId);
    }

    /**
     * 写入成交记录。
     *
     * <p>用不带 {@code IGNORE} 的普通 INSERT：主键冲突意味着"本场已经有成交记录"，
     * 这是必须暴露的事实，绝不能被静默吞掉。正常的重复触发在 {@link #lock} 那一步
     * 就已经返回了，走不到这里。
     */
    public void insert(Connection conn, String auctionId, String winnerId, long finalPrice,
            SettlementReason reason, Instant createdAt) throws SQLException {
        Db.update(conn,
                "INSERT INTO settlements (auction_id, winner_id, final_price, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                auctionId, winnerId, finalPrice, reason.name(), Db.ts(createdAt));
    }

    public SettlementRow load(String auctionId) {
        return Db.read(dataSource, conn -> Db.queryOne(conn,
                "SELECT auction_id, winner_id, final_price, reason, created_at FROM settlements "
                        + "WHERE auction_id = ?",
                SettlementRepository::map, auctionId));
    }

    public List<SettlementRow> list(int limit, int offset) {
        return Db.read(dataSource, conn -> Db.queryList(conn,
                "SELECT auction_id, winner_id, final_price, reason, created_at FROM settlements "
                        + "ORDER BY created_at DESC, auction_id DESC LIMIT ? OFFSET ?",
                SettlementRepository::map, limit, offset));
    }

    private static SettlementRow map(java.sql.ResultSet rs) throws SQLException {
        return new SettlementRow(
                rs.getString("auction_id"),
                rs.getString("winner_id"),
                rs.getLong("final_price"),
                SettlementReason.valueOf(rs.getString("reason")),
                Db.instant(rs, "created_at"));
    }
}
