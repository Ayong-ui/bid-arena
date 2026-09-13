package com.bidarena.auction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.auction.application.SettlementService.SettlementResult;
import com.bidarena.auction.domain.SettlementReason;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.support.Fixtures;
import com.bidarena.support.Invariants;
import com.bidarena.support.TestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结算的功能语义：赢家扣款、他人释放、无出价不扣款、取消、幂等重放。
 *
 * <p>竞争条件下的行为（重复触发、双实例、双扫描器）由 {@link SettlementConcurrencyTest} 覆盖。
 *
 * <p>几乎每个用例都以 {@link Invariants#assertAllHolds} 收尾。这不是凑数：
 * 结算会同时改动钱包、按场冻结、成交记录、流水四张表，"某一条断言成立"完全可以与
 * "钱是平的"同时为真，只有把不变量整套跑一遍才能确认没有留下半完成状态。
 */
@DisplayName("结算事务")
class SettlementServiceTest {

    private static final String AUCTION = "auc_settle";

    private static DataSource ds;
    private SettlementService settlementService;

    @BeforeAll
    static void initDatabase() {
        ds = TestDatabase.dataSource();
    }

    @BeforeEach
    void setUp() {
        TestDatabase.wipe();
        settlementService = Fixtures.settlementService(ds);
        Fixtures.draftAuction(ds, AUCTION, 100, 10, 600);
        Fixtures.startAuction(ds, AUCTION, 600);
    }

    // ---------------------------- 有赢家 ----------------------------

    @Test
    @DisplayName("到期有出价：赢家冻结转为实际扣款，其余出价者冻结全额释放")
    void expiredAuctionDeductsWinnerAndReleasesOthers() {
        givenBidders("u_1", "u_2", "u_3", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 110, "r1");
        Fixtures.bidService(ds).placeBid(AUCTION, "u_2", 150, "r2");

        assertNull(Fixtures.settlement(ds, AUCTION), "结算前不应有成交记录");
        Fixtures.expireAuction(ds, AUCTION);

        SettlementResult result = settlementService.settleIfDue(AUCTION);

        assertFalse(result.replay(), "首次结算不是重放");
        assertEquals("u_2", result.winnerId());
        assertEquals(150L, result.finalPrice());
        assertEquals(SettlementReason.TIMEOUT, result.reason());

        assertEquals("FINISHED", Fixtures.status(ds, AUCTION));
        assertEquals("u_2|150|TIMEOUT", Fixtures.settlement(ds, AUCTION));

        // 赢家：总额真的少了 150，不是只是冻结被清掉
        assertEquals(850L, Fixtures.totalBalance(ds, "u_2"));
        assertEquals(0L, Fixtures.frozen(ds, "u_2"));
        assertEquals(850L, Fixtures.available(ds, "u_2"));

        // 被超过的出价者与旁观者：一分不少
        for (String idle : new String[] {"u_1", "u_3"}) {
            assertEquals(1000L, Fixtures.totalBalance(ds, idle), idle + " 总额不应变化");
            assertEquals(0L, Fixtures.frozen(ds, idle), idle + " 冻结应已释放");
            assertEquals(1000L, Fixtures.available(ds, idle), idle + " 可用额应完全恢复");
        }

        // 流水：赢家一条 SETTLE，被超过者 FREEZE + RELEASE
        assertEquals(1, Fixtures.ledgerCount(ds, "u_2", AUCTION, "SETTLE"));
        assertEquals(150L, Fixtures.ledgerSum(ds, "u_2", AUCTION, "SETTLE"));
        assertEquals(1, Fixtures.ledgerCount(ds, "u_1", AUCTION, "FREEZE"));
        assertEquals(1, Fixtures.ledgerCount(ds, "u_1", AUCTION, "RELEASE"));
        assertEquals(0, Fixtures.ledgerCount(ds, "u_1", AUCTION, "SETTLE"));
        assertEquals(0, Fixtures.ledgerCount(ds, "u_3", AUCTION, "FREEZE"), "未出价者不应有任何流水");

        assertEquals(0L, Fixtures.auctionFrozen(ds, AUCTION), "结算后本场冻结必须归零");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("结算快照成交主体：谁的笑到最后，winner_type 就是谁")
    void settlementSnapshotsWinnerActorType() {
        Fixtures.user(ds, "u_agent", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_agent", 120, "r-agent", "AGENT");
        Fixtures.user(ds, "u_human", 1000);
        Fixtures.join(ds, AUCTION, "u_human");
        Fixtures.bidService(ds).placeBid(AUCTION, "u_human", 150, "r-human");

        Fixtures.expireAuction(ds, AUCTION);
        settlementService.settleIfDue(AUCTION);

        assertEquals("HUMAN", Fixtures.settlementWinnerType(ds, AUCTION), "赢家是真人，快照应为 HUMAN");
        assertEquals("HUMAN", Fixtures.ledgerActorType(ds, "u_human", AUCTION, "SETTLE"),
                "扣款流水应带赢家的主体标识");
        assertEquals("AGENT", Fixtures.ledgerActorType(ds, "u_agent", AUCTION, "RELEASE"),
                "被超过的 Agent 释放流水应保留它自己的主体标识");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("Agent 赢下拍卖：winner_type 与 SETTLE 流水均为 AGENT")
    void settlementRecordsAgentWinner() {
        Fixtures.user(ds, "u_agent", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_agent", 120, "r-agent", "AGENT");

        Fixtures.expireAuction(ds, AUCTION);
        settlementService.settleIfDue(AUCTION);

        assertEquals("AGENT", Fixtures.settlementWinnerType(ds, AUCTION),
                "成交主体必须能从结算记录读出，不能依赖会变的参与记录");
        assertEquals("AGENT", Fixtures.ledgerActorType(ds, "u_agent", AUCTION, "SETTLE"));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("结算后本场不再接受出价")
    void bidAfterSettlementIsRejected() {
        givenBidders("u_1", "u_2", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 110, "r1");
        Fixtures.expireAuction(ds, AUCTION);
        settlementService.settleIfDue(AUCTION);

        BizException e = assertThrows(BizException.class,
                () -> Fixtures.bidService(ds).placeBid(AUCTION, "u_2", 200, "r2"));

        assertEquals(ErrorCode.INVALID_STATE, e.code(), "已结束的拍卖只能报状态错，不能报价格错");
        assertEquals(110L, Fixtures.currentPrice(ds, AUCTION), "被拒的出价不得改动价格");
        assertEquals("u_1|110|TIMEOUT", Fixtures.settlement(ds, AUCTION), "成交结果不得被改写");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    // ---------------------------- 无赢家 ----------------------------

    @Test
    @DisplayName("到期无人出价：无赢家、不产生任何扣款")
    void auctionWithoutAnyBidEndsWithNoWinnerAndNoDeduction() {
        givenBidders("u_1", "u_2", 1000);
        Fixtures.expireAuction(ds, AUCTION);

        SettlementResult result = settlementService.settleIfDue(AUCTION);

        assertNull(result.winnerId());
        assertEquals(0L, result.finalPrice(), "没有成交就没有成交价");
        assertEquals(SettlementReason.NO_BIDS, result.reason());
        assertEquals("FINISHED", Fixtures.status(ds, AUCTION));
        assertEquals("-|0|NO_BIDS", Fixtures.settlement(ds, AUCTION));
        assertNull(Fixtures.settlementWinnerType(ds, AUCTION), "未成交不得有 winner_type");

        for (String user : new String[] {"u_1", "u_2"}) {
            assertEquals(1000L, Fixtures.totalBalance(ds, user), user + " 不得被扣款");
            assertEquals(0L, Fixtures.frozen(ds, user));
        }
        assertEquals(0, Fixtures.count(ds,
                "SELECT COUNT(*) FROM ledger_entries WHERE auction_id = ?", AUCTION), "无人出价不应产生流水");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("取消：冻结全部释放，不产生扣款，终态为 CANCELLED")
    void cancelReleasesAllFreezesAndProducesNoDeduction() {
        givenBidders("u_1", "u_2", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 120, "r1");
        Fixtures.bidService(ds).placeBid(AUCTION, "u_2", 140, "r2");

        SettlementResult result = settlementService.cancel(AUCTION);

        assertNull(result.winnerId(), "取消不产生赢家");
        assertEquals(SettlementReason.CANCELLED, result.reason());
        assertEquals("CANCELLED", Fixtures.status(ds, AUCTION), "取消的终态是 CANCELLED，不是 FINISHED");
        assertEquals("-|0|CANCELLED", Fixtures.settlement(ds, AUCTION));

        for (String user : new String[] {"u_1", "u_2"}) {
            assertEquals(1000L, Fixtures.totalBalance(ds, user), user + " 的钱一分都不能少");
            assertEquals(0L, Fixtures.frozen(ds, user));
            assertEquals(0, Fixtures.ledgerCount(ds, user, AUCTION, "SETTLE"), user + " 不得有扣款流水");
        }
        assertEquals(0, Fixtures.count(ds,
                "SELECT COUNT(*) FROM ledger_entries WHERE auction_id = ? AND entry_type = 'SETTLE'", AUCTION));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    // ---------------------------- 幂等与状态守卫 ----------------------------

    @Test
    @DisplayName("重复触发结算：只生成一条成交记录，只扣一次款")
    void repeatedSettlementReturnsTheSameResultWithoutMovingMoneyAgain() {
        givenBidders("u_1", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 120, "r1");
        Fixtures.expireAuction(ds, AUCTION);

        SettlementResult first = settlementService.settleIfDue(AUCTION);
        long balanceAfterFirst = Fixtures.totalBalance(ds, "u_1");

        for (int i = 0; i < 4; i++) {
            SettlementResult again = settlementService.settleIfDue(AUCTION);
            assertTrue(again.replay(), "第 " + (i + 2) + " 次调用必须走重放路径");
            assertEquals(first.winnerId(), again.winnerId());
            assertEquals(first.finalPrice(), again.finalPrice());
            assertEquals(first.reason(), again.reason());
        }

        assertEquals(1, Fixtures.settlementCount(ds, AUCTION), "成交记录只能有一条");
        assertEquals(1, Fixtures.ledgerCount(ds, "u_1", AUCTION, "SETTLE"), "扣款流水只能有一条");
        assertEquals(balanceAfterFirst, Fixtures.totalBalance(ds, "u_1"), "重复触发不得再次扣款");
        assertEquals(880L, balanceAfterFirst, "1000 - 120");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("未到期不得结算，且不留任何痕迹")
    void cannotSettleBeforeDeadline() {
        givenBidders("u_1", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 120, "r1");
        Fixtures.setEndsAtIn(ds, AUCTION, 600);

        BizException e = assertThrows(BizException.class, () -> settlementService.settleIfDue(AUCTION));

        assertEquals(ErrorCode.INVALID_STATE, e.code());
        assertEquals("RUNNING", Fixtures.status(ds, AUCTION));
        assertNull(Fixtures.settlement(ds, AUCTION), "失败的结算不得留下成交记录");
        assertEquals(120L, Fixtures.frozen(ds, "u_1"), "失败的结算不得动冻结");
        assertEquals(1000L, Fixtures.totalBalance(ds, "u_1"));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("已结算的拍卖不可取消")
    void cannotCancelAfterSettlement() {
        givenBidders("u_1", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 120, "r1");
        Fixtures.expireAuction(ds, AUCTION);
        settlementService.settleIfDue(AUCTION);

        BizException e = assertThrows(BizException.class, () -> settlementService.cancel(AUCTION));

        assertEquals(ErrorCode.INVALID_STATE, e.code());
        assertEquals("FINISHED", Fixtures.status(ds, AUCTION));
        assertEquals("u_1|120|TIMEOUT", Fixtures.settlement(ds, AUCTION), "取消不得改写既有成交结果");
        assertEquals(1, Fixtures.settlementCount(ds, AUCTION));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("已取消的拍卖不得结算")
    void cannotSettleCancelledAuction() {
        givenBidders("u_1", 1000);
        settlementService.cancel(AUCTION);

        BizException e = assertThrows(BizException.class, () -> settlementService.settleIfDue(AUCTION));

        assertEquals(ErrorCode.INVALID_STATE, e.code());
        assertEquals("-|0|CANCELLED", Fixtures.settlement(ds, AUCTION));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    // ---------------------------- 扫描 ----------------------------

    @Test
    @DisplayName("扫描只结算已到期的拍卖，不动未到期的")
    void settleDueSkipsAuctionsThatAreNotYetDue() {
        givenBidders("u_1", 1000);
        String future = "auc_future";
        Fixtures.draftAuction(ds, future, 100, 10, 600);
        Fixtures.startAuction(ds, future, 600);
        Fixtures.join(ds, future, "u_1");
        Fixtures.bidService(ds).placeBid(future, "u_1", 110, "rf");

        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 120, "r1");
        Fixtures.expireAuction(ds, AUCTION);

        SettlementScheduler scheduler = Fixtures.scheduler(ds, 50);
        scheduler.tick();

        assertEquals(1L, scheduler.settledAuctions(), "只应结算到期的 1 场");
        assertEquals("FINISHED", Fixtures.status(ds, AUCTION));
        assertEquals("RUNNING", Fixtures.status(ds, future), "未到期的拍卖不得被结算");
        assertNull(Fixtures.settlement(ds, future));
        assertEquals(110L, Fixtures.frozen(ds, "u_1"), "未到期那场的冻结必须原样保留");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("重启后继续结算：新扫描器无需任何内存状态即可补上已到期未结算的拍卖")
    void schedulerResumesExpiredButUnsettledAuctionsAfterRestart() {
        givenBidders("u_1", 1000);
        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 120, "r1");
        Fixtures.expireAuction(ds, AUCTION);

        // 模拟重启：上一轮扫描器早已消失，这里是一个全新的、从未跑过的实例。
        // 它能结算成功，说明"哪些拍卖待结算"完全来自数据库，而不是内存里的队列或标记。
        SettlementScheduler freshAfterRestart = Fixtures.scheduler(ds, 50);
        freshAfterRestart.tick();

        assertEquals(1L, freshAfterRestart.settledAuctions());
        assertEquals(1L, freshAfterRestart.executedTicks(), "一轮 tick 恰好记一次");
        assertEquals("FINISHED", Fixtures.status(ds, AUCTION));
        assertEquals("u_1|120|TIMEOUT", Fixtures.settlement(ds, AUCTION));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("单场结算失败不阻塞同一批里的其他场次")
    void oneFailingAuctionDoesNotBlockTheRestOfTheBatch() {
        givenBidders("u_1", "u_2", 1000);
        String broken = "auc_broken";
        Fixtures.draftAuction(ds, broken, 100, 10, 600);
        Fixtures.startAuction(ds, broken, 600);
        Fixtures.join(ds, broken, "u_1");
        Fixtures.bidService(ds).placeBid(broken, "u_1", 120, "rb");

        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 130, "r1");
        Fixtures.expireAuction(ds, AUCTION);

        // 人为破坏不变量：领先者的按场冻结被清零。结算必须**拒绝**这一场
        // （否则 u_1 拿走东西却不付钱），而不是把它当成无赢家蒙混过去。
        Fixtures.exec(ds, "UPDATE auction_participants SET frozen_amount = 0 WHERE auction_id = ? "
                + "AND user_id = 'u_1'", broken);
        // 让 broken 排在 AUCTION 之前，确保扫描先遇到它。
        Fixtures.exec(ds, "UPDATE auctions SET ends_at = DATE_ADD(NOW(6), INTERVAL -30 SECOND) WHERE id = ?",
                broken);

        SettlementScheduler scheduler = Fixtures.scheduler(ds, 50);
        scheduler.tick();

        assertEquals("RUNNING", Fixtures.status(ds, broken), "结算失败的场次应留在 RUNNING 等待重试");
        assertNull(Fixtures.settlement(ds, broken), "失败场次不得产生成交记录");
        assertEquals(0, Fixtures.ledgerCount(ds, "u_1", broken, "SETTLE"), "失败场次不得扣款");

        assertEquals("FINISHED", Fixtures.status(ds, AUCTION), "后面的场次必须照常结算");
        assertEquals("u_1|130|TIMEOUT", Fixtures.settlement(ds, AUCTION));
        assertEquals(870L, Fixtures.totalBalance(ds, "u_1"));

        // 注意：这里不能调用 Invariants.assertAllHolds。上面人为清零了 broken 场的按场冻结，
        // 该库本身已被破坏（钱包冻结 120 与按场冻结合计 0 不一致），全量不变量必然失败，
        // 而那不是被测代码的问题。因此只校验健康那场，并单独确认 broken 场确实没被动过。
        assertTrue(Invariants.auctionFrozenEqualsPrice(ds, AUCTION).isEmpty(),
                "健康场次的不变量应全部成立");
        assertTrue(Invariants.bidChainStrictlyIncreasing(ds, AUCTION).isEmpty());
        assertEquals(120L, Fixtures.frozen(ds, "u_1"), "失败场次的冻结必须原样保留，等修数据后重试");
    }

    // ---------------------------- 辅助 ----------------------------

    private void givenBidders(String... userIds) {
        givenBidders(userIds, 1000);
    }

    private void givenBidders(String[] userIds, long balance) {
        for (String userId : userIds) {
            Fixtures.user(ds, userId, balance);
            Fixtures.join(ds, AUCTION, userId);
        }
    }

    private void givenBidders(String a, String b, long balance) {
        givenBidders(new String[] {a, b}, balance);
    }

    private void givenBidders(String a, String b, String c, long balance) {
        givenBidders(new String[] {a, b, c}, balance);
    }

    private void givenBidders(String a, long balance) {
        givenBidders(new String[] {a}, balance);
    }
}
