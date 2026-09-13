package com.bidarena.agentaccess.application;

import com.bidarena.agentaccess.domain.AgentProxyStatus;
import com.bidarena.agentaccess.persistence.AgentProxyRepository;
import com.bidarena.agentaccess.persistence.AgentProxyRepository.ProxyRow;
import com.bidarena.auction.application.AuctionCommandService;
import com.bidarena.auction.application.AuctionQueryService;
import com.bidarena.auction.application.AuctionViews;
import com.bidarena.auction.application.BidService;
import com.bidarena.auction.domain.AuctionStatus;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.shared.PageQuery;
import com.bidarena.wallet.application.WalletQueryService;
import com.bidarena.wallet.application.WalletViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 托管 AI 代理：用户建一个代理，服务端替它跟价。
 *
 * <h2>它和 Agent Token 的区别</h2>
 * Token（P5/D-34）是"把钥匙给用户自己的程序"，服务端被动接受请求；
 * 代理是"用户只表达意图（这场、预算多少），服务端主动执行"。两者并存：
 * 会用 API 的人走 Token，不会的人走代理。
 *
 * <h2>为什么出价必须复用 {@link BidService}</h2>
 * 代理是**最容易被写成第二套资金逻辑**的东西：它需要"出价成功后记账"，看上去比真人流程简单，
 * 于是很容易长出一个"代理专用"的扣款/冻结路径。那样做的代价是代理出的价与真人出的价
 * 在冻结、释放、结算、博弈时间、幂等重试上全部各有一套语义，对账时无法收敛。
 * 因此这里只做两件事：算出下一口价、把出价请求交给 {@link BidService}；
 * {@code actor_type=AGENT} 也是在这里显式声明的（{@link AuctionCommandService#PARTICIPANT_AGENT}）。
 *
 * <h2>调度是"无记忆"的</h2>
 * 服务里不保存"我上次出过多少"。每一轮都从数据库重新读"我现在是不是最高价、下一口多少钱"，
 * 因此进程重启、多实例并行、数据库被别人直接改价，代理都能自愈。
 * 重复出价由 {@code requestId = agp-{proxyId}-{amount}} 收敛：
 * 下一口价由"当前价 + 最小加价"决定，金额随领先者变化而单调变化，
 * 同一个金额永远不会被这个代理有意提交两次。
 */
public class AgentProxyService {

    private static final Logger log = LoggerFactory.getLogger(AgentProxyService.class);

    /**
     * 预算上限的绝对天花板。
     *
     * <p>没有它，一次手滑的 {@code 999999999} 就足以让"AI 帮我把余额全押上"变成默认行为，
     * 而这笔钱是真实冻结的。上限本身不对应任何业务规则，只是防呆。
     */
    public static final long MAX_BUDGET = 1_000_000L;

    private final AgentProxyRepository proxies;
    private final AuctionQueryService auctions;
    private final WalletQueryService wallets;
    private final BidService bids;

    public AgentProxyService(AgentProxyRepository proxies, AuctionQueryService auctions,
            WalletQueryService wallets, BidService bids) {
        this.proxies = proxies;
        this.auctions = auctions;
        this.wallets = wallets;
        this.bids = bids;
    }

    /** 创建请求：只有"哪一场、最多出到多少"。策略是固定的，不开放参数。 */
    public record CreateCommand(String auctionId, Long budgetLimit) {}

    // ------------------------------------------------------------------ 写用例

    /**
     * 为当前用户在一场拍卖上创建代理。
     *
     * <p>四道校验，顺序是刻意的：先看拍卖在不在、结没结束（用户的问题大多在这里），
     * 再看预算合不合法，最后才看钱够不够。反过来会让"这场已经结束了"被报成"余额不足"。
     */
    public AgentProxyViews.Summary create(String ownerUserId, CreateCommand command) {
        if (command == null || command.auctionId() == null || command.auctionId().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "auctionId 不能为空");
        }
        Long rawBudget = command.budgetLimit();
        if (rawBudget == null || rawBudget <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "预算上限必须是正整数", Map.of("budgetLimit", String.valueOf(rawBudget)));
        }
        if (rawBudget > MAX_BUDGET) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "预算上限不能超过 " + MAX_BUDGET, Map.of("maxBudgetLimit", MAX_BUDGET));
        }
        long budget = rawBudget;

        // 拍卖必须存在且还没结束。存在性由 snapshot 负责（找不到就是 404）。
        AuctionViews.Snapshot snapshot = auctions.snapshot(command.auctionId());
        if (AuctionStatus.parse(snapshot.status()).isTerminal()) {
            throw new BizException(ErrorCode.INVALID_STATE,
                    "这场拍卖已经结束，无法再创建 AI 代理",
                    Map.of("auctionId", command.auctionId(), "status", snapshot.status()));
        }

        WalletViews.Wallet wallet = wallets.currentWallet(ownerUserId);
        if (wallet.availableBalance() < budget) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE,
                    "可用额不足以覆盖这个预算上限（AI 出价时会真实冻结）",
                    Map.of("budgetLimit", budget, "availableBalance", wallet.availableBalance()));
        }

        ProxyRow existing = proxies.findByOwnerAndAuction(ownerUserId, command.auctionId());
        if (existing != null) {
            if (existing.status() != AgentProxyStatus.REVOKED) {
                throw new BizException(ErrorCode.CONFLICT,
                        "这场拍卖你已经有一个 AI 代理了", Map.of("proxyId", existing.id()));
            }
            // 撤销后重建 = 重置同一行。唯一键 (owner, auction) 不允许新增，
            // 而"同一场同一人只能有一个代理位"本身就是产品规则。
            proxies.reset(existing.id(), budget);
            return AgentProxyViews.Summary.of(proxies.load(existing.id()));
        }

        String proxyId = "agp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        proxies.insert(proxyId, ownerUserId, command.auctionId(), budget);
        return AgentProxyViews.Summary.of(proxies.load(proxyId));
    }

    /** 撤销自己的代理。不属于本人时 404：不泄露"这个 ID 存在但不归你"。 */
    public AgentProxyViews.Summary revoke(String proxyId, String ownerUserId) {
        if (proxyId == null || proxyId.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "proxyId 不能为空");
        }
        ProxyRow row = proxies.load(proxyId);
        if (row == null || !row.ownerUserId().equals(ownerUserId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "AI 代理不存在", Map.of("proxyId", proxyId));
        }
        if (row.status() == AgentProxyStatus.REVOKED) {
            return AgentProxyViews.Summary.of(row);
        }
        proxies.revoke(proxyId, ownerUserId);
        return AgentProxyViews.Summary.of(proxies.load(proxyId));
    }

    // ------------------------------------------------------------------ 读用例

    public PageQuery.Page<AgentProxyViews.Summary> listForOwner(String ownerUserId, PageQuery page) {
        return page(proxies.pageByOwner(ownerUserId, page.limit(), page.offset()),
                proxies.countByOwner(ownerUserId), page);
    }

    public PageQuery.Page<AgentProxyViews.Summary> listAll(PageQuery page) {
        return page(proxies.pageAll(page.limit(), page.offset()), proxies.countAll(), page);
    }

    private static PageQuery.Page<AgentProxyViews.Summary> page(
            List<ProxyRow> rows, long total, PageQuery page) {
        List<AgentProxyViews.Summary> items = new ArrayList<>(rows.size());
        for (ProxyRow row : rows) {
            items.add(AgentProxyViews.Summary.of(row));
        }
        return new PageQuery.Page<>(items, page.page(), page.size(), total);
    }

    // ------------------------------------------------------------------ 调度

    /**
     * 推进一轮。返回本轮"有实际动作"的代理数（出价、触顶、收尾各算一次）。
     *
     * <p>没有动作是常态：绝大多数轮次里代理要么正在领先，要么拍卖还没开始，
     * 要么已经在等结算。返回值只用于日志与测试断言，不参与任何判定。
     */
    public int tick(int batchSize) {
        int actions = 0;
        for (ProxyRow row : proxies.findLive(batchSize)) {
            if (advance(row)) {
                actions++;
            }
        }
        // 已触顶的代理不在 findLive 里，但它们同样需要"拍卖结束后收尾"。
        for (ProxyRow row : proxies.listFrozenForFinishedAuctions(batchSize)) {
            if (finalise(row)) {
                actions++;
            }
        }
        return actions;
    }

    /** 单个代理的一步。返回值表示"这一轮真的改了状态吗"。 */
    boolean advance(ProxyRow row) {
        AuctionStatus auctionStatus = AuctionStatus.parse(row.auctionStatus());
        if (auctionStatus.isTerminal()) {
            return finalise(row);
        }
        // DRAFT（含带预告还没开拍）：什么都不做，等 AuctionStartScheduler 把它变成 RUNNING。
        // 这正是"时间到自动进场"的实现方式——不需要定时器，调度器每轮看它一眼就够了。
        if (auctionStatus != AuctionStatus.RUNNING) {
            return false;
        }
        if (row.leading()) {
            // 已经最高价。仍然要把 PENDING 推进为 BIDDING：代理确实已经进场了
            // （可能是用户自己出的价，也可能是上一次跟价还没被超越）。
            proxies.markBidding(row.id());
            return false;
        }
        if (!row.canAffordNext()) {
            return proxies.markBudgetReached(row.id());
        }

        long next = row.nextBidAmount();
        // 幂等键把"哪一个代理、哪一口价"编码进去：金额单调变化，因此同一金额不会被有意提交两次；
        // 而万一重复提交（调度器重入、人工调用），BidService 的幂等表也会返回首次结果。
        String requestId = "agp-" + row.id() + "-" + next;
        try {
            bids.placeBid(row.auctionId(), row.ownerUserId(), next, requestId,
                    AuctionCommandService.PARTICIPANT_AGENT);
            proxies.markBidPlaced(row.id(), row.bidCount() + 1, next);
            log.info("AI 代理跟价 代理={} 场次={} 用户={} 金额={}",
                    row.id(), row.auctionId(), row.ownerUserId(), next);
            return true;
        } catch (BizException e) {
            return handleRejection(row, e);
        }
    }

    /**
     * 被拒绝时怎么办。
     *
     * <p>分三类，每一类的理由都不同：
     * <ul>
     *   <li><b>等一等就会好</b>（{@code BID_TOO_LOW}：别人刚加了价，我这口已经过时）——
     *       不记任何状态，下一轮重新读当前价再算。</li>
     *   <li><b>这一场已经没有机会了</b>（博弈时间 {@code HUMAN_ONLY_PERIOD}、{@code BID_LATE}）——
     *       代理不重试也不报错，安静等到结算。这是 D-32 的直接结果：<b>AI 在最后 20 秒无法加价</b>，
     *       用户界面上必须在创建时就说清楚，否则会被理解成"我的 AI 坏了"。</li>
     *   <li><b>钱到顶了</b>（{@code INSUFFICIENT_BALANCE}）——与预算触顶同样处理：
     *       停手并给一次提醒。可用额不足和预算不足对用户是同一句话："不能再跟了"。</li>
     * </ul>
     */
    private boolean handleRejection(ProxyRow row, BizException e) {
        ErrorCode code = e.code();
        if (code == ErrorCode.INSUFFICIENT_BALANCE) {
            return proxies.markBudgetReached(row.id());
        }
        if (code == ErrorCode.HUMAN_ONLY_PERIOD || code == ErrorCode.BID_LATE) {
            log.info("AI 代理进入尾段被拒（预期行为）代理={} 场次={} code={}", row.id(), row.auctionId(), code);
            return false;
        }
        if (code == ErrorCode.BID_TOO_LOW || code == ErrorCode.NOT_JOINED
                || code == ErrorCode.INVALID_STATE || code == ErrorCode.CONFLICT) {
            return false;
        }
        log.warn("AI 代理出价被意外拒绝 代理={} 场次={} code={} message={}",
                row.id(), row.auctionId(), code, e.getMessage());
        return false;
    }

    /** 拍卖已结束：把结算结果快照进代理行。 */
    private boolean finalise(ProxyRow row) {
        try {
            AuctionViews.Result result = auctions.result(row.auctionId());
            boolean won = row.ownerUserId().equals(result.winner());
            proxies.markFinished(row.id(), won, result.finalPrice());
            return true;
        } catch (BizException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                // 状态已终结但结算行还没落（结算与扫描的极小窗口），下一轮再来。
                return false;
            }
            throw e;
        }
    }
}
