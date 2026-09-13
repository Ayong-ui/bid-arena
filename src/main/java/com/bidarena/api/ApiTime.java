package com.bidarena.api;

import java.time.Instant;

/**
 * 时间在 HTTP 上的序列化。
 *
 * <p>契约里所有时间字段都是 {@code format: date-time} 的字符串。这里统一用
 * {@link Instant#toString()}（ISO-8601 带 {@code Z}）而不是让 JSON 序列化器去猜：
 * 序列化器是否注册了 JavaTime 模块、是否输出时间戳，都是外部配置，
 * 换个环境就可能悄悄改变 wire format。显式格式化把这个不确定性消掉，
 * 也让测试可以直接 {@link Instant#parse} 回来断言。
 */
public final class ApiTime {

    private ApiTime() {}

    public static String format(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
