package com.bidarena.agentaccess.adapter;

import com.bidarena.agentaccess.application.AgentTokenService;
import com.bidarena.agentaccess.domain.AgentToken;
import com.bidarena.api.ApiPaths;
import com.bidarena.api.ApiWriter;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * Agent API 的认证与限流。<b>只作用于 {@code /api/v1/agent/**}</b>，其余路径原样放行。
 *
 * <h2>它是 Agent 端口的唯一入口守卫</h2>
 * {@code api.AuthFilter} 会跳过 Agent 前缀，因此这一组接口的"你是谁"完全由本过滤器决定：
 * 没带 Token、Token 无效、已过期或已吊销，都在这里变成 401，绝不会漏到控制器。
 *
 * <h2>与 JWT 的隔离是双向的</h2>
 * <ul>
 *   <li>用户 JWT 在这里过不了：它是一串 JWT，摘要不会命中任何 {@code agent_tokens} 记录 → 401。</li>
 *   <li>Agent Token 在普通接口上也过不了：{@code api.AuthFilter} 会拿它当 JWT 验签 → 401。</li>
 * </ul>
 * 两条规则合起来意味着"两种凭证互不通用"，这正是原文要求的"独立的认证凭据"。
 *
 * <h2>顺序：先认证，再限流，最后才是业务授权</h2>
 * 限流放在认证之后，是因为配额按 Token 计；放在授权之前，是因为一个已被吊销/伪造的
 * Token 不应该占用任何配额，而一个有效但越权的 Token 仍然应该在消耗配额的意义上被计数
 * ——否则"用合法 Token 疯狂探测不在范围内的拍卖"就成了一个不花配额的扫描接口。
 */
public class AgentAuthFilter implements Filter {

    private final AgentTokenService tokens;

    public AgentAuthFilter(AgentTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        String path = ctx.path();

        // 不是 Agent 路径：本过滤器完全不参与（普通接口由 AuthFilter 负责）。
        if (!path.startsWith(ApiPaths.AGENT_PREFIX)) {
            chain.doFilter(ctx);
            return;
        }
        // 预检请求不带凭证，必须无凭证通过，否则浏览器永远看不到真实响应。
        if ("OPTIONS".equalsIgnoreCase(ctx.method())) {
            chain.doFilter(ctx);
            return;
        }

        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ApiWriter.failure(ctx, ErrorCode.UNAUTHENTICATED, "缺少 Agent Token（Authorization: Bearer <token>）", null);
            return;
        }
        String presented = header.substring("Bearer ".length()).trim();
        if (presented.isEmpty()) {
            ApiWriter.failure(ctx, ErrorCode.UNAUTHENTICATED, "Agent Token 为空", null);
            return;
        }

        AgentToken token;
        try {
            token = tokens.authenticate(presented);
        } catch (BizException e) {
            // 认证失败的各类原因对外统一为 401（不区分"格式错/过期/吊销"）。
            ApiWriter.failure(ctx, ErrorCode.UNAUTHENTICATED, e.getMessage(), null);
            return;
        }

        try {
            tokens.checkRateLimit(token);
        } catch (BizException e) {
            ApiWriter.failure(ctx, e.code(), e.getMessage(), e.details());
            return;
        }

        // 只放"已认证的 Agent 身份"，不放明文 Token：控制器与业务层都不该拿到能再次使用的凭证。
        ctx.attrSet(AgentCaller.ATTR, token);
        chain.doFilter(ctx);
    }
}
