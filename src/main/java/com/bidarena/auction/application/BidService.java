package com.bidarena.auction.application;

import com.bidarena.auction.adapter.AuctionRepository;
import com.bidarena.auction.adapter.AuctionRepository.AuctionRow;
import com.bidarena.auction.adapter.AuctionRepository.RequestRow;
import com.bidarena.auction.domain.AuctionStatus;
import com.bidarena.shared.BizException;
import com.bidarena.shared.Db;
import com.bidarena.shared.ErrorCode;
import com.bidarena.wallet.adapter.WalletRepository;
import com.bidarena.wallet.domain.LedgerType;
import com.bidarena.wallet.adapter.WalletRepository.WalletRow;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 出价事务：本项目唯一改变"谁领先、多少钱被冻结"的地方。
 *
 * <h2>一个事务，六个步骤</h2>
 * <ol>
 *   <li><b>取数据库时间</b>——截止与延时判定不能用应用机器时钟（D-5）；</li>
 *   <li><b>锁拍卖行</b>——同一场拍卖的所有出价在此串行化，"唯一赢家"的全部依据；</li>
 *   <li><b>判幂等</b>——在锁内读 requestId，命中终态直接返回首次结果；</li>
 *   <li><b>校验业务规则</b>——状态、截止、是否参与、是否满足最小加价；</li>
 *   <li><b>锁资金</b>——本场冻结行与钱包行，均按 user_id 升序（死锁避免）；</li>
 *   <li><b>写入并收尾</b>——释放旧领先者、冻结差额、更新价格与截止时间、落流水与出价。</li>
 * </ol>
 *
 * <h2>为什么锁顺序必须固定</h2>
 * 一次出价可能同时改两个用户（新领先者与旧领先者）的钱包。若两场拍卖同时换领先者，
 * 一个按 A→B 加锁、另一个按 B→A，就会互相等对方持锁而死锁。
 * 统一按 {@code user_id} 升序后，这类死锁从"偶发"变成"不存在"。
 * 即便仍有残余死锁（1213），{@link Db#tx} 会有限重试。
 *
 * <h2>失败分支持久化</h2>
 * 业务拒绝会回滚全部业务变更，但"该 requestId 已被处理过"这一事实会被单独记下来，
 * 使重试返回同一结果。见 {@link AuctionRepository#upsertRejectedRequest}。
 */
public class BidService {

    private static final Logger log = LoggerFactory.getLogger(BidService.class);

    /** 原文规则：截止前 5 秒内的合法出价触发延时。 */
    public static final long EXTENSION_WINDOW_SECONDS = 5;
    /** 原文规则：以当前截止时间为基准延长 10 秒。 */
    public static final long EXTENSION_SECONDS = 10;
    /** 原文规则：每场最多延长 3 次，达到上限后出价仍成功但不再延时。 */
    public static final int MAX_EXTENSIONS = 3;

    /**
     * 这些拒绝是"该次出价已被评估过"的结论，值得写入幂等记录；
     * 参数非法、拍卖不存在等属于请求本身有问题，不记录。
     */
    private static final Set<ErrorCode> RECORDABLE_REJECTIONS = Set.of(
            ErrorCode.BID_TOO_LOW,
            ErrorCode.BID_LATE,
            ErrorCode.NOT_JOINED,
            ErrorCode.INSUFFICIENT_BALANCE,
            ErrorCode.INVALID_STATE);

    private final DataSource dataSource;
    private final AuctionRepository auctions;
    private final WalletRepository wallets;

    public BidService(DataSource dataSource, AuctionRepository auctions, WalletRepository wallets) {
        this.dataSource = dataSource;
        this.auctions = auctions;
        this.wallets = wallets;
    }

    /**
     * 出价结果。
     *
     * <p>重放（{@code idempotent = true}）时，{@code price} 与 {@code seq} 取**首次**结果的快照，
     * 因此同一个 requestId 反复提交会得到相同的这两个值，幂等可被验证；
     * {@code leader}/{@code extensions} 描述的是拍卖**当前**状态（期间可能已有他人出价）。
     */
    public record BidResult(
            boolean accepted, boolean idempotent, long price, String leader, int extensions, long seq,
            Instant serverTime) {}

    public BidResult placeBid(String auctionId, String userId, long amount, String requestId) {
        try {
            return Db.tx(dataSource, conn -> doPlaceBid(conn, auctionId, userId, amount, requestId));
        } catch (BizException e) {
            recordRejection(auctionId, userId, requestId, e);
            throw e;
        }
    }

    private BidResult doPlaceBid(Connection conn, String auctionId, String userId, long amount, String requestId)
            throws SQLException {
        if (amount <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "出价金额必须为正整数", Map.of("amount", amount));
        }
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "requestId 不能为空");
        }

        Instant now = Db.now(conn);

        // 第一把锁：拍卖行。此后本场拍卖的一切判断都是"当前读"，不受快照影响。
        AuctionRow auction = auctions.lockAuction(conn, auctionId);
        if (auction == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "拍卖不存在", Map.of("auctionId", auctionId));
        }

        RequestRow prior = auctions.lockRequest(conn, auctionId, userId, requestId);
        if (prior != null && prior.done()) {
            if (prior.succeeded()) {
                return new BidResult(true, true, prior.resultPrice(), auction.leaderId(),
                        auction.extensionCount(), prior.resultSeq(), now);
            }
            throw new BizException(replayCode(prior.resultCode()), "重复提交：返回首次的处理结果");
        }
        if (prior == null) {
            auctions.insertRequestPending(conn, auctionId, userId, requestId);
        }

        if (auction.status() != AuctionStatus.RUNNING) {
            throw new BizException(ErrorCode.INVALID_STATE, "拍卖不在进行中",
                    Map.of("status", auction.status().name()));
        }
        if (auction.endsAt() == null || !now.isBefore(auction.endsAt())) {
            throw new BizException(ErrorCode.BID_LATE, "拍卖已截止",
                    Map.of("endsAt", String.valueOf(auction.endsAt()), "serverTime", now.toString()));
        }
        if (!auctions.isParticipant(conn, auctionId, userId)) {
            throw new BizException(ErrorCode.NOT_JOINED, "尚未加入该拍卖间", Map.of("auctionId", auctionId));
        }

        long minimum = auction.currentPrice() + auction.minIncrement();
        if (amount < minimum) {
            throw new BizException(ErrorCode.BID_TOO_LOW, "出价低于当前最高价加最小加价",
                    Map.of("amount", amount, "minimum", minimum, "currentPrice", auction.currentPrice(),
                            "minIncrement", auction.minIncrement()));
        }

        String previousLeader = auction.leaderId();
        List<String> involved = previousLeader == null || previousLeader.equals(userId)
                ? List.of(userId)
                : List.of(userId, previousLeader);

        // 锁 2/3：本场冻结行、钱包行，均按 user_id 升序。
        Map<String, Long> freezes = wallets.lockAuctionFreezes(conn, auctionId, involved);
        Map<String, WalletRow> walletRows = wallets.lockWallets(conn, involved);

        WalletRow me = walletRows.get(userId);
        if (me == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "钱包不存在", Map.of("userId", userId));
        }

        long myFrozen = freezes.getOrDefault(userId, 0L);
        long delta = amount - myFrozen;
        if (delta < 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "本场冻结额已高于本次出价，冻结状态异常",
                    Map.of("userId", userId, "frozen", myFrozen, "amount", amount));
        }
        if (delta > me.availableBalance()) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE, "可用余额不足",
                    Map.of("requiredDelta", delta, "availableBalance", me.availableBalance(),
                            "totalBalance", me.totalBalance(), "frozenAmount", me.frozenAmount()));
        }

        releasePreviousLeader(conn, auction, previousLeader, userId, freezes, walletRows);

        if (delta > 0) {
            wallets.increaseFrozen(conn, userId, delta);
            wallets.setAuctionFrozen(conn, auctionId, userId, amount);
            wallets.appendLedger(conn, userId, LedgerType.FREEZE, delta, auctionId, requestId,
                    me.totalBalance(), me.frozenAmount() + delta);
        }

        long newSeq = auction.seq() + 1;
        Instant newEndsAt = auction.endsAt();
        int newExtensionCount = auction.extensionCount();
        boolean withinWindow = !now.isBefore(auction.endsAt().minusSeconds(EXTENSION_WINDOW_SECONDS));
        if (withinWindow && newExtensionCount < MAX_EXTENSIONS) {
            newEndsAt = auction.endsAt().plusSeconds(EXTENSION_SECONDS);
            newExtensionCount++;
        }

        auctions.applyBid(conn, auctionId, auction.seq(), amount, userId, newEndsAt, newExtensionCount, newSeq);
        auctions.insertBid(conn, auctionId, userId, amount, requestId, newSeq, now);
        auctions.markRequestDone(conn, auctionId, userId, requestId, ErrorCode.OK.name(), amount, newSeq);

        log.info("出价成功 auction={} user={} amount={} seq={} extensions={} endsAt={}",
                auctionId, userId, amount, newSeq, newExtensionCount, newEndsAt);
        return new BidResult(true, false, amount, userId, newExtensionCount, newSeq, now);
    }

    /**
     * 释放上一位领先者的冻结额。
     *
     * <p>这里顺带校验一条不变量：领先者在本场的冻结额必须等于当前最高价。
     * 若不等，说明资金状态已经损坏，宁可让本次出价失败并暴露问题，
     * 也不能带着错误的前提继续计算差额。
     */
    private void releasePreviousLeader(Connection conn, AuctionRow auction, String previousLeader,
            String bidderId, Map<String, Long> freezes, Map<String, WalletRow> walletRows) throws SQLException {
        if (previousLeader == null || previousLeader.equals(bidderId)) {
            return;
        }
        long leaderFrozen = freezes.getOrDefault(previousLeader, 0L);
        if (leaderFrozen != auction.currentPrice()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "领先者冻结额与当前最高价不一致，资金状态异常",
                    Map.of("previousLeader", previousLeader, "frozen", leaderFrozen,
                            "currentPrice", auction.currentPrice()));
        }
        if (leaderFrozen == 0) {
            return;
        }
        WalletRow leaderWallet = walletRows.get(previousLeader);
        if (leaderWallet == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "领先者钱包不存在",
                    Map.of("previousLeader", previousLeader));
        }
        wallets.decreaseFrozen(conn, previousLeader, leaderFrozen);
        wallets.setAuctionFrozen(conn, auction.id(), previousLeader, 0L);
        wallets.appendLedger(conn, previousLeader, LedgerType.RELEASE, leaderFrozen, auction.id(), null,
                leaderWallet.totalBalance(), leaderWallet.frozenAmount() - leaderFrozen);
    }

    private void recordRejection(String auctionId, String userId, String requestId, BizException e) {
        if (!RECORDABLE_REJECTIONS.contains(e.code()) || requestId == null || requestId.isBlank()) {
            return;
        }
        try {
            auctions.upsertRejectedRequest(auctionId, userId, requestId, e.code().name());
        } catch (RuntimeException recordFailure) {
            // 记录失败不能掩盖真正的业务拒绝，否则调用方看到的原因会被替换成无关的错误
            log.warn("写入幂等拒绝记录失败 auction={} user={} requestId={} code={}: {}",
                    auctionId, userId, requestId, e.code(), recordFailure.getMessage());
        }
    }

    private static ErrorCode replayCode(String stored) {
        try {
            return ErrorCode.valueOf(stored);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ErrorCode.CONFLICT;
        }
    }
}
