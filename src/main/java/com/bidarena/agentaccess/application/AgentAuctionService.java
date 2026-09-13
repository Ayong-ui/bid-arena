package com.bidarena.agentaccess.application;

import com.bidarena.agentaccess.domain.AgentScope;
import com.bidarena.agentaccess.domain.AgentToken;
import com.bidarena.auction.application.AuctionCommandService;
import com.bidarena.auction.application.AuctionQueryService;
import com.bidarena.auction.application.AuctionViews;
import com.bidarena.auction.application.BidService;

/**
 * 竞拍 Agent 的用例编排：<b>先授权，再复用拍卖业务的同一批服务</b>。
 *
 * <h2>为什么不另开一条出价路径</h2>
 * 出价是"谁领先、冻结多少"的唯一事实来源，它的事务、锁顺序与幂等语义写在
 * {@link BidService} 里（见 {@code DESIGN.md}）。如果 Agent 走一条新路径，那套规则就会
 * 被复制第二份，而两份规则迟早会分叉——最坏的情况是 Agent 的出价绕过了资金不变量。
 * 因此这里只做三件事：拿 Token 授权、调业务服务、把结果原样交给适配器；
 * Agent 出价与真人出价落在同一个 {@code bids} 表、同一个事务、同一套 INV 约束下。
 *
 * <h2>为什么 Agent 出价会自动加入拍卖间</h2>
 * 契约里没有 `/agent/auctions/{id}/join`——Agent 只有"读状态"与"出价"两个动作。
 * 而 {@link BidService} 要求出价者是参与者。两条设计二选一：要么给 Agent 加一个
 * 契约里没有的加入接口，要么让出价自动补上参与记录。后者更贴近"Agent 自主决策并出价"
 * 的语义：它读到值得出价的状态时，加入是出价的隐含前提，而不是一个需要 Agent 额外
 * 理解的状态机。参与记录会以 {@code AGENT} 类型落库，运营仍能区分两类参与者。
 */
public class AgentAuctionService {

    private final AgentTokenService tokenAccess;
    private final AuctionQueryService auctions;
    private final BidService bids;

    public AgentAuctionService(AgentTokenService tokenAccess, AuctionQueryService auctions, BidService bids) {
        this.tokenAccess = tokenAccess;
        this.auctions = auctions;
        this.bids = bids;
    }

    /** 读取权威快照。需要 {@code auction:read}，且拍卖在 Token 范围内。 */
    public AuctionViews.Snapshot snapshot(AgentToken token, String auctionId) {
        tokenAccess.authorize(token, auctionId, AgentScope.READ);
        return auctions.snapshot(auctionId);
    }

    /** 读取成交结果。需要 {@code auction:read}。 */
    public AuctionViews.Result result(AgentToken token, String auctionId) {
        tokenAccess.authorize(token, auctionId, AgentScope.READ);
        return auctions.result(auctionId);
    }

    /**
     * 出价。需要 {@code auction:bid}。<b>以 Token 所属的用户身份出价</b>——
     * 请求体与路径里都没有 userId，因此"用别人的钱出价"在协议层就不存在。
     *
     * <p>幂等键由调用方（控制器）解析后传入，与真人出价共用同一个字段与同一张
     * {@code bid_requests} 表：同一个 {@code requestId} 无论来自 Agent 还是真人，
     * 都只会产生一次出价与一次资金变动。
     */
    public BidService.BidResult bid(AgentToken token, String auctionId, long amount, String requestId) {
        tokenAccess.authorize(token, auctionId, AgentScope.BID);
        return bids.placeBid(auctionId, token.agentUserId(), amount, requestId,
                AuctionCommandService.PARTICIPANT_AGENT);
    }
}
