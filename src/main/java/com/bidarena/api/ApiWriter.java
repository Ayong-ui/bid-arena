package com.bidarena.api;

import com.bidarena.shared.ApiResponse;
import com.bidarena.shared.ErrorCode;
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
}
