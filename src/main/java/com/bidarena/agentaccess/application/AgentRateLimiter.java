package com.bidarena.agentaccess.application;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 Token 的固定窗口限流。
 *
 * <h2>为什么是内存实现</h2>
 * 限流是"降低滥用影响"的手段，不是资金或赢家的事实来源。放进 MySQL 会让每一次 Agent
 * 请求都多一次写事务（还要处理它自己的锁），代价远大于收益；放进 Redis 会引入本部署
 * 并不需要的组件与"与 MySQL 不一致如何恢复"的新问题。内存实现的边界是明确的：
 * <b>多实例部署时每个实例各算一份上限</b>，实际放行量是 {@code 实例数 × limit}。
 * 这一点写在 {@code DESIGN.md}，不是隐藏假设。资金守恒不依赖限流，因此这个边界可接受。
 *
 * <h2>为什么窗口从"第一次请求"起算，而不是对齐自然分钟</h2>
 * 对齐自然分钟会让"一分钟内前 59 秒无请求、最后 1 秒打满"变成下一分钟继续打满，
 * 也会让测试在分钟边界上随机变红。从首次请求起算的滑动固定窗口没有这两种问题，
 * 代价是长期速率可能略高于名义上限（窗口滚动），这对"防滥用"这个目标无关紧要。
 */
public class AgentRateLimiter {

    static final long WINDOW_MILLIS = 60_000L;

    /** 触发清理的阈值：只在被异常多的 Token 刷爆时才做一次全量扫描。 */
    private static final int PRUNE_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 尝试消耗一次配额。
     *
     * @return true 表示放行；false 表示本窗口内已耗尽
     */
    public boolean tryAcquire(String key, int limitPerMinute, Instant now) {
        if (limitPerMinute <= 0) {
            // 未配置上限视为不限流（正常的签发路径不会产生这种值，见 ck_agent_token_rate_positive）。
            return true;
        }
        long nowMillis = now.toEpochMilli();
        boolean[] allowed = {true};

        // compute 在同一个 key 上是原子的：并发请求不会各自读到旧计数而重复放行。
        windows.compute(key, (k, current) -> {
            if (current == null || nowMillis - current.startMillis() >= WINDOW_MILLIS) {
                return new Window(nowMillis, 1);
            }
            if (current.count() >= limitPerMinute) {
                allowed[0] = false;
                return current;
            }
            return new Window(current.startMillis(), current.count() + 1);
        });

        if (windows.size() > PRUNE_THRESHOLD) {
            prune(nowMillis);
        }
        return allowed[0];
    }

    /** 当前窗口内已用配额（测试与诊断用）。 */
    public int used(String key, Instant now) {
        Window window = windows.get(key);
        if (window == null || now.toEpochMilli() - window.startMillis() >= WINDOW_MILLIS) {
            return 0;
        }
        return window.count();
    }

    private void prune(long nowMillis) {
        // 已过期的窗口没有任何语义，留着只会让 map 无限增长（被吊销的 Token 永远不再出现）。
        windows.entrySet().removeIf(e -> nowMillis - e.getValue().startMillis() >= WINDOW_MILLIS);
    }

    private record Window(long startMillis, int count) {}
}
