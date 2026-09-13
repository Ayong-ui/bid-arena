package com.bidarena.shared;

import java.util.Map;

/**
 * 统一的 HTTP 响应封套，字段与 {@code docs/openapi.yaml} 的 {@code Envelope} 逐项一致。
 *
 * <p>{@code data} 恒存在（无内容时为 {@code {}}）：契约把四个字段都列为必填，
 * 若成功响应偶尔省略 {@code data}，客户端就要到处判空，契约也就失去了意义。
 *
 * <h2>注意两个同名的 requestId</h2>
 * 本类的 {@code requestId} 是**服务端生成的请求追踪 ID**，与出价请求里的
 * {@code requestId}（客户端生成的幂等键）不是同一个东西，只是恰好同名。
 * 详见 {@link TraceId}。
 */
public record ApiResponse(String code, String message, Object data, String requestId) {

    public static ApiResponse ok(Object data, String requestId) {
        return new ApiResponse(ErrorCode.OK.name(), "ok", data == null ? Map.of() : data, requestId);
    }

    /** 与 {@link #ok} 的唯一区别是 {@code code}，用于让调用方能从封套直接认出"这是重放"。 */
    public static ApiResponse replay(Object data, String requestId) {
        return new ApiResponse(
                ErrorCode.IDEMPOTENCY_REPLAY.name(), "重复提交：返回首次结果",
                data == null ? Map.of() : data, requestId);
    }

    public static ApiResponse failure(ErrorCode code, String message, Object data, String requestId) {
        return new ApiResponse(code.name(), message, data == null ? Map.of() : data, requestId);
    }
}
