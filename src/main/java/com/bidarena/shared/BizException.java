package com.bidarena.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务异常：表示"请求不合法"这一可预期结果，不是程序缺陷。
 *
 * <p>与 {@link RuntimeException} 的区别在于它携带一个 {@link ErrorCode}，
 * 适配层必须据此翻译成 API 响应，**不允许**退化成 500。
 *
 * <p>{@code details} 用于给调用方补充机器可读的上下文，例如余额不足时附上
 * 需要的金额与可用余额——并发场景下"为什么被拒"本身就是要解释的事实。
 */
public class BizException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public BizException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public BizException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    /** 链式补充上下文字段，便于在事务中途逐层加上"当时看到的事实"。 */
    public BizException with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put(key, value);
        return new BizException(code, getMessage(), merged);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // 业务拒绝是高频、可预期的路径（例如并发下 19 个出价被拒），
        // 采集栈帧只会增加开销而没有诊断价值。真正的缺陷走普通异常。
        return this;
    }
}
