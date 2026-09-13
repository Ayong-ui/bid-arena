package com.bidarena.shared;

import java.util.List;

/**
 * 分页参数与分页结果。
 *
 * <p>上限 {@value #MAX_SIZE} 是契约规定（{@code Size.maximum}），不是随手取的数：
 * 没有上限时，客户端一个 {@code size=1000000} 就能把整张流水表读进内存。
 * 越界的处理是**拒绝**而不是静默截断——静默截断会让客户端以为拿全了。
 *
 * <p>这个记录放在共享内核而不是 HTTP 层：使用它的 query 用例与 HTTP 解析参数是
 * 两件事。HTTP 侧把查询串解析成 {@code PageQuery}（见 {@code api.PageParams}），
 * 应用层只看到普通的整数，因此换掉 HTTP 层不会牵动用例，反之亦然。
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
}
