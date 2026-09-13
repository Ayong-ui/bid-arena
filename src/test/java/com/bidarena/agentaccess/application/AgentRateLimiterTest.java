package com.bidarena.agentaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 每 Token 固定窗口限流。
 *
 * <p>时间由入参给出，因此可以断言"第 60 秒整"这种边界，而不必真的等一分钟。
 */
@DisplayName("Agent 限流")
class AgentRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final AgentRateLimiter limiter = new AgentRateLimiter();

    @Test
    @DisplayName("窗口内放行 limit 次，第 limit+1 次被拒")
    void rejectsBeyondLimit() {
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("agt_1", 3, T0.plusMillis(i)), "第 " + (i + 1) + " 次应当放行");
        }

        assertFalse(limiter.tryAcquire("agt_1", 3, T0.plusMillis(3)), "第 4 次应当被拒");
        assertEquals(3, limiter.used("agt_1", T0.plusMillis(3)));
    }

    @Test
    @DisplayName("被拒的请求不占用配额：窗口结束后的额度不会被提前吃掉")
    void rejectedRequestsDoNotConsumeQuota() {
        assertTrue(limiter.tryAcquire("agt_1", 1, T0));
        assertFalse(limiter.tryAcquire("agt_1", 1, T0.plusSeconds(1)));

        // 窗口滚动之后重新开始，而不是"补扣"之前被拒的那次。
        assertTrue(limiter.tryAcquire("agt_1", 1, T0.plusSeconds(60)));
    }

    @Test
    @DisplayName("窗口边界：59.999 秒仍在窗内，60 秒整开始新窗口")
    void windowBoundary() {
        assertTrue(limiter.tryAcquire("agt_1", 1, T0));

        assertFalse(limiter.tryAcquire("agt_1", 1, T0.plusMillis(59_999)), "差 1 毫秒仍是同一个窗口");
        assertTrue(limiter.tryAcquire("agt_1", 1, T0.plusSeconds(60)), "满 60 秒进入新窗口");
    }

    @Test
    @DisplayName("配额按 key 独立：一个 Token 打满不影响另一个")
    void quotaIsPerKey() {
        assertTrue(limiter.tryAcquire("agt_1", 1, T0));
        assertFalse(limiter.tryAcquire("agt_1", 1, T0));

        assertTrue(limiter.tryAcquire("agt_2", 1, T0), "另一个 Token 应有自己的配额");
        assertEquals(1, limiter.used("agt_2", T0));
        assertEquals(1, limiter.used("agt_1", T0));
    }

    @Test
    @DisplayName("上限为 0 或负数视为不限流（签发侧已保证不会出现，这里是兜底）")
    void nonPositiveLimitMeansUnlimited() {
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire("agt_0", 0, T0));
            assertTrue(limiter.tryAcquire("agt_neg", -1, T0));
        }
    }

    @Test
    @DisplayName("过期窗口的计数不会被算进新窗口")
    void staleWindowCountsAsZero() {
        assertTrue(limiter.tryAcquire("agt_1", 5, T0));

        assertEquals(0, limiter.used("agt_1", T0.plusSeconds(61)), "窗口已过期，用量应视为 0");
    }
}
