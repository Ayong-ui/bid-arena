package com.bidarena.shared;

import java.util.UUID;

/**
 * 服务端为每一次请求生成的追踪 ID。
 *
 * <h2>它和出价请求里的 requestId 不是同一个东西</h2>
 * 两者恰好同名，但生命周期与责任人完全不同，混淆会造成真实的资金错误：
 *
 * <table border="1">
 *   <caption>两个 requestId 的对比</caption>
 *   <tr><th></th><th>响应封套的 requestId</th><th>出价请求的 requestId</th></tr>
 *   <tr><td>生成方</td><td>服务端</td><td>客户端</td></tr>
 *   <tr><td>标识</td><td>一次 HTTP 往返</td><td>一次出价意图</td></tr>
 *   <tr><td>每次请求是否相同</td><td>不同（每次都是新的）</td><td>同一次出价重发必须相同</td></tr>
 *   <tr><td>用途</td><td>把日志、错误响应与客户端报障串起来</td><td>幂等去重</td></tr>
 * </table>
 *
 * <p>把两者混起来的后果是双向的：用追踪 ID 当幂等键，会让每次重试都变成一次新的出价
 * （重复扣款）；用一个固定的幂等键当追踪 ID，会让两次不同的出价被误判为重复
 * （第二次静默不生效，用户以为加价了其实没有）。
 */
public final class TraceId {

    private static final String PREFIX = "req_";

    private TraceId() {}

    /**
     * 生成一个新的追踪 ID。
     *
     * <p>用 UUID 而不是自增序列或时间戳：追踪 ID 会被回显给客户端并写进日志，
     * 它不该泄漏请求量、时间或服务实例信息。也不需要密码学强度——它不是凭据。
     */
    public static String next() {
        return PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
