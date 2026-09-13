package com.bidarena.agentaccess.adapter;

import com.bidarena.agentaccess.application.AgentTokenService;
import com.bidarena.agentaccess.domain.AgentToken;
import com.bidarena.api.ApiTrace;
import com.bidarena.api.CurrentUser;
import com.bidarena.api.PageParams;
import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.ApiResponse;
import com.bidarena.shared.ApiTime;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
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
 * 管理员签发 / 吊销竞拍 Agent Token（走用户 JWT + ADMIN 角色，与 Agent API 无关）。
 *
 * <h2>为什么签发接口在 8080 而不是 Agent 端口</h2>
 * 这是管理动作，凭证是管理员 JWT。放在 Agent 端口上会模糊"哪个端口用什么凭证"这条边界，
 * 也会让 Agent 端口不再是一个"只读 + 出价"的最小面。
 *
 * <h2>明文只出现在创建响应里</h2>
 * {@code POST /admin/agent-tokens} 返回 {@code {token, tokenId, expiresAt}}；
 * 之后的任何查询接口都不会再返回 {@code token}（吊销响应里该字段为 null）。
 * 这条约束由 {@link AgentTokenService} 的结构保证：服务端手里根本没有明文。
 */
@Controller
@Mapping("/api/v1")
public class HttpAgentTokenController {

    @Inject
    AgentTokenService tokens;

    /**
     * 签发请求，与契约 {@code CreateAgentTokenRequest} 一致。
     *
     * <p>{@code rateLimitPerMinute} 用包装类型：缺省时应当是"用默认值"，
     * 而不是被 Jackson 填成 0（0 会被校验拒绝，把那句"未配置"变成一次 400）。
     */
    public record CreateAgentTokenRequest(
            String name,
            String agentUserId,
            List<String> auctionIds,
            List<String> scopes,
            String expiresAt,
            Integer rateLimitPerMinute) {}

    /**
     * 自助签发请求：<b>没有 agentUserId</b>。
     *
     * <p>不是省略，而是刻意的：归属恒为当前登录用户，因此"替别人签发"在契约层面就无法表达。
     */
    public record CreateMyAgentTokenRequest(
            String name,
            List<String> auctionIds,
            List<String> scopes,
            String expiresAt,
            Integer rateLimitPerMinute) {}

    /** 签发 / 吊销的响应。{@code token} 只在创建时为非 null。 */
    public record AgentTokenView(String token, String tokenId, String expiresAt) {}

    @Mapping(value = "/admin/agent-tokens", method = MethodType.POST)
    public ApiResponse create(@Body CreateAgentTokenRequest request) {
        Context ctx = ContextUtil.current();
        CurrentUser.requireAdmin(ctx);
        if (request == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }

        AgentTokenService.Issued issued = tokens.issue(new AgentTokenService.IssueCommand(
                request.name(), request.agentUserId(), request.auctionIds(), request.scopes(),
                parseExpiresAt(request.expiresAt()), request.rateLimitPerMinute()));

        ctx.status(201);
        return ApiResponse.ok(
                new AgentTokenView(issued.token(), issued.tokenId(), ApiTime.format(issued.expiresAt())),
                ApiTrace.current());
    }

    @Mapping(value = "/admin/agent-tokens/{tokenId}/revoke", method = MethodType.POST)
    public ApiResponse revoke(@Path("tokenId") String tokenId) {
        Context ctx = ContextUtil.current();
        CurrentUser.requireAdmin(ctx);
        AgentToken token = tokens.revoke(tokenId);
        return ApiResponse.ok(
                new AgentTokenView(null, token.tokenId(), ApiTime.format(token.expiresAt())),
                ApiTrace.current());
    }

    /**
     * 管理员总览全部已签发的 Token。
     *
     * <p>存在的理由是审计与排障：没有列表，运营就无法回答"现在还有哪些 Agent 能替我出价"。
     * 响应<b>不含明文</b>——明文在签发后就不可取回（见 {@link AgentTokenService} 的类注释）。
     */
    @Mapping(value = "/admin/agent-tokens", method = MethodType.GET)
    public ApiResponse listAll() {
        CurrentUser.requireAdmin(ContextUtil.current());
        return ApiResponse.ok(tokens.listAll(PageParams.parse(ContextUtil.current())), ApiTrace.current());
    }

    // ---------------------------------------------------------- 自助授权（本人）

    /** 我授权的 Agent Token 列表；只会返回属于当前用户的那些。 */
    @Mapping(value = "/me/agent-tokens", method = MethodType.GET)
    public ApiResponse myTokens() {
        Principal me = CurrentUser.require(ContextUtil.current());
        return ApiResponse.ok(
                tokens.listForAgentUser(me.userId(), PageParams.parse(ContextUtil.current())),
                ApiTrace.current());
    }

    /** 为自己签发一枚 Token；归属恒为当前用户。 */
    @Mapping(value = "/me/agent-tokens", method = MethodType.POST)
    public ApiResponse createMine(@Body CreateMyAgentTokenRequest request) {
        Context ctx = ContextUtil.current();
        Principal me = CurrentUser.require(ctx);
        if (request == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        AgentTokenService.Issued issued = tokens.issueForSelf(me.userId(), new AgentTokenService.IssueCommand(
                request.name(), me.userId(), request.auctionIds(), request.scopes(),
                parseExpiresAt(request.expiresAt()), request.rateLimitPerMinute()));
        ctx.status(201);
        return ApiResponse.ok(
                new AgentTokenView(issued.token(), issued.tokenId(), ApiTime.format(issued.expiresAt())),
                ApiTrace.current());
    }

    /** 吊销自己的 Token；不属于本人时 404。 */
    @Mapping(value = "/me/agent-tokens/{tokenId}/revoke", method = MethodType.POST)
    public ApiResponse revokeMine(@Path("tokenId") String tokenId) {
        Principal me = CurrentUser.require(ContextUtil.current());
        AgentToken token = tokens.revokeForAgentUser(tokenId, me.userId());
        return ApiResponse.ok(
                new AgentTokenView(null, token.tokenId(), ApiTime.format(token.expiresAt())),
                ApiTrace.current());
    }

    /**
     * 解析 ISO-8601 时间。显式解析而不是让绑定器猜：绑定器能接受多少种格式，
     * 契约里没有承诺，换个序列化器配置就可能悄悄改变行为（与 {@code ApiTime} 同样的理由）。
     */
    private static Instant parseExpiresAt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "expiresAt 不能为空");
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "expiresAt 必须是 ISO-8601 时刻（如 2026-12-31T00:00:00Z）",
                    Map.of("expiresAt", raw));
        }
    }
}
