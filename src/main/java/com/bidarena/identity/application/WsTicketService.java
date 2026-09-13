package com.bidarena.identity.application;

import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket 握手用的一次性短票。
 *
 * <h2>为什么需要它（而不是直接用 JWT）</h2>
 * 浏览器的 {@code WebSocket} 构造器**不能设置请求头**，令牌只能放进 URL；
 * 而 URL 会进入访问日志、浏览器历史、录屏，有效期还是 {@code JWT_TTL}（默认 8 小时）。
 * 换票之后：一次性、TTL 默认 60 秒、用后即废——泄露窗口与价值都被压到最小。
 * 完整的取舍见 {@code docs/REALTIME_AND_COMMAND_FLOW.md} §5。
 *
 * <h2>为什么存在内存里</h2>
 * ticket 的生命周期**短于一次页面操作**，不需要跨进程、跨重启存活：票丢了，客户端再签发一张即可
 * （代价是一次 HTTP 往返）。为此引入 Redis 或一张表，是为了一个 60 秒的对象付出长期的运维成本。
 *
 * <h2>为什么必须有容量上限</h2>
 * 一个只进不出的内存 Map 就是一条内存泄漏路径：任何已登录用户都能反复调用签发接口，
 * 每张票还挂着 TTL。这里在**签发时**顺手清理过期项，并在仍超过上限时拒绝新签发（429），
 * 使"内存占用"有硬上界（上限 × 单条大小），而不是等 OOM 才发现。
 *
 * <h2>为什么用 {@link Clock}</h2>
 * TTL 是这段代码的**全部**业务语义，靠 {@code Thread.sleep} 测它会让测试既慢又不稳。
 * 注入时钟后，"过期"可以在微秒内被确定性地验证。
 */
public final class WsTicketService {

    /** 与 {@code JWT_SECRET} 同一理由：足够长的随机串，用不着 JWT 的签名（票本身就是随机密文）。 */
    private static final int TOKEN_BYTES = 32;

    private final Duration ttl;
    private final int capacity;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> issued = new ConcurrentHashMap<>();

    private final AtomicLong issuedCount = new AtomicLong();
    private final AtomicLong redeemedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    /** 一张待核销的票：绑定的身份 + 过期时刻。 */
    private record Entry(Principal principal, Instant expiresAt) {}

    public record Ticket(String value, Instant expiresAt) {}

    /** 观测计数。票的内容**不**出现在这里，也不出现在日志里。 */
    public record Metrics(long issued, long redeemed, long rejected, int pending) {}

    public WsTicketService(Duration ttl, int capacity, Clock clock) {
        this.ttl = ttl;
        this.capacity = capacity;
        this.clock = clock;
    }

    /** 用系统时钟建一个（生产路径）。 */
    public static WsTicketService withSystemClock(Duration ttl, int capacity) {
        return new WsTicketService(ttl, capacity, Clock.systemUTC());
    }

    /**
     * 签发一张票。绑定**签发那一刻**的身份（用户 + 角色）。
     *
     * <p>不在核销时回查用户状态：那会引入一次数据库读，而收益是把"账号在 60 秒内被禁用"这个窗口
     * 从 60 秒压到 0——相比 JWT 的 8 小时窗口，它已经不是瓶颈（见 {@code DECISIONS.md} D-3）。
     */
    public Ticket issue(Principal principal) {
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未认证的调用者不能签发 WebSocket 票");
        }
        purgeExpired();
        if (issued.size() >= capacity) {
            rejectedCount.incrementAndGet();
            throw new BizException(ErrorCode.RATE_LIMITED, "WebSocket 票签发过于频繁，请稍后重试",
                    Map.of("capacity", capacity));
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = clock.instant().plus(ttl);
        issued.put(value, new Entry(principal, expiresAt));
        issuedCount.incrementAndGet();
        return new Ticket(value, expiresAt);
    }

    /**
     * 核销一张票：**成功一次之后它就不存在了**。
     *
     * <p>用 {@code remove} 而不是"先 get 再判断再 remove"：同一张票被两个连接同时提交时，
     * 后者必须拿不到身份。这个差异就是一次性语义的全部意义，不能靠"客户端不会这么干"。
     */
    public Optional<Principal> redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            rejectedCount.incrementAndGet();
            return Optional.empty();
        }
        Entry entry = issued.remove(ticket);
        if (entry == null) {
            // 已经用过 / 从未存在 / 已被并发核销：三种情况对外都是"这张票无效"，
            // 区分它们只会帮攻击者判断票是否曾经存在。
            rejectedCount.incrementAndGet();
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            rejectedCount.incrementAndGet();
            return Optional.empty();
        }
        redeemedCount.incrementAndGet();
        return Optional.of(entry.principal());
    }

    public Metrics metrics() {
        return new Metrics(issuedCount.get(), redeemedCount.get(), rejectedCount.get(), issued.size());
    }

    /** 清掉过期项。签发时顺手做一次，使清理频率与签发频率同阶，不需要后台线程。 */
    private void purgeExpired() {
        Instant now = clock.instant();
        issued.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
    }
}
