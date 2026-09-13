package com.bidarena.identity.domain;

/**
 * 已认证的调用者：**只**来自已验证的令牌，不接受来自请求体的用户 ID。
 *
 * <p>这是整个鉴权设计的支点。若"我是谁"可以由客户端在请求体里指定，
 * 那么任何人都能冒充别人出价、花别人的钱，而 RBAC 也就形同虚设。
 * 因此所有下游接口都从本类型取 {@code userId}，绝不从路径或 body 取。
 *
 * <p>角色放在令牌里而非每次查库：JWT 的取舍本来就是"用无法即时吊销换取无状态"，
 * 已在 {@code DECISIONS.md} D-3 记录其代价（短 TTL 缓解）。
 */
public record Principal(String userId, UserRole role) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
