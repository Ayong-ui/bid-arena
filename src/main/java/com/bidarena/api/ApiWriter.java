package com.bidarena.api;

import com.bidarena.shared.ApiResponse;
import com.bidarena.shared.ErrorCode;
import java.io.InputStream;
import java.util.Map;
import org.noear.solon.core.handle.Context;

/**
 * 把统一响应封套写回 {@link Context}。
 *
 * <p>只用于**失败**路径：成功路径由控制器返回 {@link ApiResponse}，交给 Solon 渲染。
 * 之所以要有这一处手写：异常可能发生在路由之前（鉴权、参数绑定），那时已经没有"返回值"可渲染，
 * 只能直接写响应体。
 *
 * <p>渲染后必须 {@link Context#setHandled(boolean)}，否则 Solon 的 {@code doStatus}
 * 会认为本次请求无人处理，再追加一次状态处理，得到"封套 + 另一个错误页"的拼接响应。
 */
public final class ApiWriter {

    /**
     * 提前拒绝时可以顺手读掉的请求体上限。
     *
     * <p>取值依据：项目里最大的请求体是出价（约 100 字节），管理员建拍卖也就几百字节，
     * 64 KiB 给足了余量，又不至于让一个未认证请求拖走大量带宽。
     */
    private static final int MAX_DRAIN_BYTES = 64 * 1024;

    private ApiWriter() {}

    public static void failure(Context ctx, ErrorCode code, String message, Object data) {
        failure(ctx, code.httpStatus(), code, message, data);
    }

    /**
     * 用指定的 HTTP 状态码写封套，业务码可以与状态码不同。
     *
     * <p>存在的理由：Solon 自身会设出 405/503 这类**枚举里没有同名项**的状态码。
     * 若强行用 {@code code.httpStatus()} 覆盖，就会把真实状态码改掉（405 变 500），
     * 让客户端看到与服务端日志不一致的结论。宁可让两者不同，也不伪造状态码。
     */
    public static void failure(Context ctx, int httpStatus, ErrorCode code, String message, Object data) {
        drainRequestBody(ctx);
        ctx.status(httpStatus);
        ApiResponse body = ApiResponse.failure(code, message, data, ApiTrace.of(ctx));
        try {
            ctx.render(body);
        } catch (Throwable renderFailure) {
            // 序列化都失败了，至少要给客户端一个可解析的封套，而不是 HTML 错误页。
            // 这里不能再调 render（同一个失败原因），直接拼最小 JSON。
            ctx.output("{\"code\":\"INTERNAL_ERROR\",\"message\":\"响应序列化失败\",\"data\":{},"
                    + "\"requestId\":\"" + ApiTrace.of(ctx) + "\"}");
        }
        ctx.setHandled(true);
    }

    /**
     * 提前拒绝时把请求体读干净。
     *
     * <h2>为什么非做不可</h2>
     * 鉴权过滤器在**读请求体之前**就回写 401，而 smartboot（Solon 的 smarthttp 底层）
     * 只在"请求体已被读完"时才保持连接复用（{@code HttpMessageProcessor.keepConnection}
     * 里的 {@code request.getInputStream().available() <= 0}）。两者相遇时会出现连接错位：
     * 留在内核缓冲区里的请求体被当成下一条请求的请求行，于是同一个 keep-alive 连接上的
     * 下一次请求被解析成 {@code method={"name":"…"}POST}、{@code path=/api/v1/auth/login}，
     * 调用方收到一个莫名其妙的 401。这正是 {@code DEBUG_LOG} DBG-23 记录的现象。
     *
     * <p>框架自带的那个判断本身是对的，但它与"客户端把请求体发完"之间存在竞态：
     * 响应回写时字节可能还在路上，{@code available()} 返回 0，框架判定连接可复用，
     * 而随后到达的字节就污染了下一条请求。**只有真的把体读掉才能消除这个竞态**，
     * 因此修在这里（唯一一处"没经过控制器就回写响应"的地方）。
     *
     * <h2>为什么设上限</h2>
     * 未认证的请求不值得为它读完一个巨大的体（那会变成放大攻击面）。超过上限时
     * 退化为"告诉客户端不要再复用这条连接"——合规客户端（JDK、浏览器、curl）读到
     * {@code Connection: close} 就会换一条连接。残余风险只发生在"请求体超过上限
     * 且客户端不理会该响应头"的组合上；本项目全部接口的请求体都在上限之内
     * （最大的是出价，约 100 字节）。
     *
     * <p>读失败一律当"没法保证连接同步"处理：宁可让客户端换连接，也不要赌它没被污染。
     */
    private static void drainRequestBody(Context ctx) {
        try (InputStream body = ctx.bodyAsStream()) {
            if (body == null) {
                return;
            }
            byte[] buffer = new byte[4096];
            int total = 0;
            for (int read; (read = body.read(buffer)) >= 0; ) {
                total += read;
                if (total > MAX_DRAIN_BYTES) {
                    ctx.headerSet("Connection", "close");
                    return;
                }
            }
        } catch (Throwable drainFailure) {
            ctx.headerSet("Connection", "close");
        }
    }
}
