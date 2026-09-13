package com.bidarena.api;

import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.Map;
import org.noear.solon.core.handle.Context;

/**
 * 幂等键的解析规则，出价接口（真人 / Agent）共用。
 *
 * <p>抽出来的理由不是"少写几行"，而是<b>规则必须只有一份</b>：原文要求同一个
 * {@code requestId} 只生效一次，而 {@code requestId} 可以从请求体或
 * {@code Idempotency-Key} 头进来。若两个入口各自实现一遍，只要有一处对"两者不一致时"
 * 的处理不同，同一次重试在真人路径与 Agent 路径上就会产生不同的幂等结果——
 * 而那正是"重复冻结"的入口。
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    /**
     * 解析出本次请求唯一的幂等键。
     *
     * <p>只给头时以头为准；两个都给了就必须一致；都没给则 400。
     * 不一致时<b>不猜</b>：猜错的代价是把一次重试当成新出价，重复冻结资金。
     */
    public static String resolve(Context ctx, String fromBody) {
        String header = ctx.header("Idempotency-Key");
        boolean bodyBlank = fromBody == null || fromBody.isBlank();
        boolean headerBlank = header == null || header.isBlank();

        if (bodyBlank && headerBlank) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "缺少幂等键：请在请求体提供 requestId，或用 Idempotency-Key 头");
        }
        if (!bodyBlank && !headerBlank && !fromBody.equals(header.trim())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "body.requestId 与 Idempotency-Key 不一致",
                    Map.of("requestId", fromBody, "idempotencyKey", header));
        }
        return bodyBlank ? header.trim() : fromBody;
    }
}
