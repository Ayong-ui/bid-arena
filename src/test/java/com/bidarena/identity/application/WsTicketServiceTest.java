package com.bidarena.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.identity.domain.Principal;
import com.bidarena.identity.domain.UserRole;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WebSocket 票的单元测试。
 *
 * <h2>为什么用可控时钟而不是 {@code Thread.sleep}</h2>
 * 这段代码的全部业务语义就是"有效期"，而用真实时间验证它意味着测试要睡 60 秒——
 * 或者把 TTL 调到毫秒级，于是测的就不再是生产配置。可控时钟让"过期"变成一行
 * {@code clock.advance(61)}，既确定又快。
 */
class WsTicketServiceTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    /** 可拨动的时钟：只实现 {@link Clock#instant()}，其余方法由默认实现兜底（调用即抛）。 */
    private static final class TickClock extends Clock {

        private Instant now = Instant.parse("2024-05-01T00:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private final TickClock clock = new TickClock();
    private final WsTicketService tickets = new WsTicketService(TTL, 10, clock);

    private static Principal user(String id, UserRole role) {
        return new Principal(id, role);
    }

    @Test
    @DisplayName("签发的票可核销一次，且带回过期时间而不是让调用方自己算")
    void issueAndRedeem() {
        WsTicketService.Ticket ticket = tickets.issue(user("usr_a", UserRole.BIDDER));

        assertFalse(ticket.value().isBlank());
        assertEquals(clock.instant().plus(TTL), ticket.expiresAt());

        Optional<Principal> redeemed = tickets.redeem(ticket.value());
        assertTrue(redeemed.isPresent());
        assertEquals("usr_a", redeemed.get().userId());
        assertEquals(UserRole.BIDDER, redeemed.get().role());
    }

    @Test
    @DisplayName("票是一次性的：第二次核销必须失败（浏览器重放 URL 不该能再连一次）")
    void singleUse() {
        WsTicketService.Ticket ticket = tickets.issue(user("usr_a", UserRole.BIDDER));

        assertTrue(tickets.redeem(ticket.value()).isPresent());
        assertTrue(tickets.redeem(ticket.value()).isEmpty(), "同一张票不该能核销两次");
        assertEquals(1, tickets.metrics().redeemed());
    }

    @Test
    @DisplayName("过期的票无效，即使从未被使用过")
    void expired() {
        WsTicketService.Ticket ticket = tickets.issue(user("usr_a", UserRole.BIDDER));

        clock.advance(TTL.plusSeconds(1));

        assertTrue(tickets.redeem(ticket.value()).isEmpty());
        assertEquals(0, tickets.metrics().redeemed(), "过期不是成功核销");
    }

    @Test
    @DisplayName("恰好到期时刻即失效（边界取'不到期才有效'，与截止时间判定同规则）")
    void expiresExactlyAtDeadline() {
        WsTicketService.Ticket ticket = tickets.issue(user("usr_a", UserRole.BIDDER));

        clock.advance(TTL);

        assertTrue(tickets.redeem(ticket.value()).isEmpty());
    }

    @Test
    @DisplayName("未知/空票一律无效，不区分'不存在'与'已用过'")
    void unknownTickets() {
        assertTrue(tickets.redeem("not-a-real-ticket").isEmpty());
        assertTrue(tickets.redeem(null).isEmpty());
        assertTrue(tickets.redeem("  ").isEmpty());
        assertEquals(0, tickets.metrics().redeemed());
        assertTrue(tickets.metrics().rejected() >= 3);
    }

    @Test
    @DisplayName("签发的票互不相同，且不包含用户标识")
    void ticketsAreOpqaueAndUnique() {
        String a = tickets.issue(user("usr_a", UserRole.BIDDER)).value();
        String b = tickets.issue(user("usr_a", UserRole.BIDDER)).value();

        assertNotEquals(a, b);
        for (String value : new String[] {a, b}) {
            assertFalse(value.contains("usr_a"), "票里不该出现用户标识：" + value);
            assertFalse(value.contains("="), "base64url 无填充，放进 URL 不需要转义：" + value);
        }
    }

    @Test
    @DisplayName("容量上限：超出的签发被拒绝（拒绝比无界增长好——内存占用要有硬上界）")
    void capacityIsBounded() {
        WsTicketService small = new WsTicketService(TTL, 2, clock);

        small.issue(user("usr_a", UserRole.BIDDER));
        small.issue(user("usr_b", UserRole.BIDDER));

        BizException rejected = assertThrows(BizException.class,
                () -> small.issue(user("usr_c", UserRole.BIDDER)));
        assertEquals(ErrorCode.RATE_LIMITED, rejected.code());
        assertEquals(2, small.metrics().pending());
    }

    @Test
    @DisplayName("签发时顺手清理过期票，容量不会被早已作废的票占满")
    void purgeExpiredOnIssue() {
        WsTicketService small = new WsTicketService(TTL, 2, clock);
        small.issue(user("usr_a", UserRole.BIDDER));
        small.issue(user("usr_b", UserRole.BIDDER));

        clock.advance(TTL.plusSeconds(5));

        // 两张旧票都已过期，签发新票应当成功（而不是撞容量上限）。
        WsTicketService.Ticket fresh = small.issue(user("usr_c", UserRole.BIDDER));
        assertEquals(1, small.metrics().pending());
        assertTrue(small.redeem(fresh.value()).isPresent());
    }

    @Test
    @DisplayName("没有身份的调用者不能签发（票必须绑定一个身份）")
    void issueRequiresPrincipal() {
        BizException failure = assertThrows(BizException.class, () -> tickets.issue(null));
        assertEquals(ErrorCode.UNAUTHENTICATED, failure.code());
    }

    @Test
    @DisplayName("票绑定签发时的角色：ADMIN 票核销出来还是 ADMIN")
    void roleIsCarried() {
        WsTicketService.Ticket ticket = tickets.issue(user("usr_admin", UserRole.ADMIN));
        assertEquals(UserRole.ADMIN, tickets.redeem(ticket.value()).orElseThrow().role());
    }
}
