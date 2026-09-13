package com.bidarena.auction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.auction.application.BidService.BidResult;
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
 * 出价事务的功能语义：冻结差额、领先者转移、规则拒绝、截止延时。
 *
 * <p>这些用例是顺序执行的，验证的是"单次出价算得对不对"；
 * 竞争条件下的行为由 {@link BidConcurrencyTest} 覆盖。
 */
@DisplayName("出价事务")
class BidServiceTest {

    private static final String AUCTION = "auc_fn";

    private static DataSource ds;
    private BidService bidService;

    @BeforeAll
    static void initDatabase() {
        ds = TestDatabase.dataSource();
    }

    @BeforeEach
    void setUp() {
        TestDatabase.wipe();
        bidService = Fixtures.bidService(ds);
        Fixtures.draftAuction(ds, AUCTION, 100, 10, 600);
        Fixtures.startAuction(ds, AUCTION, 600);
    }

    // ---------------------------- 资金 ----------------------------

    @Test
    @DisplayName("同一用户加价：只额外冻结差额，不是重新冻结全款")
    void rebidBySameUserFreezesOnlyTheDelta() {
        givenUser("u_a", 1000);
        givenJoined("u_a");

        bidService.placeBid(AUCTION, "u_a", 110, "r1");
        assertEquals(110L, Fixtures.frozen(ds, "u_a"));

        bidService.placeBid(AUCTION, "u_a", 130, "r2");

        assertEquals(130L, Fixtures.frozen(ds, "u_a"), "再次加价后冻结额应等于新出价");
        // 可用余额 = 1000 - 130。注意区分两个量：冻结总额跟上新出价，
        // 但本次真正动的钱只有差额 20，这就是「只冻差额」的含义。
        assertEquals(870L, Fixtures.available(ds, "u_a"), "可用余额应只减少差额 20");
        assertEquals(130L, Fixtures.ledgerSum(ds, "u_a", AUCTION, "FREEZE"),
                "冻结流水合计应等于最终冻结额：110 首次 + 20 差额");
        assertEquals(2, Fixtures.ledgerCount(ds, "u_a", AUCTION, "FREEZE"));
        assertEquals(1, Fixtures.count(ds,
                "SELECT COUNT(*) FROM ledger_entries WHERE user_id = 'u_a' "
                        + "AND auction_id = ? AND entry_type = 'FREEZE' AND amount = 20", AUCTION),
                "应存在一条金额为 20 的差额冻结流水");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("领先者转移：旧领先者被全额释放，新领先者被冻结")
    void previousLeaderIsReleasedOnTransfer() {
        givenUser("u_a", 1000);
        givenUser("u_b", 1000);
        givenJoined("u_a");
        givenJoined("u_b");

        bidService.placeBid(AUCTION, "u_a", 110, "r1");
        assertEquals(110L, Fixtures.frozen(ds, "u_a"));

        bidService.placeBid(AUCTION, "u_b", 120, "r2");

        assertEquals(0L, Fixtures.frozen(ds, "u_a"), "旧领先者的冻结额必须被完全释放");
        assertEquals(1000L, Fixtures.available(ds, "u_a"), "旧领先者的可用余额应复原");
        assertEquals(120L, Fixtures.frozen(ds, "u_b"));
        assertEquals(110L, Fixtures.ledgerSum(ds, "u_a", AUCTION, "RELEASE"));
        assertEquals(120L, Fixtures.auctionFrozen(ds, AUCTION));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("可用余额不足：按需新增的冻结额判断，而非按出价总额")
    void insufficientAvailableBalanceIsRejectedOnDeltaBasis() {
        givenUser("u_a", 200);
        givenJoined("u_a");

        bidService.placeBid(AUCTION, "u_a", 110, "r1");
        assertEquals(90L, Fixtures.available(ds, "u_a"));

        // 出价 210 的总额超过余额，但本次只需新增冻结 100，仍小于可用余额 90 —— 恰好越界
        BizException e = assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_a", 210, "r2"));
        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, e.code());
        assertTrue(String.valueOf(e.details()).contains("requiredDelta"),
                "拒绝信息应说明本次需要的增量，否则调用方无法判断差多少钱");

        assertEquals(110L, Fixtures.currentPrice(ds, AUCTION), "被拒绝的出价不得改变当前价");
        assertEquals(110L, Fixtures.frozen(ds, "u_a"), "被拒绝的出价不得改变冻结额");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    // ---------------------------- 规则拒绝 ----------------------------

    @Test
    @DisplayName("低于当前价加最小加价被拒，且不改变任何资金状态")
    void bidBelowMinimumIsRejected() {
        givenUser("u_a", 1000);
        givenUser("u_b", 1000);
        givenJoined("u_a");
        givenJoined("u_b");
        bidService.placeBid(AUCTION, "u_a", 110, "r1");

        BizException e = assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_b", 119, "r2"));
        assertEquals(ErrorCode.BID_TOO_LOW, e.code());

        assertEquals(110L, Fixtures.currentPrice(ds, AUCTION));
        assertEquals(0L, Fixtures.frozen(ds, "u_b"));
        assertEquals(110L, Fixtures.frozen(ds, "u_a"), "被拒绝的出价不得释放领先者的冻结");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("未加入拍卖间不能出价")
    void nonParticipantIsRejected() {
        givenUser("u_a", 1000);

        BizException e = assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_a", 110, "r1"));
        assertEquals(ErrorCode.NOT_JOINED, e.code());
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("拍卖非进行中不能出价")
    void nonRunningAuctionIsRejected() {
        givenUser("u_a", 1000);
        givenJoined("u_a");
        Fixtures.exec(ds, "UPDATE auctions SET status = 'DRAFT' WHERE id = ?", AUCTION);

        BizException e = assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_a", 110, "r1"));
        assertEquals(ErrorCode.INVALID_STATE, e.code());
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("已截止不能出价")
    void bidAfterDeadlineIsRejected() {
        givenUser("u_a", 1000);
        givenJoined("u_a");
        Fixtures.setEndsAtIn(ds, AUCTION, -1);

        BizException e = assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_a", 110, "r1"));
        assertEquals(ErrorCode.BID_LATE, e.code());
        assertEquals(0L, Fixtures.frozen(ds, "u_a"));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("金额非正数按参数错误处理")
    void nonPositiveAmountIsRejected() {
        givenUser("u_a", 1000);
        givenJoined("u_a");

        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_a", 0, "r1")).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_a", -5, "r2")).code());
    }

    // ---------------------------- 截止延时 ----------------------------

    @Test
    @DisplayName("截止前 5 秒内的合法出价延长 10 秒，每场最多 3 次")
    void bidWithinLastFiveSecondsExtendsDeadlineAtMostThreeTimes() {
        givenUser("u_a", 1000);
        givenJoined("u_a");

        for (int round = 1; round <= 3; round++) {
            Fixtures.setEndsAtIn(ds, AUCTION, 2);
            long amount = 100L + 10L * round;
            BidResult result = bidService.placeBid(AUCTION, "u_a", amount, "ext-" + round);

            assertEquals(round, result.extensions(), "第 " + round + " 次应触发第 " + round + " 次延时");
            assertEquals(round, Fixtures.extensionCount(ds, AUCTION));
            assertTrue(secondsUntilDeadline() >= 9,
                    "触发延时的出价应把截止时间推后 10 秒，实际剩余 " + secondsUntilDeadline());
        }

        // 第 4 次：已达上限，出价仍然成功，但不再延时
        Fixtures.setEndsAtIn(ds, AUCTION, 2);
        BidResult fourth = bidService.placeBid(AUCTION, "u_a", 150, "ext-4");

        assertTrue(fourth.accepted(), "达到延时上限后出价仍应成功");
        assertEquals(3, fourth.extensions(), "延时次数不得继续增长");
        assertEquals(3, Fixtures.extensionCount(ds, AUCTION));
        assertTrue(secondsUntilDeadline() <= 5,
                "第 4 次不得延时，截止时间应仍停留在 2 秒后，实际剩余 " + secondsUntilDeadline());
        Invariants.assertAllHolds(ds, AUCTION);
    }

    @Test
    @DisplayName("远离截止时间的出价不触发延时")
    void bidOutsideWindowDoesNotExtend() {
        givenUser("u_a", 1000);
        givenJoined("u_a");

        BidResult result = bidService.placeBid(AUCTION, "u_a", 110, "r1");

        assertEquals(0, result.extensions());
        assertEquals(0, Fixtures.extensionCount(ds, AUCTION));
        assertTrue(secondsUntilDeadline() >= 590, "截止时间不应被推后");
        Invariants.assertAllHolds(ds, AUCTION);
    }

    // ---------------------------- 拒绝的幂等 ----------------------------

    @Test
    @DisplayName("被拒的 requestId 重放时返回同一拒绝，不会在条件变化后变成成功")
    void rejectedRequestIdReplaysTheSameRejection() {
        givenUser("u_a", 1000);
        givenUser("u_b", 1000);
        givenJoined("u_a");
        givenJoined("u_b");
        bidService.placeBid(AUCTION, "u_a", 110, "r1");

        assertEquals(ErrorCode.BID_TOO_LOW, assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_b", 119, "rejected-once")).code());

        // 同一 requestId 提交一个**现在合法**的金额，仍应返回原来的拒绝：
        // 否则调用方在超时重试时会拿到与首次不同的结论，重试语义不可依赖。
        BizException replay = assertThrows(BizException.class,
                () -> bidService.placeBid(AUCTION, "u_b", 500, "rejected-once"));
        assertEquals(ErrorCode.BID_TOO_LOW, replay.code(),
                "同一 requestId 必须返回首次的结论，否则调用方的重试语义不可依赖");
        assertEquals(0L, Fixtures.frozen(ds, "u_b"), "重放不得产生任何资金变化");
        assertEquals(110L, Fixtures.currentPrice(ds, AUCTION));

        // 换新 requestId 后可以正常出价
        bidService.placeBid(AUCTION, "u_b", 500, "a-fresh-request");
        assertEquals(500L, Fixtures.currentPrice(ds, AUCTION));
        Invariants.assertAllHolds(ds, AUCTION);
    }

    // ------------------------------------------------------------------

    private void givenUser(String userId, long balance) {
        Fixtures.user(ds, userId, balance);
    }

    private void givenJoined(String userId) {
        Fixtures.join(ds, AUCTION, userId);
    }

    private long secondsUntilDeadline() {
        return Fixtures.scalar(ds, "SELECT TIMESTAMPDIFF(SECOND, NOW(6), ends_at) FROM auctions WHERE id = ?",
                AUCTION);
    }
}
