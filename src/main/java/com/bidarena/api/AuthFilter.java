package com.bidarena.api;

import com.bidarena.identity.application.TokenService;
import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.Set;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * Bearer 令牌鉴权。除了白名单路径，所有 {@code /api/v1/**} 都必须携带有效令牌。
 *
 * <p>白名单只有登录与健康检查。健康检查公开是刻意的：探活不该依赖"先登录"，
 * 否则令牌签名密钥配错时，探活会失败而看起来像服务挂了。
 *
 * <p>非 {@code /api/v1/} 的请求（静态资源、WebSocket 握手）直接放行：
 * 本过滤器只负责 API 的认证，把别的流量也拦下来会让前端页面都打不开。
 *
 * <p><b>{@code /api/v1/agent/**} 也整体跳过</b>：那一组接口的凭证是 Agent Token
 * 而不是 JWT（见 {@link ApiPaths#AGENT_PREFIX}）。若不跳过，Agent 会先被 JWT 校验拦下
 * 并收到“缺少 Bearer 令牌”，而它明明带了令牌——一个只会误导排查方向的 401。
 * 它们由 {@code agentaccess.adapter.AgentAuthFilter} 独立认证。
 *
 * <p>鉴权通过后把 {@link Principal} 放进 {@link Context} 属性，控制器用
 * {@link CurrentUser} 读取。**不在这里解析业务角色**——"谁能做什么"由
 * {@link CurrentUser#requireAdmin} 或应用服务判断，过滤器只管"你是谁"。
 */
public class AuthFilter implements Filter {

    /** "方法 路径" 形式，与方法绑定，避免 GET 白名单被 POST 复用。 */
    private static final Set<String> PUBLIC = Set.of(
            "POST /api/v1/auth/login",
            "GET /api/v1/health");

    private final TokenService tokens;

    public AuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        String path = ctx.path();

        // 预检请求不带 Authorization，浏览器要求它必须无凭证通过。
        if ("OPTIONS".equalsIgnoreCase(ctx.method()) || !path.startsWith("/api/v1/")) {
            chain.doFilter(ctx);
            return;
        }
        if (PUBLIC.contains(ctx.method().toUpperCase() + " " + path)) {
            chain.doFilter(ctx);
            return;
        }
        // Agent API 用独立凭证，不归本过滤器管（见类注释与 ApiPaths.AGENT_PREFIX）。
        if (path.startsWith(ApiPaths.AGENT_PREFIX)) {
            chain.doFilter(ctx);
            return;
        }

        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ApiWriter.failure(ctx, ErrorCode.UNAUTHENTICATED, "缺少 Bearer 令牌", null);
            return;
        }

        String raw = header.substring("Bearer ".length()).trim();
        if (raw.isEmpty()) {
            ApiWriter.failure(ctx, ErrorCode.UNAUTHENTICATED, "Bearer 令牌为空", null);
            return;
        }

        try {
            Principal principal = tokens.verify(raw);
            ctx.attrSet(CurrentUser.ATTR, principal);
        } catch (BizException e) {
            // 令牌无效的各种原因（格式、签名、过期）对外统一为 401，
            // 不告诉攻击者"签名对了但过期了"这种可用于试探的信息。
            ApiWriter.failure(ctx, ErrorCode.UNAUTHENTICATED, e.getMessage(), null);
            return;
        }

        chain.doFilter(ctx);
    }
}
