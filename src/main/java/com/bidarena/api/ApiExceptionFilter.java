package com.bidarena.api;

import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.Map;
import org.noear.solon.core.exception.StatusException;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 最外层过滤器：把任何抛到顶层的异常翻译成统一封套。
 *
 * <p>为什么在过滤器而不是控制器里 {@code try/catch}：异常可能来自参数绑定、鉴权、
 * 路由匹配，甚至来自别的过滤器。只有包在最外层的这一层能全部接住。
 * 注册顺序见 {@code Application}（数值越小越靠外层）。
 *
 * <h2>三类异常，三种待遇</h2>
 * <ul>
 *   <li>{@link BizException} 是**可预期**的业务结论（余额不足、迟到、状态不对），
 *       按它自带的 {@link ErrorCode} 翻译，不打错误日志——并发下被拒是常态，
 *       每个拒绝都打 stack trace 会把真正的缺陷淹没。</li>
 *   <li>{@link StatusException} 来自 Solon 路由：路径不存在（404）、路径对但方法不对（405）。
 *       它不是缺陷，也不是业务结论，但同样必须变成封套——否则客户端会拿到一个空 body，
 *       前端统一解包逻辑在这里就会抛 JSON 解析错误，把一个"路径写错了"伪装成"服务崩了"。</li>
 *   <li>其它 {@link Throwable} 是**程序缺陷**，一律 500 且记录 ERROR 与堆栈。
 *       绝不把原始异常信息回给客户端：那会把 SQL、表名、文件路径泄漏出去。</li>
 * </ul>
 */
public class ApiExceptionFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionFilter.class);

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        try {
            chain.doFilter(ctx);
        } catch (Throwable t) {
            handle(ctx, t);
        }
        renderBareErrorIfAny(ctx);
    }

    private void handle(Context ctx, Throwable raw) throws Throwable {
        Throwable cause = unwrap(raw);

        if (alreadyWritten(ctx)) {
            // 响应已经写出一部分（例如渲染到一半失败），再写第二个封套会把响应体拼坏，
            // 客户端拿到的是两个 JSON 拼接的非法内容。此时只能记录，让它保持原样。
            if (cause instanceof BizException e) {
                log.warn("业务异常发生在响应已写出之后 path={} code={}", ctx.path(), e.code());
                return;
            }
            log.error("未处理异常发生在响应已写出之后 path={} requestId={}",
                    ctx.path(), ApiTrace.of(ctx), raw);
            throw raw;
        }

        if (cause instanceof BizException e) {
            ApiWriter.failure(ctx, e.code(), e.getMessage(), e.details());
            return;
        }

        if (cause instanceof StatusException e) {
            ErrorCode code = ErrorCode.fromHttpStatus(e.getCode());
            String message = code == ErrorCode.NOT_FOUND ? "路径不存在" : "请求方法不被允许";
            ApiWriter.failure(ctx, e.getCode(), code, message,
                    Map.of("path", ctx.path(), "method", ctx.method()));
            return;
        }

        log.error("未处理异常 path={} requestId={}", ctx.path(), ApiTrace.of(ctx), raw);
        ApiWriter.failure(ctx, ErrorCode.INTERNAL_ERROR, "服务器内部错误", Map.of());
    }

    /**
     * 有些状态码是框架直接设在 {@link Context} 上、既不抛异常也不写响应体的
     * （例如容器已停止时的 503）。这里补一个封套，保证"任何非 2xx 都有可解析的 body"。
     */
    private static void renderBareErrorIfAny(Context ctx) {
        if (alreadyWritten(ctx)) {
            return;
        }
        int status = ctx.status();
        if (status < 400) {
            return;
        }
        ApiWriter.failure(ctx, status, ErrorCode.fromHttpStatus(status), "请求未能被处理",
                Map.of("status", status));
    }

    /**
     * 在因果链里找本项目认识的异常类型。
     *
     * <p>事务边界上的重试与包装（{@code Db.tx} 的连接归还、驱动的 SQLException 包装）
     * 可能把业务异常塞进 cause 里，只 {@code catch} 最外层类型会把它当成未知缺陷，
     * 于是"余额不足"变成 500 并打出一条误导人的堆栈。
     *
     * <p>找不到已知类型时返回**原始的** throwable 而不是最深的 cause：
     * 日志里应当保留完整链条，而不是只剩一颗不知从哪来的根因。
     */
    private static Throwable unwrap(Throwable t) {
        Throwable cursor = t;
        for (int depth = 0; depth < 8 && cursor != null; depth++) {
            if (cursor instanceof BizException || cursor instanceof StatusException) {
                return cursor;
            }
            cursor = cursor.getCause();
        }
        return t;
    }

    private static boolean alreadyWritten(Context ctx) {
        return ctx.getHandled() || ctx.getRendered();
    }
}
