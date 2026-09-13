package com.bidarena.auction.application;

import com.bidarena.auction.persistence.AuctionRepository;
import com.bidarena.auction.persistence.AuctionRepository.AuctionRow;
import com.bidarena.auction.persistence.AuctionRepository.ParticipantRow;
import com.bidarena.auction.persistence.AuctionRepository.RequestRow;
import com.bidarena.auction.domain.AuctionEvent;
import com.bidarena.auction.domain.AuctionEventPublisher;
import com.bidarena.auction.domain.AuctionStatus;
import com.bidarena.shared.BizException;
import com.bidarena.shared.Db;
import com.bidarena.shared.ErrorCode;
import com.bidarena.wallet.persistence.WalletRepository;
import com.bidarena.wallet.domain.LedgerType;
import com.bidarena.wallet.persistence.WalletRepository.WalletRow;
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
    private final AuctionEventPublisher events;

    public BidService(DataSource dataSource, AuctionRepository auctions, WalletRepository wallets,
            AuctionEventPublisher events) {
        this.dataSource = dataSource;
        this.auctions = auctions;
        this.wallets = wallets;
        this.events = events;
    }

    /**
     * 出价结果。
     *
     * <p>重放（{@code idempotent = true}）时，{@code price} 与 {@code seq} 取**首次**结果的快照，
     * 因此同一个 requestId 反复提交会得到相同的这两个值，幂等可被验证；
     * {@code leader}/{@code extensions}/{@code endsAt} 描述的是拍卖**当前**状态（期间可能已有他人出价）。
     *
     * <p>{@code endsAt} 与 {@code extended} 是给实时事件用的：截止时间与延长次数属于出价结果的一部分
     * （一次出价可能顺带延时），由事务自己返回，比“提交后再去查一遍”多一次竞态窗口——
     * 期间可能已有别人出价，查回来的就不是本次提交的状态了。
     */
    public record BidResult(
            boolean accepted, boolean idempotent, long price, String leader, int extensions, long seq,
            Instant serverTime, Instant endsAt, boolean extended) {}

    public BidResult placeBid(String auctionId, String userId, long amount, String requestId) {
        // 真人路径：不是参与者就拒绝（加入是一个显式动作，见 AuctionCommandService.join）。
        return placeBid(auctionId, userId, amount, requestId, null);
    }

    /**
     * 出价，可选"出价时自动成为参与者"。
     *
     * <p>存在的理由是 Agent：契约里只有"读状态"与"出价"两个动作，没有加入接口。
     * 于是有两种实现：给 Agent 加一个契约外的加入接口（多一次往返、多一个可失败的中间态），
     * 或在出价事务里顺手补上参与记录。选后者，并且<b>必须在同一个事务里</b>：
     * 若先加入再出价，出价因余额不足被拒时会留下一个"加入了但没出价"的 Agent；
     * 更精糕的是，两次写操作之间存在窗口，可能被结算抢先。
     *
     * @param autoJoinAs 非 null 时，出价者还不是参与者就在本事务内以该类型补一条参与记录；
     *                   {@code null} 表示"不是参与者就拒绝"（真人路径，行为与 P4 一致）。
     */
    public BidResult placeBid(String auctionId, String userId, long amount, String requestId, String autoJoinAs) {
        TxOutcome outcome;
        try {
            outcome = Db.tx(dataSource, conn -> doPlaceBid(conn, auctionId, userId, amount, requestId, autoJoinAs));
        } catch (BizException e) {
            recordRejection(auctionId, userId, requestId, e);
            publishRejected(auctionId, userId, e);
            throw e;
        }
        if (outcome.autoJoined() != null) {
            publishQuietly(AuctionEvents.participantJoined(auctionId, outcome.bid().seq(), userId,
                    outcome.participantCount(), outcome.autoJoined().joinedAt()));
        }
        publishAccepted(auctionId, outcome.bid());
        return outcome.bid();
    }

    /**
     * 事务的完整回报：出价结果，以及（若发生了）自动加入的参与记录。
     *
     * <p>不把这些字段塞进 {@link BidResult}：那是对外契约的一部分，
     * 而"本次顺带加入了"是内部事实，不应该出现在 HTTP 响应与事件载荷里。
     */
    private record TxOutcome(BidResult bid, ParticipantRow autoJoined, int participantCount) {}

    /**
     * 发布“出价被接受”（以及可能的延时）。
     *
     * <p>顺序是先主后从：{@code BID_ACCEPTED} 已经带上本次的 {@code endsAt} 与 {@code extensionCount}，
     * 因此只看了第一帧的客户端也是对的；{@code AUCTION_EXTENDED} 是给“延时”这种强调场景的补充视图。
     * 两者 {@code seq} 相同（一次提交 = 一个版本号，契约 §3）。
     *
     * <p>重放**不发布**：重放意味着本次调用没有产生任何状态变更，
     * 再广播一次会把“同一版本”推给所有人，客户端只能靠去重吃掉。
     */
    private void publishAccepted(String auctionId, BidResult result) {
        if (result.idempotent()) {
            return;
        }
        publishQuietly(AuctionEvents.bidAccepted(auctionId, result.seq(), result.price(), result.leader(),
                result.endsAt(), result.extensions(), result.serverTime()));
        if (result.extended()) {
            publishQuietly(AuctionEvents.auctionExtended(auctionId, result.seq(), result.endsAt(),
                    result.extensions(), result.serverTime()));
        }
    }

    /**
     * 发布“出价被拒”。只发给请求者本人（事件类型自己声明了范围）。
     *
     * <p>拍卖不存在时跳过：既没有可对齐的 {@code seq}，也没有“这一场”的概念。
     */
    private void publishRejected(String auctionId, String userId, BizException cause) {
        AuctionRow auction = auctions.load(auctionId);
        if (auction == null) {
            return;
        }
        publishToUserQuietly(userId, auctionId,
                AuctionEvents.bidRejected(auctionId, auction.seq(), cause, Instant.now()));
    }

    /**
     * 发布失败的兜底：事件在事务**提交后**发布，推送失败不能把一次已成功的出价变成失败（契约 §7）。
     *
     * <p>发布端口自己也会吞异常，这里再兜一层是刻意的双重保险：这层保证“事务已提交”这件事
     * 不会被下游任何实现细节推翻，那层保证单个连接的失败不会影响同一场其它连接的广播。
     */
    private void publishQuietly(AuctionEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException e) {
            log.warn("事件广播失败，已提交的事务不受影响 auction={} type={}: {}",
                    event.auctionId(), event.type(), e.getMessage());
        }
    }

    private void publishToUserQuietly(String userId, String auctionId, AuctionEvent event) {
        if (userId == null) {
            return;
        }
        try {
            events.publishToUser(userId, event);
        } catch (RuntimeException e) {
            log.warn("事件单播失败，已提交的事务不受影响 auction={} type={}: {}",
                    auctionId, event.type(), e.getMessage());
        }
    }

    private TxOutcome doPlaceBid(Connection conn, String auctionId, String userId, long amount, String requestId,
            String autoJoinAs) throws SQLException {
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
                return new TxOutcome(new BidResult(true, true, prior.resultPrice(), auction.leaderId(),
                        auction.extensionCount(), prior.resultSeq(), now, auction.endsAt(), false), null, 0);
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
        if (autoJoinAs == null && !auctions.isParticipant(conn, auctionId, userId)) {
            throw new BizException(ErrorCode.NOT_JOINED, "尚未加入该拍卖间", Map.of("auctionId", auctionId));
        }

        // 自动加入与出价在同一个事务、同一把拍卖行锁内：要么两者都成立，要么都不成立。
        ParticipantRow autoJoined = null;
        if (autoJoinAs != null && !auctions.isParticipant(conn, auctionId, userId)) {
            auctions.join(conn, auctionId, userId, autoJoinAs);
            autoJoined = auctions.findParticipant(conn, auctionId, userId);
            if (autoJoined == null) {
                // 刚写入却读不到：与 AuctionCommandService.join 同样的判断，不能带着不确定继续。
                throw new BizException(ErrorCode.INTERNAL_ERROR, "自动加入后无法读取参与记录",
                        Map.of("auctionId", auctionId, "userId", userId));
            }
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

        log.info("出价成功 auction={} user={} amount={} seq={} extensions={} endsAt={} autoJoined={}",
                auctionId, userId, amount, newSeq, newExtensionCount, newEndsAt, autoJoined != null);
        return new TxOutcome(new BidResult(true, false, amount, userId, newExtensionCount, newSeq, now, newEndsAt,
                newExtensionCount != auction.extensionCount()), autoJoined,
                autoJoined == null ? 0 : auctions.countParticipants(conn, auctionId));
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
