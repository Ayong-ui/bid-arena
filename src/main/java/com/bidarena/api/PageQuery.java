package com.bidarena.api;

import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.List;
import java.util.Map;
import org.noear.solon.core.handle.Context;

/**
 * 分页参数与分页结果。
 *
 * <p>上限 {@value #MAX_SIZE} 是契约规定（{@code Size.maximum}），不是随手取的数：
 * 没有上限时，客户端一个 {@code size=1000000} 就能把整张流水表读进内存。
 * 越界的处理是**拒绝**而不是静默截断——静默截断会让客户端以为拿全了。
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public int limit() {
        return size;
    }

    public int offset() {
        return (page - 1) * size;
    }

    /** 分页返回体。字段名与 openapi 的 {@code Envelope} + 列表语义一致。 */
    public record Page<T>(List<T> items, int page, int size, long total) {}

    public static PageQuery parse(Context ctx) {
        int page = positive(ctx, "page", 1);
        int size = positive(ctx, "size", DEFAULT_SIZE);
        if (page < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "page 必须 >= 1", Map.of("page", page));
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "size 必须在 1.." + MAX_SIZE + " 之间", Map.of("size", size, "max", MAX_SIZE));
        }
        return new PageQuery(page, size);
    }

    private static int positive(Context ctx, String name, int fallback) {
        String raw = ctx.param(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    name + " 必须是整数", Map.of("name", name, "value", raw));
        }
    }
}
