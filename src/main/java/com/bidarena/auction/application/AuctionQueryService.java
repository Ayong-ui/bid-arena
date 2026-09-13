package com.bidarena.auction.application;

import com.bidarena.api.ApiTime;
import com.bidarena.api.PageQuery;
import com.bidarena.auction.adapter.AuctionRepository;
import com.bidarena.auction.adapter.AuctionRepository.AuctionRow;
import com.bidarena.auction.adapter.AuctionRepository.BidRow;
import com.bidarena.auction.adapter.AuctionViews;
import com.bidarena.auction.adapter.SettlementRepository;
import com.bidarena.auction.adapter.SettlementRepository.SettlementRow;
import com.bidarena.auction.domain.AuctionStatus;
import com.bidarena.shared.BizException;
import com.bidarena.shared.Db;
import com.bidarena.shared.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 拍卖、出价、成交结果的只读查询。
 *
 * <p>{@code serverTime} 一律取数据库时间（D-5），即使这里只是"信息字段"：
 * 客户端会用它与本机时间比对来估算剩余时间。若服务端返回 JVM 时间而截止时间基于数据库时间，
 * 两台机器的时钟差会直接变成客户端进度条的偏差，看起来像"倒计时不准"的业务缺陷。
 *
 * <p>这里刻意不缓存：拍卖的 {@code currentPrice}/{@code seq} 每秒都可能变，
 * 缓存带来的"看起来对但不一致"比多打一次数据库危险得多。
 */
public class AuctionQueryService {

    private final DataSource dataSource;
    private final AuctionRepository auctions;
    private final SettlementRepository settlements;

    public AuctionQueryService(DataSource dataSource, AuctionRepository auctions, SettlementRepository settlements) {
        this.dataSource = dataSource;
        this.auctions = auctions;
        this.settlements = settlements;
    }

    public AuctionViews.Snapshot snapshot(String auctionId) {
        AuctionRow row = auctions.load(auctionId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "拍卖不存在", Map.of("auctionId", auctionId));
        }
        return AuctionViews.Snapshot.of(row, serverTime());
    }

    public PageQuery.Page<AuctionViews.Snapshot> auctions(AuctionStatus status, PageQuery page) {
        Instant now = serverTime();
        List<AuctionViews.Snapshot> items = auctions.list(status, page.limit(), page.offset()).stream()
                .map(row -> AuctionViews.Snapshot.of(row, now))
                .toList();
        return new PageQuery.Page<>(items, page.page(), page.size(), auctions.count(status));
    }

    /** 出价列表。拍卖不存在时返回 404，而不是空页——空页会让客户端以为"这场拍卖还没有出价"。 */
    public PageQuery.Page<AuctionViews.Bid> bids(String auctionId, PageQuery page) {
        requireAuction(auctionId);
        List<BidRow> rows = auctions.pageBids(auctionId, page.limit(), page.offset());
        return new PageQuery.Page<>(
                rows.stream().map(AuctionViews.Bid::of).toList(), page.page(), page.size(),
                auctions.countBids(auctionId));
    }

    /**
     * 成交结果。尚未结算时返回 404。
     *
     * <p>{@code status} 取拍卖的当前状态而不是成交记录里的原因：成交记录只回答"谁赢了、多少钱"，
     * 而取消与到期结算都产生成交记录，调用方需要能从 {@code status} 直接区分 {@code FINISHED} 与
     * {@code CANCELLED}，不必去解析 {@code reason}。
     */
    public AuctionViews.Result result(String auctionId) {
        AuctionRow auction = requireAuction(auctionId);
        SettlementRow settlement = settlements.load(auctionId);
        if (settlement == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "拍卖尚未结算", Map.of("auctionId", auctionId));
        }
        return new AuctionViews.Result(
                settlement.auctionId(),
                auction.status().name(),
                settlement.winnerId(),
                settlement.finalPrice(),
                settlement.reason().name(),
                ApiTime.format(settlement.createdAt()));
    }

    private AuctionRow requireAuction(String auctionId) {
        AuctionRow row = auctions.load(auctionId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "拍卖不存在", Map.of("auctionId", auctionId));
        }
        return row;
    }

    /**
     * 是否为该场的参与者。WebSocket 订阅资格的唯一判据。
     *
     * <p>不在这里判断拍卖是否存在：调用方（{@code AuctionSocketHandler}）已经先读过快照，
     * 那一步就会把“不存在”抛成 NOT_FOUND，再查一次只是多一次往返。
     */
    public boolean isParticipant(String auctionId, String userId) {
        return Db.read(dataSource, conn -> auctions.isParticipant(conn, auctionId, userId));
    }

    private Instant serverTime() {
        return Db.read(dataSource, Db::now);
    }
}
