package com.bidarena.auction.application;

import com.bidarena.auction.persistence.AuctionRepository;
import com.bidarena.auction.persistence.AuctionRepository.AuctionRow;
import com.bidarena.auction.persistence.SettlementRepository;
import com.bidarena.auction.persistence.SettlementRepository.SettlementRow;
import com.bidarena.auction.domain.AuctionEvent;
import com.bidarena.auction.domain.AuctionEventPublisher;
import com.bidarena.auction.domain.AuctionStatus;
import com.bidarena.auction.domain.SettlementReason;
import com.bidarena.shared.BizException;
import com.bidarena.shared.Db;
import com.bidarena.shared.ErrorCode;
import com.bidarena.wallet.persistence.WalletRepository;
import com.bidarena.wallet.persistence.WalletRepository.WalletRow;
import com.bidarena.wallet.domain.LedgerType;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结束一场拍卖：把冻结额变成"要么扣款、要么归还"的终局。
 *
 * <h2>三种结束原因，一个处理路径</h2>
 * <ul>
 *   <li>{@link SettlementReason#TIMEOUT}——到期且有人出价，赢家冻结额转为实际扣款；</li>
 *   <li>{@link SettlementReason#NO_BIDS}——到期但无人出价，无赢家、无扣款；</li>
 *   <li>{@link SettlementReason#CANCELLED}——被取消，冻结全部释放、无扣款。</li>
 * </ul>
 * "要不要动钱"只由 {@link SettlementReason#hasWinner()} 决定一个变量，不存在三条各自判断的路径。
 *
 * <h2>为什么整个结算在一个事务里完成</h2>
 * 原文的状态机是 {@code RUNNING → SETTLING → FINISHED}。这里把 {@code SETTLING} 作为
 * **事务内的中间状态**，提交后不可观测。这不是偷懒，而是刻意的：
 *
 * <p>如果把 {@code SETTLING} 单独提交（先"抢占"、再结算），就会出现一个
 * "已抢占但钱还没动"的持久状态。此时进程崩溃，拍卖会永久卡在 {@code SETTLING}——
 * 钱既没扣也没还，而且扫描"待结算"的查询再也找不到它，必须再写一套超时回收逻辑。
 * 单事务没有这个中间态：崩溃等于什么都没发生，拍卖仍是 {@code RUNNING}，
 * 下一轮扫描自然重试（因为它已过截止时间，出价会被 {@code BID_LATE} 挡住，
 * 用户视角上它已经结束了）。**自愈比"多一个可见状态"更值钱。**
 *
 * <p>{@code SETTLING} 仍然写了一次，是为了让状态机在代码里是完整的、可读的；
 * 代价只是同一事务内多一条 UPDATE。
 *
 * <h2>幂等的三道防线</h2>
 * <ol>
 *   <li>拍卖行锁——重复触发在第一步就被串行化；</li>
 *   <li>锁内读 {@code settlements}——命中即返回已有结果，这是"重复触发返回同一结论"的来源；</li>
 *   <li>{@code settlements} 主键——兜底，保证任何情况下都写不进第二条。</li>
 * </ol>
 *
 * <h2>锁顺序</h2>
 * 与出价事务一致：{@code auctions} 行 → {@code auction_participants} 行（按 {@code user_id} 升序）
 * → {@code wallets} 行（按 {@code user_id} 升序）。全局唯一顺序是避免跨场次死锁的前提。
 */
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final DataSource dataSource;
    private final AuctionRepository auctions;
    private final WalletRepository wallets;
    private final SettlementRepository settlements;
    private final AuctionEventPublisher events;

    public SettlementService(DataSource dataSource, AuctionRepository auctions, WalletRepository wallets,
            SettlementRepository settlements, AuctionEventPublisher events) {
        this.dataSource = dataSource;
        this.auctions = auctions;
        this.wallets = wallets;
        this.settlements = settlements;
        this.events = events;
    }

    /**
     * {@code replay = true} 表示这次调用没有产生任何资金变化，只是返回了已有的成交结果。
     *
     * <p>{@code seq}/{@code status} 是终态事件的输入：结算同样是一次状态变更，要推进版本号，
     * 否则订阅者会停在“最后一次出价”的版本上，永远等不到"已结束"。
     */
    public record SettlementResult(
            String auctionId, String winnerId, long finalPrice, SettlementReason reason, boolean replay,
            Instant serverTime, long seq, AuctionStatus status) {}

    /**
     * 到期结算。拍卖未到期或状态不对时抛 {@link ErrorCode#INVALID_STATE}。
     *
     * <p>重复调用是安全的：第二次会返回 {@code replay = true} 的同一结果。
     */
    public SettlementResult settleIfDue(String auctionId) {
        SettlementResult result = Db.tx(dataSource, conn -> end(conn, auctionId, false));
        publishFinished(result);
        return result;
    }

    /**
     * 取消拍卖并释放全部冻结。
     *
     * <p>取消的终态是 {@link AuctionStatus#CANCELLED}（不是 {@code FINISHED}），与原文状态机一致。
     * 只有 {@code DRAFT} 与 {@code RUNNING} 可以取消；{@code SETTLING} 之后不可取消——
     * 在单事务结算下，这条规则体现为"出价事务与结算事务都要先拿到拍卖行锁"，
     * 谁先拿到谁定结局，后到者看到的是已经变了的 {@code status}。
     */
    public SettlementResult cancel(String auctionId) {
        SettlementResult result = Db.tx(dataSource, conn -> end(conn, auctionId, true));
        publishFinished(result);
        return result;
    }

    /**
     * 扫描并结算一批已到期的拍卖，返回**实际**完成的场数（重放不计入）。
     *
     * <p>不依赖任何客户端调用：每轮都从数据库重新查"已到期且仍为 RUNNING"的拍卖，
     * 因此服务重启后自然接着结算，不需要恢复内存状态。
     *
     * <p>单场失败**不中断整批**：某一场因为数据异常结算不了，不应该把后面所有到期的拍卖
     * 一起拖住。失败的场次留在 {@code RUNNING}，下一轮继续尝试，并记 ERROR 日志。
     */
    public int settleDue(int limit) {
        List<String> due = Db.read(dataSource, conn -> auctions.findDueAuctionIds(conn, Db.now(conn), limit));
        int settled = 0;
        for (String auctionId : due) {
            try {
                SettlementResult result = settleIfDue(auctionId);
                if (!result.replay()) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.error("结算失败，本场将在下一轮重试 auction={}: {}", auctionId, e.getMessage());
            }
        }
        return settled;
    }

    // ------------------------------------------------------------------

    private SettlementResult end(Connection conn, String auctionId, boolean cancelRequested) throws SQLException {
        Instant now = Db.now(conn);

        // 第一把锁：拍卖行。与出价事务共用同一把锁，因此结算与出价天然互斥。
        AuctionRow auction = auctions.lockAuction(conn, auctionId);
        if (auction == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "拍卖不存在", Map.of("auctionId", auctionId));
        }

        SettlementRow existing = settlements.lock(conn, auctionId);
        if (existing != null) {
            // 只有"同一种结束方式已经发生过"才算重放。
            // 若把"取消"打到已成交的拍卖上也返回 replay=true，等于告诉调用方"你的取消已生效"，
            // 而实际发生的是成交——这是在对调用方说谎，比报错危险得多。
            boolean sameKind = cancelRequested == (existing.reason() == SettlementReason.CANCELLED);
            if (sameKind) {
                log.info("拍卖已按 {} 结束，返回已有结果 auction={} winner={}",
                        existing.reason(), auctionId, existing.winnerId());
                // 重放：不改状态、不推版本号、不广播（与出价重放同一规则）。
                return new SettlementResult(auctionId, existing.winnerId(), existing.finalPrice(),
                        existing.reason(), true, now, auction.seq(),
                        existing.reason() == SettlementReason.CANCELLED
                                ? AuctionStatus.CANCELLED
                                : AuctionStatus.FINISHED);
            }
            throw new BizException(ErrorCode.INVALID_STATE,
                    cancelRequested
                            ? "拍卖已按 " + existing.reason() + " 结算，不可取消"
                            : "拍卖已取消，不可再结算",
                    Map.of("auctionId", auctionId, "reason", existing.reason().name()));
        }

        SettlementReason reason = decideReason(auction, cancelRequested, now);
        String winnerId = reason.hasWinner() ? auction.leaderId() : null;
        long finalPrice = winnerId == null ? 0L : auction.currentPrice();

        moveMoney(conn, auction, winnerId, finalPrice);
        settlements.insert(conn, auctionId, winnerId, finalPrice, reason, now);

        AuctionStatus finalStatus;
        if (cancelRequested) {
            auctions.updateStatus(conn, auctionId, auction.status(), AuctionStatus.CANCELLED);
            finalStatus = AuctionStatus.CANCELLED;
        } else {
            // 状态机的 SETTLING 在这里出现，但不跨事务提交，见类注释。
            auctions.updateStatus(conn, auctionId, auction.status(), AuctionStatus.SETTLING);
            auctions.updateStatus(conn, auctionId, AuctionStatus.SETTLING, AuctionStatus.FINISHED);
            finalStatus = AuctionStatus.FINISHED;
        }
        // 结束也是一次状态变更：推进版本号，使订阅者能收到高于“最后一次出价”的 seq。
        long newSeq = auctions.bumpSeq(conn, auctionId);

        log.info("结算完成 auction={} reason={} winner={} finalPrice={} seq={}",
                auctionId, reason, winnerId, finalPrice, newSeq);
        return new SettlementResult(auctionId, winnerId, finalPrice, reason, false, now, newSeq, finalStatus);
    }

    /**
     * 广播终态事件。重放不广播（没有状态变更），发送失败也不影响已提交的结算（契约 §7）。
     */
    private void publishFinished(SettlementResult result) {
        if (result.replay()) {
            return;
        }
        AuctionEvent event = AuctionEvents.auctionFinished(result.auctionId(), result.seq(), result.winnerId(),
                result.finalPrice(), result.status().name(), result.reason().name(), result.serverTime());
        try {
            events.publish(event);
        } catch (RuntimeException e) {
            log.warn("事件广播失败，已提交的结算不受影响 auction={}: {}", result.auctionId(), e.getMessage());
        }
    }

    private SettlementReason decideReason(AuctionRow auction, boolean cancelRequested, Instant now) {
        if (cancelRequested) {
            if (auction.status() != AuctionStatus.DRAFT && auction.status() != AuctionStatus.RUNNING) {
                throw new BizException(ErrorCode.INVALID_STATE, "只有草稿或进行中的拍卖可以取消",
                        Map.of("auctionId", auction.id(), "status", auction.status().name()));
            }
            return SettlementReason.CANCELLED;
        }

        if (auction.status() != AuctionStatus.RUNNING) {
            throw new BizException(ErrorCode.INVALID_STATE, "只有进行中的拍卖可以到期结算",
                    Map.of("auctionId", auction.id(), "status", auction.status().name()));
        }
        if (auction.endsAt() == null || now.isBefore(auction.endsAt())) {
            throw new BizException(ErrorCode.INVALID_STATE, "拍卖尚未到期",
                    Map.of("auctionId", auction.id(), "serverTime", now.toString(),
                            "endsAt", String.valueOf(auction.endsAt())));
        }
        return auction.leaderId() == null ? SettlementReason.NO_BIDS : SettlementReason.TIMEOUT;
    }

    /**
     * 移动资金。
     *
     * <p>遍历本场**全部**有参与记录的用户，而不是只处理赢家与"当前领先者"：
     * 正常运行时只有领先者有冻结，但一旦这个前提被破坏（历史缺陷、人工改库），
     * 只处理领先者会让别人的冻结永久留在账上。遍历一遍的代价是一次索引扫描，
     * 换来的是"结算之后本场冻结必然归零"这个可以断言的性质。
     *
     * <p>非赢家出现非零冻结属于不变量被破坏，此时**归还而不是报错**：
     * 报错会让拍卖卡在未结算状态、资金继续悬着，而归还至少把钱还给了用户，
     * 并留下一条可追溯的 RELEASE 流水。该情形记 WARN。
     */
    private void moveMoney(Connection conn, AuctionRow auction, String winnerId, long finalPrice)
            throws SQLException {
        List<Map.Entry<String, Long>> freezes = wallets.lockAllAuctionFreezes(conn, auction.id());

        // 赢家的冻结额必须**恰好**等于成交价。少一分都不能放行：
        // 若赢家冻结为 0 而仍然成交，等于用户拿走东西却没付钱，且后续所有对账都是平的，
        // 根本不会报警。这种"看起来正确"的缺陷比报错危险得多。
        long winnerFrozen = -1L;
        for (Map.Entry<String, Long> entry : freezes) {
            if (entry.getKey().equals(winnerId)) {
                winnerFrozen = entry.getValue() == null ? 0L : entry.getValue();
            }
        }
        if (winnerId != null && winnerFrozen != finalPrice) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "赢家冻结额与成交价不一致，拒绝结算以免扣错金额或漏扣",
                    Map.of("auctionId", auction.id(), "winnerId", winnerId,
                            "winnerFrozen", winnerFrozen, "finalPrice", finalPrice));
        }

        List<String> holders = freezes.stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();
        Map<String, WalletRow> walletRows = wallets.lockWallets(conn, holders);

        for (Map.Entry<String, Long> entry : freezes) {
            String userId = entry.getKey();
            long frozen = entry.getValue() == null ? 0L : entry.getValue();
            if (frozen == 0) {
                continue;
            }
            WalletRow wallet = walletRows.get(userId);
            if (wallet == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "参与者的钱包不存在",
                        Map.of("auctionId", auction.id(), "userId", userId));
            }

            if (userId.equals(winnerId)) {
                wallets.settleWinner(conn, userId, finalPrice);
                wallets.setAuctionFrozen(conn, auction.id(), userId, 0L);
                wallets.appendLedger(conn, userId, LedgerType.SETTLE, finalPrice, auction.id(), null,
                        wallet.totalBalance() - finalPrice, wallet.frozenAmount() - finalPrice);
            } else {
                log.warn("非赢家存在非零冻结，予以释放 auction={} user={} frozen={}",
                        auction.id(), userId, frozen);
                wallets.decreaseFrozen(conn, userId, frozen);
                wallets.setAuctionFrozen(conn, auction.id(), userId, 0L);
                wallets.appendLedger(conn, userId, LedgerType.RELEASE, frozen, auction.id(), null,
                        wallet.totalBalance(), wallet.frozenAmount() - frozen);
            }
        }
    }
}
