package com.bidarena.shared;

/**
 * 业务结果码。
 *
 * <p>取值与 {@code docs/openapi.yaml} 里 {@code ApiCode} 的枚举**逐项一致**，是 Layer-1 契约的一部分：
 * 改动这里必须同步改契约。HTTP 状态码由本枚举推导，避免"业务码与状态码各写一套"而对不上。
 *
 * <p>客户端（Web 前端、模拟脚本、竞拍 Agent）应当以 {@code code} 判定业务结果，
 * 而不是解析 message 或只看 HTTP 状态码——多个不同的业务冲突共用 409。
 */
public enum ErrorCode {

    OK(200),

    /** 参数缺失或格式非法。 */
    VALIDATION_FAILED(400),

    UNAUTHENTICATED(401),

    FORBIDDEN(403),

    NOT_FOUND(404),

    /** 拍卖不在可操作状态（如未开始、已结束、已结算）。 */
    INVALID_STATE(409),

    /** 出价低于「当前最高价 + 最小加价」。 */
    BID_TOO_LOW(409),

    /** 出价时已超过截止时间（含已进入结算）。 */
    BID_LATE(409),

    /** 未加入该拍卖间。 */
    NOT_JOINED(409),

    /** 本次需新增冻结的金额超过可用余额。 */
    INSUFFICIENT_BALANCE(409),

    /**
     * 同一 requestId 的重复提交，返回的是**首次**结果而非本次重新计算的结果。
     * 这是一个可识别的成功语义（不是错误），但需要与首次提交区分，因此单列一个码。
     */
    IDEMPOTENCY_REPLAY(409),

    /** 其他业务冲突（唯一约束、并发写冲突等）。 */
    CONFLICT(409),

    RATE_LIMITED(429),

    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isOk() {
        return this == OK;
    }
}
