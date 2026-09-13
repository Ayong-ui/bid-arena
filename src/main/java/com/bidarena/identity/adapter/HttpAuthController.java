package com.bidarena.identity.adapter;

import com.bidarena.api.ApiTime;
import com.bidarena.api.ApiTrace;
import com.bidarena.api.CurrentUser;
import com.bidarena.identity.application.IdentityService;
import com.bidarena.identity.application.WsTicketService;
import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.ApiResponse;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.boot.prop.impl.WebSocketServerProps;
import org.noear.solon.core.handle.ContextUtil;
import org.noear.solon.core.handle.MethodType;

/**
 * 身份相关的 HTTP 入口：登录、当前用户。
 *
 * <p>控制器只做三件事：解析输入、调用应用服务、包成统一封套。
 * 校验、事务、异常翻译都不在这里——那些属于应用层与过滤器。
 *
 * <p>依赖用 {@code @Inject} 字段注入（Solon 的构造器注入需要为每个参数标注类型，
 * 而本项目的装配图已由 {@code bootstrap.Services} 显式给出，这里只是把它接上）。
 */
@Controller
@Mapping("/api/v1")
public class HttpAuthController {

    @Inject
    IdentityService identity;

    @Inject
    WsTicketService wsTickets;

    /** 登录请求体，与 {@code openapi.yaml} 的 {@code LoginRequest} 一致。 */
    public record LoginRequest(String email, String password) {}

    /** 登录响应数据，与 {@code AuthData} 一致。{@code expiresAt} 用字符串见 {@link ApiTime}。 */
    public record AuthData(String accessToken, String expiresAt, UserView user) {}

    /**
     * WebSocket 票响应，与 {@code openapi.yaml} 的 {@code WsTicket} 一致。
     *
     * <p>顺带返回 {@code wsPath} 与 {@code wsPort}：WS 监听的**不是** HTTP 端口（默认在 HTTP 端口上加
     * 10000），前端自己拼很容易拼错，而错法是“连接被拒”而不是一个可读的错误。
     * 端口号取框架自己的配置结果（{@link WebSocketServerProps}），
     * 而不是在这里再写一次“加 10000”的规则：那种重复一改就错，而且错得很静默。
     */
    public record WsTicketData(String ticket, String expiresAt, String wsPath, int wsPort) {}

    @Mapping(value = "/auth/login", method = MethodType.POST)
    public ApiResponse login(@Body LoginRequest body) {
        if (body == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        IdentityService.LoginResult result = identity.login(body.email(), body.password());
        return ApiResponse.ok(
                new AuthData(result.accessToken(), ApiTime.format(result.expiresAt()), UserView.of(result.user())),
                ApiTrace.current());
    }

    @Mapping(value = "/users/me", method = MethodType.GET)
    public ApiResponse me() {
        Principal me = CurrentUser.require(ContextUtil.current());
        return ApiResponse.ok(UserView.of(identity.requireUser(me.userId())), ApiTrace.current());
    }

    /**
     * 签发一张 WebSocket 握手票（一次性，默认 60 秒）。
     *
     * <p>为什么需要它：浏览器无法在 WebSocket 请求上自定义头，令牌只能进 URL，
     * 而 URL 会落进访问日志与浏览器历史。换票把“长寿命凭证出现在日志里”压缩成
     * “一个已作废的 60 秒字符串”。完整取舍见 {@code docs/REALTIME_AND_COMMAND_FLOW.md} §5。
     *
     * <p>要求已登录（与其它接口一致，由 AuthFilter 保证），因为票必须绑定一个身份。
     */
    @Mapping(value = "/auth/ws-tickets", method = MethodType.POST)
    public ApiResponse wsTicket() {
        Principal me = CurrentUser.require(ContextUtil.current());
        WsTicketService.Ticket ticket = wsTickets.issue(me);
        return ApiResponse.ok(new WsTicketData(ticket.value(), ApiTime.format(ticket.expiresAt()),
                "/ws/auctions/{auctionId}", WebSocketServerProps.getInstance().getPort()), ApiTrace.current());
    }
}
