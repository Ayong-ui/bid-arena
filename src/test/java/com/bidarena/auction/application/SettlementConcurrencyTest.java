package com.bidarena.auction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.bidarena.auction.application.SettlementService.SettlementResult;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.support.Fixtures;
import com.bidarena.support.Invariants;
import com.bidarena.support.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发结算：INV-4（唯一结算）的直接证据。
 *
 * <p>原文点名的四种触发方式——重复定时任务、重复请求、服务重启、两个实例同时触发——
 * 在这里都被还原成真实的并发调用：同一个 {@code auctionId} 被多个线程/多个扫描器
 * 同时要求结束。判定标准不是"有没有抛异常"，而是钱的最终状态：
 * 成交记录只能有一条、扣款流水只能有一条、余额只能少一次。
 *
 * <p>并发断言只针对与调度顺序无关的量。例如"哪个线程拿到了结算权"是不可断言的，
 * 而"总扣款次数"是。
 */
@DisplayName("并发结算与唯一结算")
class SettlementConcurrencyTest {

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
    }

    @Test
    @DisplayName("8 个线程同时要求结算同一场：1 次真正结算，7 次重放，只扣一次款")
    void manyThreadsSettlingTheSameAuctionProduceOneSettlement() throws Exception {
        String auctionId = "auc_c1";
        prepareExpiredAuction(auctionId, "u_1", 120, 1000);

        List<Outcome> outcomes = concurrently(8, i -> attemptSettle(auctionId));

        assertEquals(8, outcomes.size());
        outcomes.forEach(o -> assertNull(o.error(), "并发结算不应报错：" + o.message()));
        assertEquals(1L, outcomes.stream().filter(o -> o.result() != null && !o.result().replay()).count(),
                "只允许一个线程真正完成结算");
        assertEquals(7L, outcomes.stream().filter(o -> o.result() != null && o.result().replay()).count(),
                "其余线程必须走重放路径，而不是报错、也不是再次结算");

        assertEquals(1, Fixtures.settlementCount(ds, auctionId));
        assertEquals("u_1|120|TIMEOUT", Fixtures.settlement(ds, auctionId));
        assertEquals(1, Fixtures.ledgerCount(ds, "u_1", auctionId, "SETTLE"), "扣款流水只能有一条");
        assertEquals(880L, Fixtures.totalBalance(ds, "u_1"), "只扣一次：1000 - 120");
        assertEquals(0L, Fixtures.frozen(ds, "u_1"));
        Invariants.assertAllHolds(ds, auctionId);
    }

    @Test
    @DisplayName("连续多轮并发触发结算：每轮都不产生第二次扣款")
    void repeatedRoundsOfConcurrentSettlementDeductOnlyOnce() throws Exception {
        String auctionId = "auc_c2";
        prepareExpiredAuction(auctionId, "u_1", 150, 1000);

        AtomicLong freshSettlements = new AtomicLong();
        for (int round = 0; round < 3; round++) {
            int currentRound = round;
            List<Outcome> outcomes = concurrently(6, i -> attemptSettle(auctionId));
            freshSettlements.addAndGet(outcomes.stream()
                    .filter(o -> o.result() != null && !o.result().replay()).count());
            outcomes.forEach(o -> assertNull(o.error(), "第 " + currentRound + " 轮报错：" + o.message()));
        }

        assertEquals(1L, freshSettlements.get(), "三轮共 18 次触发，只有第一次算数");
        assertEquals(1, Fixtures.settlementCount(ds, auctionId));
        assertEquals(1, Fixtures.ledgerCount(ds, "u_1", auctionId, "SETTLE"));
        assertEquals(1, Fixtures.ledgerCount(ds, "u_1", auctionId, "FREEZE"),
                "三轮触发不得多记账：流水总数仍是冻结 1 条 + 扣款 1 条");
        assertEquals(850L, Fixtures.totalBalance(ds, "u_1"), "三轮触发不得累加扣款");
        Invariants.assertAllHolds(ds, auctionId);
    }

    @Test
    @DisplayName("两个扫描器同时跑：每场拍卖各结算一次，不重复扣款")
    void twoSchedulersRunningAtTheSameTimeSettleEachAuctionOnce() throws Exception {
        int auctions = 12;
        for (int i = 0; i < auctions; i++) {
            // 起始价 100、最小加价 10，所以首口价必须 ≥ 110。
            prepareExpiredAuction("auc_multi_" + i, "u_" + i, 110L + 10L * i, 1000);
        }
        Fixtures.exec(ds, "UPDATE auctions SET ends_at = DATE_ADD(NOW(6), INTERVAL -60 SECOND)");

        SettlementScheduler schedulerA = Fixtures.scheduler(ds, 50);
        SettlementScheduler schedulerB = Fixtures.scheduler(ds, 50);

        // 两个扫描器代表两个实例，同时开跑。
        concurrently(2, i -> {
            (i == 0 ? schedulerA : schedulerB).tick();
            return null;
        });

        assertEquals(auctions, schedulerA.settledAuctions() + schedulerB.settledAuctions(),
                "两实例合计只应结算 " + auctions + " 场（重放不计入）");

        for (int i = 0; i < auctions; i++) {
            String auctionId = "auc_multi_" + i;
            String userId = "u_" + i;
            long price = 110L + 10L * i;
            assertEquals(1, Fixtures.settlementCount(ds, auctionId), auctionId + " 成交记录必须唯一");
            assertEquals(1, Fixtures.ledgerCount(ds, userId, auctionId, "SETTLE"),
                    auctionId + " 扣款流水必须唯一");
            assertEquals(1000L - price, Fixtures.totalBalance(ds, userId), auctionId + " 只应扣一次");
            assertEquals("FINISHED", Fixtures.status(ds, auctionId));
            Invariants.assertAllHolds(ds, auctionId);
        }
    }

    @Test
    @DisplayName("结算与取消同时发生：两者只有一个生效，钱与成交结果必须一致")
    void simultaneousSettlementAndCancellationOnlyOneWins() throws Exception {
        String auctionId = "auc_c4";
        prepareExpiredAuction(auctionId, "u_1", 120, 1000);

        List<Outcome> outcomes = concurrently(2, i -> i == 0
                ? attemptSettle(auctionId)
                : attemptCancel(auctionId));

        // 胜者不可预测，但结果只能有两种，且必须自洽。
        assertEquals(1, Fixtures.settlementCount(ds, auctionId), "成交记录必须唯一");
        String status = Fixtures.status(ds, auctionId);
        String settlement = Fixtures.settlement(ds, auctionId);

        if ("FINISHED".equals(status)) {
            assertEquals("u_1|120|TIMEOUT", settlement);
            assertEquals(880L, Fixtures.totalBalance(ds, "u_1"), "成交则扣款");
        } else {
            assertEquals("CANCELLED", status, "若非成交，只能是取消");
            assertEquals("-|0|CANCELLED", settlement);
            assertEquals(1000L, Fixtures.totalBalance(ds, "u_1"), "取消则分文不扣");
        }

        // 败者要么走重放、要么被明确拒绝；两种都可以，但绝不能是"静默成功"。
        outcomes.stream().filter(o -> o.error() != null)
                .forEach(o -> assertEquals(ErrorCode.INVALID_STATE, o.error(),
                        "败者只应得到状态错误：" + o.message()));
        outcomes.stream().filter(o -> o.result() != null && !o.result().replay())
                .forEach(o -> assertEquals(settlement, render(o.result()), "真正生效的结果必须与库里一致"));

        Invariants.assertAllHolds(ds, auctionId);
    }

    @Test
    @DisplayName("取消也被并发重复触发时只释放一次，不产生负冻结")
    void concurrentCancellationReleasesOnlyOnce() throws Exception {
        String auctionId = "auc_c5";
        prepareExpiredAuction(auctionId, "u_1", 140, 1000);

        List<Outcome> outcomes = concurrently(6, i -> attemptCancel(auctionId));

        outcomes.forEach(o -> assertNull(o.error(), "重复取消不应报错：" + o.message()));
        assertEquals(1L, outcomes.stream().filter(o -> o.result() != null && !o.result().replay()).count());
        assertEquals(1, Fixtures.settlementCount(ds, auctionId));
        assertEquals("CANCELLED", Fixtures.status(ds, auctionId));
        assertEquals(1, Fixtures.ledgerCount(ds, "u_1", auctionId, "RELEASE"), "释放流水只能有一条");
        assertEquals(1000L, Fixtures.totalBalance(ds, "u_1"));
        assertEquals(0L, Fixtures.frozen(ds, "u_1"), "重复释放会把冻结压成负数，必须被挡住");
        Invariants.assertAllHolds(ds, auctionId);
    }

    // ---------------------------- 辅助 ----------------------------

    private record Outcome(SettlementResult result, ErrorCode error, String message) {}

    private static String render(SettlementResult result) {
        return (result.winnerId() == null ? "-" : result.winnerId()) + "|" + result.finalPrice()
                + "|" + result.reason();
    }

    /** 造一场"已到期、有人出价、尚未结算"的拍卖。 */
    private void prepareExpiredAuction(String auctionId, String userId, long amount, long balance) {
        Fixtures.user(ds, userId, balance);
        Fixtures.draftAuction(ds, auctionId, 100, 10, 600);
        Fixtures.startAuction(ds, auctionId, 600);
        Fixtures.join(ds, auctionId, userId);
        Fixtures.bidService(ds).placeBid(auctionId, userId, amount, "seed-" + auctionId);
        Fixtures.expireAuction(ds, auctionId);
    }

    private Outcome attemptSettle(String auctionId) {
        try {
            return new Outcome(settlementService.settleIfDue(auctionId), null, null);
        } catch (BizException e) {
            return new Outcome(null, e.code(), e.getMessage());
        }
    }

    private Outcome attemptCancel(String auctionId) {
        try {
            return new Outcome(settlementService.cancel(auctionId), null, null);
        } catch (BizException e) {
            return new Outcome(null, e.code(), e.getMessage());
        }
    }

    private <T> List<T> concurrently(int count, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CyclicBarrier barrier = new CyclicBarrier(count);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return task.apply(index);
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
