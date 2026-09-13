package com.bidarena.wallet.adapter;

import com.bidarena.api.ApiTrace;
import com.bidarena.api.CurrentUser;
import com.bidarena.api.PageParams;
import com.bidarena.shared.PageQuery;
import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.ApiResponse;
import com.bidarena.wallet.application.WalletQueryService;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Path;
import org.noear.solon.core.handle.ContextUtil;
import org.noear.solon.core.handle.MethodType;

/**
 * 钱包与流水的 HTTP 入口。
 *
 * <p>路径固定为 {@code /wallets/me}，没有"按 user_id 查别人钱包"的路由。
 * 这不是省略，而是刻意的：一旦存在那样的路由，就必须在每个处理器里记得鉴权，
 * 漏一处就是资金隐私泄漏。当前设计下，能查到的永远只是令牌持有者自己的钱包。
 */
@Controller
@Mapping("/api/v1")
public class HttpWalletController {

    @Inject
    WalletQueryService wallets;

    @Mapping(value = "/wallets/me", method = MethodType.GET)
    public ApiResponse currentWallet() {
        Principal me = CurrentUser.require(ContextUtil.current());
        return ApiResponse.ok(wallets.currentWallet(me.userId()), ApiTrace.current());
    }

    @Mapping(value = "/wallets/me/ledger", method = MethodType.GET)
    public ApiResponse walletLedger() {
        Principal me = CurrentUser.require(ContextUtil.current());
        return ApiResponse.ok(wallets.ledger(me.userId(), PageParams.parse(ContextUtil.current())), ApiTrace.current());
    }

    /**
     * 管理员按场次查看全部流水，用于确认"最后成交的是 AI 还是人"。
     *
     * <p>鉴权写在方法首行（{@link CurrentUser#requireAdmin}）：普通用户只能看到
     * {@code /wallets/me/ledger}（自己的流水），别人的主体类型不暴露。
     * 路由仍然放在 wallet 适配器里，避免 auction 的适配器跨上下文依赖 wallet 的应用服务
     * （见 {@code ArchitectureTest}）。
     */
    @Mapping(value = "/admin/auctions/{auctionId}/ledger", method = MethodType.GET)
    public ApiResponse auctionLedger(@Path("auctionId") String auctionId) {
        CurrentUser.requireAdmin(ContextUtil.current());
        return ApiResponse.ok(wallets.ledgerByAuction(auctionId, PageParams.parse(ContextUtil.current())),
                ApiTrace.current());
    }
}
