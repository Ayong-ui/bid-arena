package com.bidarena.api;

import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.shared.PageQuery;
import java.util.Map;
import org.noear.solon.core.handle.Context;

/**
 * 把 HTTP 查询串解析成分页参数。
 *
 * <p>放在 {@code api} 包而不是 {@link PageQuery} 里：解析需要 Solon 的 {@link Context}，
 * 而使用分页参数的应用服务不该因此依赖 HTTP 框架。两件事分开之后，
 * "应用层不得依赖 HTTP 层"这条规则才能由架构测试守住（见 {@code ArchitectureTest}）。
 */
public final class PageParams {

    private PageParams() {}

    public static PageQuery parse(Context ctx) {
        int page = positive(ctx, "page", 1);
        int size = positive(ctx, "size", PageQuery.DEFAULT_SIZE);
        if (page < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "page 必须 >= 1", Map.of("page", page));
        }
        if (size < 1 || size > PageQuery.MAX_SIZE) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "size 必须在 1.." + PageQuery.MAX_SIZE + " 之间",
                    Map.of("size", size, "max", PageQuery.MAX_SIZE));
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
