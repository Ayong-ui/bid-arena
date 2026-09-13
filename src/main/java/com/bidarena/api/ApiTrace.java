package com.bidarena.api;

import com.bidarena.shared.TraceId;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextUtil;

/**
 * 把追踪 ID 绑定在一次请求的 {@link Context} 上。
 *
 * <p>刻意不用 ThreadLocal 静态变量：Solon 的 {@link Context} 本身就是"这一次请求"的载体，
 * 从过滤器到控制器同属一条调用链，既不会串号，也不会有线程池复用带来的脏值。
 *
 * <p>{@link #next()} 生成新 ID，{@link #of} 复用同一个（同一次请求里调用多次得到同一个值）。
 */
public final class ApiTrace {

    /** 存在 {@link Context} 属性里的键。控制器与过滤器都通过本类访问，不直接写字面量。 */
    private static final String ATTR = "bidarena.traceId";

    private ApiTrace() {}

    /** 取出本次请求的追踪 ID；没有就生成一个并记住。 */
    public static String of(Context ctx) {
        String id = ctx.attr(ATTR);
        if (id == null) {
            id = TraceId.next();
            ctx.attrSet(ATTR, id);
        }
        return id;
    }

    /**
     * 当前请求的追踪 ID，供控制器使用。
     *
     * <p>{@link ContextUtil#current()} 由 Solon 在处理任何请求之前设置（见 {@code SolonApp.tryHandle}），
     * 因此控制器里一定能取到。若在请求之外调用（例如定时任务线程）会得到 null，
     * 那时 {@link #of} 会空指针——这是刻意的：追踪 ID 只属于 HTTP 请求，不该被别处虚构。
     */
    public static String current() {
        return of(ContextUtil.current());
    }
}
