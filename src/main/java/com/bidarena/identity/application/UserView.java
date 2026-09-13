package com.bidarena.identity.application;

import com.bidarena.identity.domain.User;

/**
 * 用户的对外投影，字段与 {@code openapi.yaml} 的 {@code User} 逐项一致。
 *
 * <p>为什么不直接序列化领域类型 {@link User}：领域类型的字段会随内部需要变化
 * （例如以后加 {@code createdAt}），而对外契约必须由我们显式控制。
 * 若领域类型即响应体，一次"内部重构"就会悄悄改变 wire format，
 * 前端和竞拍 Agent 会在没有任何契约变更的情况下失效。
 *
 * <p>{@code role} 用 {@code String} 而非枚举，让序列化结果不依赖序列化器对枚举的处理方式
 * （输出 {@code name} 还是 {@code toString}）。
 */
public record UserView(String id, String name, String role) {

    public static UserView of(User user) {
        return new UserView(user.id(), user.displayName(), user.role().name());
    }
}
