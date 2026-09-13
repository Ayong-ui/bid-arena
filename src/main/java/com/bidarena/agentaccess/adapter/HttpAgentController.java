package com.bidarena.agentaccess.adapter;

import com.bidarena.agentaccess.application.AgentAuctionService;
import com.bidarena.agentaccess.domain.AgentToken;
import com.bidarena.api.ApiTrace;
import com.bidarena.api.IdempotencyKeys;
import com.bidarena.auction.application.AuctionViews;
import com.bidarena.auction.application.BidService;
import com.bidarena.shared.ApiResponse;
import com.bidarena.shared.ApiTime;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Path;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextUtil;
import org.noear.solon.core.handle.MethodType;

/**
 * 竞拍 Agent 的 HTTP 入口（读状态、出价、读结果）。
 *
 * <p>类级 {@code @Mapping("/api/v1/agent")} 的字符串必须与
 * {@link com.bidarena.api.ApiPaths#AGENT_PREFIX} 保持一致——注解参数只能是编译期常量，
 * 两者无法互相引用，因此改前缀时两处都要动（P5 起由集成测试与架构测试共同兜住：
 * 前缀若不一致，Agent 会被 JWT 过滤器拦下，`AgentApiIntegrationTest` 立刻变红）。
 *
 * <h2>身份只来自 Token</h2>
 * 与真人接口同一原则：路径与请求体里没有 userId，出价用的一定是 Token 所属用户。
 * 因此 Agent 无法替别人出价——这不是靠校验，而是靠协议里根本没有这个字段。
 *
 * <h2>响应形状与真人出价一致</h2>
 * 复用同一套 {@code BidOutcome} 字段（金额用整数积分、时间是 ISO 字符串），
 * 使 Coding Agent 与前端可以用同一份解析逻辑；重放（{@code IDEMPOTENCY_REPLAY}）
 * 也保持 HTTP 200 + 首次结果，不因入口不同而改变语义。
 */
@Controller
@Mapping("/api/v1/agent")
public class HttpAgentController {

    @Inject
    AgentAuctionService agentAuctions;

    /** 出价请求体，与契约 {@code BidRequest} 一致。 */
    public record BidRequest(String requestId, long amount) {}

    /** 出价结果，与契约 {@code BidResult} 一致。 */
    public record BidOutcome(
            boolean accepted,
            boolean idempotent,
            long price,
            String leader,
            int extensions,
            long seq,
            String serverTime) {}

    @Mapping(value = "/auctions/{auctionId}", method = MethodType.GET)
    public ApiResponse auction(@Path("auctionId") String auctionId) {
        AgentToken token = AgentCaller.require(ContextUtil.current());
        return ApiResponse.ok(agentAuctions.snapshot(token, auctionId), ApiTrace.current());
    }

    @Mapping(value = "/auctions/{auctionId}/bids", method = MethodType.POST)
    public ApiResponse placeBid(@Path("auctionId") String auctionId, @Body BidRequest body) {
        Context ctx = ContextUtil.current();
        AgentToken token = AgentCaller.require(ctx);
        if (body == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        String requestId = IdempotencyKeys.resolve(ctx, body.requestId());

        BidService.BidResult result = agentAuctions.bid(token, auctionId, body.amount(), requestId);
        BidOutcome data = new BidOutcome(result.accepted(), result.idempotent(), result.price(),
                result.leader(), result.extensions(), result.seq(), ApiTime.format(result.serverTime()));

        return result.idempotent()
                ? ApiResponse.replay(data, ApiTrace.current())
                : ApiResponse.ok(data, ApiTrace.current());
    }

    @Mapping(value = "/auctions/{auctionId}/result", method = MethodType.GET)
    public ApiResponse auctionResult(@Path("auctionId") String auctionId) {
        AgentToken token = AgentCaller.require(ContextUtil.current());
        AuctionViews.Result result = agentAuctions.result(token, auctionId);
        // Agent 不是管理员：只有它所属用户本人赢了这一场，才允许看到 winnerType。
        if (!token.agentUserId().equals(result.winner())) {
            result = new AuctionViews.Result(result.auctionId(), result.status(), result.winner(), null,
                    result.finalPrice(), result.reason(), result.settledAt());
        }
        return ApiResponse.ok(result, ApiTrace.current());
    }
}
