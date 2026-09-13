package com.bidarena.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 广播事件里的用户匿名标识。
 *
 * <h2>为什么需要一个"能算出来"的匿名标识</h2>
 * WebSocket 事件是**一场拍卖的所有参与者**都收到的（见
 * {@code docs/REALTIME_AND_COMMAND_FLOW.md} §4）。若事件里直接带 {@code user_id}，
 * 等于把用户标识体系广播给所有同场用户，而且这份 payload 会被前端缓存、写日志、录进回放。
 *
 * <p>但"领先者是谁"又必须让本人认出来（要高亮"我当前领先"）。两个需求同时满足的办法是：
 * 用一个**确定性**的匿名标识——客户端对自己的 {@code userId} 算一次，就能比对出来。
 * 若改用随机标识，服务端就得给每个连接生成不同的 payload，"一次广播"就没了。
 *
 * <h2>为什么不是加密</h2>
 * 这里的目标是**不可枚举 + 不泄露内部 ID**，不是不可逆（{@code userId} 本身不敏感、
 * 熵也不够，彩虹表更不是威胁模型）。因此取 SHA-256 前 8 位十六进制：
 * 64 bit 足够让"猜另一个用户的匿名 ID"没有意义，短到便于日志与前端展示。
 *
 * <p>算法是**契约的一部分**：前端（P4）要用同一算法计算本人的匿名 ID，
 * 测试也依赖它断言事件里不出现 {@code user_id}。
 */
public final class AnonymousId {

    private static final String PREFIX = "anon-";
    private static final int HEX_CHARS = 8;

    private AnonymousId() {}

    /** {@code null} 入参返回 {@code null}：终局事件里的"无赢家"（无人出价/取消）就是这种情形。 */
    public static String of(String userId) {
        if (userId == null) {
            return null;
        }
        return PREFIX + sha256Hex(userId).substring(0, HEX_CHARS);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境已经不可信，
            // 不能降级成弱哈希——匿名标识一旦可枚举就失去了它唯一的作用。
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
