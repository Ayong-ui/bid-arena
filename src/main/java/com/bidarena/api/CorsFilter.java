package com.bidarena.api;

import java.util.List;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * CORS：按白名单回具体的 Origin，并短路浏览器的预检请求。
 *
 * <h2>为什么不能用 {@code *}</h2>
 * 本项目用 {@code Authorization: Bearer} 而不是 Cookie，但跨域放开仍然危险：
 * 任何页面都能用访客浏览器里已保存的令牌发起出价。因此只回
 * {@link #origins} 里列出的 Origin，其余一律不加 CORS 头——
 * 浏览器会自己拒绝，服务端不必、也不应该抛错（同一个接口可能同时被同源前端与
 * 脚本客户端使用，对后者报错才是错的）。
 *
 * <h2>为什么回显 Origin 而不是回第一个白名单项</h2>
 * 多个 Origin 共用一份部署时，必须回**请求方自己那个**，否则第二个前端会被浏览器拒掉。
 * 回显是安全的：只有命中白名单的 Origin 才会被回显。
 *
 * <h2>为什么预检要在这里短路</h2>
 * 预检请求不带 {@code Authorization}，若让它继续走到鉴权过滤器就会被 401，
 * 浏览器于是认定跨域失败。在 CORS 层回 204 是所有浏览器都认的做法。
 */
public class CorsFilter implements Filter {

    private final List<String> origins;

    public CorsFilter(List<String> origins) {
        this.origins = List.copyOf(origins);
    }

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        String origin = ctx.header("Origin");
        boolean allowed = origin != null && origins.contains(origin);

        if (allowed) {
            ctx.headerSet("Access-Control-Allow-Origin", origin);
            // 声明"响应随 Origin 变化"，否则中间缓存会把 A 站点的响应喂给 B 站点。
            ctx.headerSet("Vary", "Origin");
            ctx.headerSet("Access-Control-Allow-Credentials", "true");
        }

        // 预检：OPTIONS + Access-Control-Request-Method。真正的业务请求不会带这个头，
        // 因此不能用"是不是 OPTIONS"来判断，否则把合法的 OPTIONS 业务请求也吞掉。
        boolean preflight = "OPTIONS".equalsIgnoreCase(ctx.method())
                && ctx.header("Access-Control-Request-Method") != null;
        if (preflight) {
            if (allowed) {
                ctx.headerSet("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                // Idempotency-Key 必须列进来：漏掉它会让浏览器预检通过、真实请求被拦，
                // 表现为"出价接口在某些浏览器上莫名为 0 字节失败"。
                ctx.headerSet("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key");
                ctx.headerSet("Access-Control-Max-Age", "600");
            }
            ctx.status(204);
            ctx.setHandled(true);
            return;
        }

        chain.doFilter(ctx);
    }
}
