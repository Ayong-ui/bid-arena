package com.bidarena.auction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.auction.application.BidService.BidResult;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.support.Fixtures;
import com.bidarena.support.Invariants;
import com.bidarena.support.TestDatabase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发出价：INV-2（领先者唯一）与 INV-3（请求幂等）的直接证据。
 *
 * <h2>为什么每个断言都配一次不变量校验</h2>
 * "只有一个人成功"这种断言很容易被满足——例如把所有出价都拒绝掉也满足。
 * 因此每个测试末尾都调 {@link Invariants#assertAllHolds}，把资金、出价链、幂等
 * 放在一起检查，避免用一个片面指标换来虚假的通过。
 *
 * <h2>并发是真的同时出发</h2>
 * 所有线程在 {@link CyclicBarrier} 上等到齐后才发请求，避免因为线程启动的先后
 * 使测试退化成"顺序执行"，那样测不到竞争。
 */
@DisplayName("并发出价与幂等")
class BidConcurrencyTest {

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
    }

    @Test
    @DisplayName("20 人同时出相同金额：只有一人成为领先者，其余得到 BID_TOO_LOW")
    void sameAmountOnlyOneBecomesLeader() throws Exception {
        int players = 20;
        String auctionId = "auc_same_amount";
        prepareAuction(auctionId, players, 600);

        List<Outcome> outcomes = concurrently(players, i ->
                attempt(auctionId, user(i), 110, "req-" + i));

        long accepted = outcomes.stream().filter(o -> o.result != null).count();
        long tooLow = outcomes.stream().filter(o -> o.error == ErrorCode.BID_TOO_LOW).count();

        assertEquals(1L, accepted, "同时出相同金额只能有一个人成功，实际成功 " + accepted + " 人");
        assertEquals(players - 1L, tooLow, "其余出价都应以「低于当前价加最小加价」被拒");
        assertEquals(1, Fixtures.count(ds, "SELECT COUNT(*) FROM bids WHERE auction_id = ?", auctionId));
        assertEquals(110L, Fixtures.currentPrice(ds, auctionId));
        assertEquals(110L, Fixtures.auctionFrozen(ds, auctionId), "本场冻结额应等于唯一领先者的出价");
        Invariants.assertAllHolds(ds, auctionId);
    }

    @Test
    @DisplayName("20 人并发出递增价格：无论谁先拿到锁，最终价都等于最大出价")
    void increasingLadderEndsAtHighestBid() throws Exception {
        int players = 20;
        String auctionId = "auc_ladder";
        prepareAuction(auctionId, players, 600);

        // 每人一个递增价格：110, 120, ..., 300
        List<Outcome> outcomes = concurrently(players, i ->
                attempt(auctionId, user(i), 110L + 10L * i, "req-" + i));

        long maxAmount = 110L + 10L * (players - 1);
        long accepted = outcomes.stream().filter(o -> o.result != null).count();
        long tooLow = outcomes.stream().filter(o -> o.error == ErrorCode.BID_TOO_LOW).count();

        // 刻意不断言接受了几条：谁先拿到拍卖行锁由线程调度决定，
        // 若 300 先执行，后面较低的价格就都应被拒。「接受条数」因此不是确定量。
        assertEquals(players, accepted + tooLow,
                "未成功的出价必须全部是「低于当前价」，出现其他原因就说明有别的缺陷");

        // 但结果本身是确定的：没有任何出价能超过 300，所以 300 无论何时执行都必然被接受，
        // 且此后无人能再抬价。这三条断言不依赖调度，因此可以严格成立。
        assertEquals(maxAmount, Fixtures.currentPrice(ds, auctionId), "最终价必须等于被投出的最大金额");
        assertEquals(user(players - 1), Fixtures.leader(ds, auctionId), "领先者应是出最高价的人");
        assertEquals(maxAmount, Fixtures.auctionFrozen(ds, auctionId),
                "本场冻结额必须等于最高价：说明每位旧领先者都被释放、新领先者被冻结");
        Invariants.assertAllHolds(ds, auctionId);
    }

    @Test
    @DisplayName("同一 requestId 并发提交 20 次：只生效一次，20 次返回同一价格与 seq")
    void sameRequestIdIsAppliedOnce() throws Exception {
        int attempts = 20;
        String auctionId = "auc_idempotent";
        prepareAuction(auctionId, 1, 600);
        String requestId = "dup-request-0001";

        List<Outcome> outcomes = concurrently(attempts, i ->
                attempt(auctionId, user(0), 110, requestId));

        assertEquals(attempts, outcomes.stream().filter(o -> o.result != null).count(),
                "重复提交不应报错，应返回首次结果");
        assertEquals(1L, outcomes.stream().filter(o -> o.result != null && !o.result.idempotent()).count(),
                "只有一次是真正生效，其余都必须被识别为重放");

        Set<String> signatures = new HashSet<>();
        for (Outcome o : outcomes) {
            signatures.add(o.result.price() + "/" + o.result.seq());
        }
        assertEquals(1, signatures.size(), "20 次返回的价格与 seq 必须完全一致，实际得到 " + signatures);

        assertEquals(1, Fixtures.count(ds, "SELECT COUNT(*) FROM bids WHERE auction_id = ?", auctionId),
                "同一 requestId 只能产生一条出价记录");
        assertEquals(1, Fixtures.ledgerCount(ds, user(0), auctionId, "FREEZE"),
                "同一 requestId 只能产生一条冻结流水");
        assertEquals(110L, Fixtures.frozen(ds, user(0)), "只应冻结一次");
        Invariants.assertAllHolds(ds, auctionId);
    }

    @Test
    @DisplayName("20 人各出价 3 轮共 60 次并发：领先者唯一且所有不变量成立")
    void repeatedConcurrentRounds() throws Exception {
        int players = 20;
        int rounds = 3;
        String auctionId = "auc_stress";
        prepareAuction(auctionId, players, 600);

        int total = players * rounds;
        List<Outcome> outcomes = concurrently(total, k -> {
            int player = k % players;
            int round = k / players;
            long amount = 110L + 10L * k;
            return attempt(auctionId, user(player), amount, "stress-" + k);
        });

        long accepted = outcomes.stream().filter(o -> o.result != null).count();
        assertTrue(accepted >= 1, "至少要有一次出价成功");
        assertEquals(accepted, (long) Fixtures.count(ds,
                "SELECT COUNT(*) FROM bids WHERE auction_id = ?", auctionId),
                "每一次成功的出价都必须留下恰好一条出价记录");
        assertEquals(Fixtures.currentPrice(ds, auctionId), Fixtures.auctionFrozen(ds, auctionId),
                "本场冻结额必须等于最终成交价");

        for (int i = 0; i < players; i++) {
            assertTrue(Fixtures.available(ds, user(i)) >= 0,
                    user(i) + " 的可用余额为负：" + Fixtures.available(ds, user(i)));
        }
        Invariants.assertAllHolds(ds, auctionId);
    }

    @Test
    @DisplayName("重放的结果可被识别：第二次提交带 idempotent 标记")
    void replayIsFlagged() {
        String auctionId = "auc_replay_flag";
        prepareAuction(auctionId, 1, 600);

        BidResult first = bidService.placeBid(auctionId, user(0), 110, "req-sequential-1");
        BidResult second = bidService.placeBid(auctionId, user(0), 110, "req-sequential-1");

        assertTrue(first.accepted());
        assertTrue(!first.idempotent(), "首次提交不应标记为重放");
        assertTrue(second.idempotent(), "重复提交必须标记为重放");
        assertEquals(first.price(), second.price(), "重放返回首次的价格");
        assertEquals(first.seq(), second.seq(), "重放返回首次的 seq");
        Invariants.assertAllHolds(ds, auctionId);
    }

    // ------------------------------------------------------------------

    private void prepareAuction(String auctionId, int players, int durationSeconds) {
        Fixtures.draftAuction(ds, auctionId, 100, 10, durationSeconds);
        Fixtures.startAuction(ds, auctionId, durationSeconds);
        for (int i = 0; i < players; i++) {
            Fixtures.user(ds, user(i), 1000);
            Fixtures.join(ds, auctionId, user(i));
        }
    }

    private static String user(int index) {
        return String.format("usr_%02d", index);
    }

    private record Outcome(BidResult result, ErrorCode error, String message) {}

    private Outcome attempt(String auctionId, String userId, long amount, String requestId) {
        try {
            BidResult result = bidService.placeBid(auctionId, userId, amount, requestId);
            assertNotNull(result);
            return new Outcome(result, null, null);
        } catch (BizException e) {
            return new Outcome(null, e.code(), e.getMessage());
        }
    }

    /** 让 {@code count} 个线程在栅栏上对齐后同时发起调用，把"启动先后"从测试里排除掉。 */
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
