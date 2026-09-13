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

    /**
     * 拍卖已进入最后一段"博弈时间"，此处只允许真人出价，竞拍 Agent 被系统强制清场。
     *
     * <p>这是对原文"最后五秒延时"规则的**有意收紧**（见 DECISIONS D-32）：
     * 尾段是留给真人的博弈窗口，Agent 无论人是否在场都不得出价。
     * HTTP 状态是 403 而不是 409——它不是"出价内容不合法"，而是"这个主体没有资格在此刻出价"。
     */
    HUMAN_ONLY_PERIOD(403),

    /** 未加入该拍卖间。 */
    NOT_JOINED(409),

    /** 本次需新增冻结的金额超过可用余额。 */
    INSUFFICIENT_BALANCE(409),

    /**
     * 同一 requestId 的重复提交，返回的是**首次**结果而非本次重新计算的结果。
     * 这是一个可识别的成功语义（不是错误），但需要与首次提交区分，因此单列一个码。
     *
     * <p>HTTP 状态码是 200：出价确实生效了（在第一次），把它当成 409 会让客户端
     * 把一次正常的重试当成失败。客户端可以只用
     * {@link #isOk()} 判断成功，不必自己列举成功码。
     */
    IDEMPOTENCY_REPLAY(200),

    /** 其他业务冲突（唯一约束、并发写冲突等）。 */
    CONFLICT(409),

    /**
     * 路径存在但方法不对（如对 {@code /api/v1/auth/login} 发 GET）。
     *
     * <p>由 Solon 路由在匹配到路径、方法不匹配时抛出，本项目的控制器永远不会主动返回它。
     * 把它列进枚举是为了让这个场景也走统一封套，而不是给客户端一个空 body 的 405。
     */
    METHOD_NOT_ALLOWED(405),

    RATE_LIMITED(429),

    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 是否代表"操作成功"。
     *
     * <p>存在两个成功码（{@link #OK} 与 {@link #IDEMPOTENCY_REPLAY}）是有意的，
     * 代价是调用方不能再写 {@code code == OK}——那会把一次成功的重试判成失败。
     * 因此把判断收敛到这一个方法里，两侧都只依赖它。
     */
    public boolean isOk() {
        return httpStatus == 200;
    }

    /**
     * 由 HTTP 状态码反查业务码。
     *
     * <p>用于把**不是我方代码**产生的状态码（Solon 路由的 404/405、容器停止时的 503）
     * 也翻译成封套里的 {@code code}。找不到对应项时返回 {@link #INTERNAL_ERROR}：
     * "不认识的状态"归为内部错误，比猜一个具体码更安全。
     */
    public static ErrorCode fromHttpStatus(int httpStatus) {
        for (ErrorCode code : values()) {
            if (code.httpStatus == httpStatus) {
                return code;
            }
        }
        return INTERNAL_ERROR;
    }
}
