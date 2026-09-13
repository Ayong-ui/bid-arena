package com.bidarena.agentaccess.adapter;

import com.bidarena.agentaccess.application.AgentProxyService;
import com.bidarena.api.ApiTrace;
import com.bidarena.api.CurrentUser;
import com.bidarena.api.PageParams;
import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.ApiResponse;
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
 * 托管 AI 代理的 HTTP 入口（用户 JWT，走 8080）。
 *
 * <h2>为什么不在 Agent 端口（:8090）</h2>
 * Agent 端口是一个"带着 Bearer Token、只能读快照与出价"的最小面，凭证是 Agent Token。
 * 代理的创建与撤销是**用户在网页上做的管理动作**，凭证是用户 JWT。
 * 两者混在一个端口上，会让"哪个端口用什么凭证"这条边界消失——
 * 而这条边界正是 P5 端口隔离要守住的东西。
 *
 * <h2>请求体里没有 userId，也没有策略参数</h2>
 * 归属恒为当前登录用户；策略固定为"不是最高价就加到最低加价，直到预算上限"。
 * 少一个参数就少一类"用户以为设了、其实没生效"的误解。
 */
@Controller
@Mapping("/api/v1")
public class HttpAgentProxyController {

    @Inject
    AgentProxyService agentProxies;

    /**
     * 创建请求：{@code budgetLimit} 用包装类型，缺省时是"没填"（400 可读报错），
     * 而不是被绑定器填成 0（0 会被"必须为正数"拒绝，把"没填"报成"填了 0"）。
     */
    public record CreateAgentProxyRequest(String auctionId, Long budgetLimit) {}

    /** 我创建的 AI 代理；只返回属于当前用户的那些。 */
    @Mapping(value = "/me/agent-proxies", method = MethodType.GET)
    public ApiResponse myProxies() {
        Principal me = CurrentUser.require(ContextUtil.current());
        return ApiResponse.ok(
                agentProxies.listForOwner(me.userId(), PageParams.parse(ContextUtil.current())),
                ApiTrace.current());
    }

    /** 在一场正在或即将进行的拍卖上创建 AI 代理。 */
    @Mapping(value = "/me/agent-proxies", method = MethodType.POST)
    public ApiResponse createProxy(@Body CreateAgentProxyRequest request) {
        Context ctx = ContextUtil.current();
        Principal me = CurrentUser.require(ctx);
        if (request == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        ctx.status(201);
        return ApiResponse.ok(
                agentProxies.create(me.userId(), new AgentProxyService.CreateCommand(
                        request.auctionId(), request.budgetLimit())),
                ApiTrace.current());
    }

    /** 撤销自己的 AI 代理；不属于本人时 404。 */
    @Mapping(value = "/me/agent-proxies/{proxyId}/revoke", method = MethodType.POST)
    public ApiResponse revokeProxy(@Path("proxyId") String proxyId) {
        Principal me = CurrentUser.require(ContextUtil.current());
        return ApiResponse.ok(agentProxies.revoke(proxyId, me.userId()), ApiTrace.current());
    }

    /**
     * 管理员总览：便于排障时回答"现在有哪些 AI 在替人出价"。
     *
     * <p>这里只做列表，**不提供**"管理员替用户建代理/改预算"的能力：
     * 那等于让运营替用户消耗资金，超出本项目的授权范围。
     */
    @Mapping(value = "/admin/agent-proxies", method = MethodType.GET)
    public ApiResponse listAll() {
        CurrentUser.requireAdmin(ContextUtil.current());
        return ApiResponse.ok(agentProxies.listAll(PageParams.parse(ContextUtil.current())), ApiTrace.current());
    }
}
