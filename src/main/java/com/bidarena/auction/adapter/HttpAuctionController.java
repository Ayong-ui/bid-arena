package com.bidarena.auction.adapter;

import com.bidarena.shared.ApiTime;
import com.bidarena.api.ApiTrace;
import com.bidarena.api.CurrentUser;
import com.bidarena.api.IdempotencyKeys;
import com.bidarena.api.PageParams;
import com.bidarena.shared.PageQuery;
import com.bidarena.auction.application.AuctionCommandService;
import com.bidarena.auction.application.AuctionQueryService;
import com.bidarena.auction.application.BidService;
import com.bidarena.auction.domain.AuctionStatus;
import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.ApiResponse;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.Map;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Path;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextUtil;
import org.noear.solon.core.handle.MethodType;

/**
 * 拍卖与出价的公开 HTTP 入口（竞拍者视角）。
 *
 * <h2>身份只来自令牌</h2>
 * 每个涉及"我"的操作都从 {@link CurrentUser} 取 {@code userId}，
 * 路径与请求体里没有任何用户标识。这样"以别人的身份出价"在协议层就不可能发生，
 * 而不是依赖某个 {@code if} 记得去校验。
 *
 * <h2>幂等键的来源</h2>
 * 原文只要求出价请求体带 {@code requestId}。契约里额外声明了 {@code Idempotency-Key} 头，
 * 两者都接受：只给了头时以头为准，都给了就必须一致。规则只有一份，见
 * {@link com.bidarena.api.IdempotencyKeys}（Agent 出价用的是同一个）。
 */
@Controller
@Mapping("/api/v1")
public class HttpAuctionController {

    @Inject
    AuctionQueryService query;

    @Inject
    AuctionCommandService commands;

    @Inject
    BidService bids;

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

    @Mapping(value = "/auctions", method = MethodType.GET)
    public ApiResponse listAuctions() {
        Context ctx = ContextUtil.current();
        return ApiResponse.ok(query.auctions(statusParam(ctx), PageParams.parse(ctx)), ApiTrace.current());
    }

    @Mapping(value = "/auctions/{auctionId}", method = MethodType.GET)
    public ApiResponse getAuction(@Path("auctionId") String auctionId) {
        return ApiResponse.ok(query.snapshot(auctionId), ApiTrace.current());
    }

    @Mapping(value = "/auctions/{auctionId}/join", method = MethodType.POST)
    public ApiResponse joinAuction(@Path("auctionId") String auctionId) {
        Principal me = CurrentUser.require(ContextUtil.current());
        return ApiResponse.ok(commands.join(auctionId, me.userId()), ApiTrace.current());
    }

    @Mapping(value = "/auctions/{auctionId}/bids", method = MethodType.GET)
    public ApiResponse listBids(@Path("auctionId") String auctionId) {
        Context ctx = ContextUtil.current();
        return ApiResponse.ok(query.bids(auctionId, PageParams.parse(ctx)), ApiTrace.current());
    }

    @Mapping(value = "/auctions/{auctionId}/bids", method = MethodType.POST)
    public ApiResponse placeBid(@Path("auctionId") String auctionId, @Body BidRequest body) {
        Context ctx = ContextUtil.current();
        Principal me = CurrentUser.require(ctx);
        if (body == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }

        String requestId = IdempotencyKeys.resolve(ctx, body.requestId());
        BidService.BidResult result = bids.placeBid(auctionId, me.userId(), body.amount(), requestId);

        BidOutcome data = new BidOutcome(
                result.accepted(), result.idempotent(), result.price(), result.leader(),
                result.extensions(), result.seq(), ApiTime.format(result.serverTime()));
        // 重放用专门的 code，让客户端能区分"这次真的出价了"与"这是首次结果的重放"。
        return result.idempotent()
                ? ApiResponse.replay(data, ApiTrace.current())
                : ApiResponse.ok(data, ApiTrace.current());
    }

    @Mapping(value = "/auctions/{auctionId}/result", method = MethodType.GET)
    public ApiResponse auctionResult(@Path("auctionId") String auctionId) {
        return ApiResponse.ok(query.result(auctionId), ApiTrace.current());
    }

    private static AuctionStatus statusParam(Context ctx) {
        String raw = ctx.param("status");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AuctionStatus.parse(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "status 取值非法",
                    Map.of("status", raw, "allowed", "DRAFT,RUNNING,SETTLING,FINISHED,CANCELLED"));
        }
    }
}
