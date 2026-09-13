package com.bidarena.auction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.auction.persistence.AuctionRepository;
import com.bidarena.auction.persistence.SettlementRepository;
import com.bidarena.auction.domain.AuctionEvent;
import com.bidarena.auction.domain.AuctionEventPublisher;
import com.bidarena.auction.domain.AuctionEventType;
import com.bidarena.shared.AnonymousId;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.support.Fixtures;
import com.bidarena.support.TestDatabase;
import com.bidarena.wallet.persistence.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 命令路径的事件发布测试：**真事务、真数据库**，只把"发出去"这一步换成记录器。
 *
 * <h2>为什么把广播器换掉</h2>
 * 这里要证明的是"业务事务该在什么时候产生什么事件"，以及 A8 那条边界
 * （广播失败绝不能回滚已提交的事务）。用真 WebSocket 只会引入时间不确定性，
 * 却不增加任何证据强度——真握手那条路径由 {@code WsIntegrationTest} 覆盖。
 *
 * <h2>为什么每个用例都重新组一遍服务</h2>
 * 事件记录器是每次用例新造的，若复用 {@code Fixtures.services(ds)}，注入进去的会是
 * 生产广播器，记录不到东西。这里的装配刻意只替换<b>那一个</b>依赖，其余按生产接线，
 * 于是"事件与事务的顺序"这类结论仍然对生产成立。
 */
class EventPublishingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 记录每一次发布：扇出与单播分开存，才能断言"该单播的没有扇出"。 */
    private static final class RecordingPublisher implements AuctionEventPublisher {

        private final List<AuctionEvent> broadcast = new ArrayList<>();
        private final List<AuctionEvent> unicast = new ArrayList<>();
        private final List<String> unicastTargets = new ArrayList<>();

        @Override
        public void publish(AuctionEvent event) {
            broadcast.add(event);
        }

        @Override
        public void publishToUser(String userId, AuctionEvent event) {
            unicast.add(event);
            unicastTargets.add(userId);
        }

        List<AuctionEvent> ofType(AuctionEventType type) {
            List<AuctionEvent> found = new ArrayList<>();
            for (AuctionEvent event : broadcast) {
                if (event.type() == type) {
                    found.add(event);
                }
            }
            return found;
        }

        List<AuctionEvent> all() {
            List<AuctionEvent> all = new ArrayList<>(broadcast);
            all.addAll(unicast);
            return all;
        }
    }

    /** 只会抛异常的发布器：用来验证 A8（广播失败不影响已提交的事务）。 */
    private static final class ExplodingPublisher implements AuctionEventPublisher {

        @Override
        public void publish(AuctionEvent event) {
            throw new IllegalStateException("模拟广播通道故障");
        }

        @Override
        public void publishToUser(String userId, AuctionEvent event) {
            throw new IllegalStateException("模拟广播通道故障");
        }
    }

    private DataSource ds;
    private RecordingPublisher events;
    private AuctionRepository auctions;
    private WalletRepository wallets;
    private SettlementRepository settlements;

    @BeforeEach
    void setUp() {
        ds = TestDatabase.dataSource();
        TestDatabase.wipe();
        Fixtures.user(ds, "usr_a", 100_000);
        Fixtures.user(ds, "usr_b", 100_000);
        events = new RecordingPublisher();
        auctions = new AuctionRepository(ds);
        wallets = new WalletRepository(ds);
        settlements = new SettlementRepository(ds);
    }

    private BidService bids(AuctionEventPublisher publisher) {
        return new BidService(ds, auctions, wallets, publisher);
    }

    private SettlementService settlements(AuctionEventPublisher publisher) {
        return new SettlementService(ds, auctions, wallets, settlements, publisher);
    }

    private AuctionCommandService commands(AuctionEventPublisher publisher) {
        return new AuctionCommandService(ds, auctions, settlements(publisher), publisher);
    }

    /** 造一场正在进行的、已有两个参与者的拍卖，返回拍品 ID。 */
    private String runningAuction(int secondsLeft) {
        Fixtures.draftAuction(ds, "auc_t1", 100, 10, 600);
        Fixtures.startAuction(ds, "auc_t1", secondsLeft);
        Fixtures.join(ds, "auc_t1", "usr_a");
        Fixtures.join(ds, "auc_t1", "usr_b");
        return "auc_t1";
    }

    // ------------------------------------------------------------ 出价

    @Test
    @DisplayName("出价成功：BID_ACCEPTED 的 seq/价格/匿名领先者与库内状态一致")
    void bidAcceptedMatchesCommittedState() throws Exception {
        String auctionId = runningAuction(600);

        BidService.BidResult result = bids(events).placeBid(auctionId, "usr_a", 110, "req-1");

        assertTrue(result.accepted());
        List<AuctionEvent> accepted = events.ofType(AuctionEventType.BID_ACCEPTED);
        assertEquals(1, accepted.size());

        AuctionEvent event = accepted.get(0);
        assertEquals(auctionId, event.auctionId());
        assertEquals(Fixtures.seq(ds, auctionId), event.seq(), "事件版本号必须等于已提交的 seq");
        assertEquals(110L, event.payload().get("price"));
        assertEquals(AnonymousId.of("usr_a"), event.payload().get("leader"));
        // 匿名标识的确定性让"领先者是不是我"可以在客户端本地判断，无需再问服务端。
        assertFalse(JSON.writeValueAsString(event.payload()).contains("usr_a"));
    }

    @Test
    @DisplayName("一次出价同时触发延时：两条事件共享同一 seq（客户端按 seq+type 去重）")
    void extensionSharesSeqWithAccepted() {
        String auctionId = runningAuction(600);
        // 剩余时间落到延时窗口内：EXTENSION_WINDOW_SECONDS 是 5 秒。
        Fixtures.setEndsAtIn(ds, auctionId, (int) BidService.EXTENSION_WINDOW_SECONDS);

        BidService.BidResult result = bids(events).placeBid(auctionId, "usr_a", 110, "req-ext");

        assertTrue(result.extended(), "这次出价应当顺带延时");
        List<AuctionEvent> accepted = events.ofType(AuctionEventType.BID_ACCEPTED);
        List<AuctionEvent> extended = events.ofType(AuctionEventType.AUCTION_EXTENDED);
        assertEquals(1, accepted.size());
        assertEquals(1, extended.size());
        assertEquals(accepted.get(0).seq(), extended.get(0).seq(),
                "同一次提交只推进一个版本号，两条事件必须共享 seq");
        assertEquals(1L, Fixtures.seq(ds, auctionId), "库里的 seq 是 1，不是 2");
        assertEquals(1, Fixtures.extensionCount(ds, auctionId));
    }

    @Test
    @DisplayName("出价被拒：只单播给请求者，且不推进 seq")
    void rejectedBidIsUnicastAndDoesNotBumpSeq() {
        String auctionId = runningAuction(600);
        bids(events).placeBid(auctionId, "usr_a", 110, "req-1");
        long seqAfterFirstBid = Fixtures.seq(ds, auctionId);
        events.broadcast.clear();
        events.unicast.clear();

        // 低于"当前价 + 最小加价"，必被拒。
        try {
            bids(events).placeBid(auctionId, "usr_b", 115, "req-2");
            throw new AssertionError("这次出价应当被拒绝");
        } catch (BizException expected) {
            assertEquals(ErrorCode.BID_TOO_LOW, expected.code());
        }

        assertEquals(0, events.ofType(AuctionEventType.BID_ACCEPTED).size());
        assertEquals(0, events.broadcast.size(), "拒绝事件绝不能扇出给全场");
        assertEquals(1, events.unicast.size());
        AuctionEvent rejection = events.unicast.get(0);
        assertEquals(AuctionEventType.BID_REJECTED, rejection.type());
        assertEquals("usr_b", events.unicastTargets.get(0));
        assertEquals("BID_TOO_LOW", rejection.payload().get("code"));
        assertNotNull(rejection.payload().get("minimum"), "拒绝原因里的最低出价要能解释给用户看");
        assertEquals(seqAfterFirstBid, Fixtures.seq(ds, auctionId), "失败的出价不是状态变更，不该推进 seq");
        assertEquals(seqAfterFirstBid, rejection.seq(), "拒绝事件沿用当时的版本号");
    }

    @Test
    @DisplayName("幂等重放不再发事件：没有状态变更就没有广播")
    void replayPublishesNothing() {
        String auctionId = runningAuction(600);
        BidService.BidResult first = bids(events).placeBid(auctionId, "usr_a", 110, "req-same");
        assertFalse(first.idempotent());

        BidService.BidResult replay = bids(events).placeBid(auctionId, "usr_a", 110, "req-same");

        assertTrue(replay.idempotent());
        assertEquals(1, events.ofType(AuctionEventType.BID_ACCEPTED).size(),
                "重放是对同一结果的重复回答，不该再触发一次广播");
        assertEquals(0, events.unicast.size());
    }

    @Test
    @DisplayName("A8：广播通道整体故障时，出价仍然提交成功（事务已提交，不因广播回滚）")
    void broadcastFailureDoesNotRollbackCommittedBid() {
        String auctionId = runningAuction(600);

        // 发布器每次调用都抛异常。若业务代码没有把广播放在事务之外、或没有吞掉异常，
        // 这里会看到异常冒泡（甚至事务回滚）。
        BidService.BidResult result = bids(new ExplodingPublisher()).placeBid(auctionId, "usr_a", 110, "req-a8");

        assertTrue(result.accepted(), "广播故障不该影响出价结果");
        assertEquals("usr_a", Fixtures.leader(ds, auctionId), "出价必须真的落库");
        assertEquals(110L, Fixtures.currentPrice(ds, auctionId));
        assertEquals(1L, Fixtures.seq(ds, auctionId));
        // 资金也必须落账：这条路径上"回滚"最危险的后果是把钱退回去而价格留下了。
        assertEquals(110L, Fixtures.frozen(ds, "usr_a"));
        assertEquals(1, Fixtures.ledgerCount(ds, "usr_a", auctionId, "FREEZE"));
    }

    // ------------------------------------------------------------ 加入

    @Test
    @DisplayName("首次加入推进 seq 并广播 PARTICIPANT_JOINED；重复加入不再推进、不再广播")
    void firstJoinBumpsSeqOnlyOnce() {
        Fixtures.draftAuction(ds, "auc_j1", 100, 10, 600);
        Fixtures.startAuction(ds, "auc_j1", 600);
        AuctionCommandService commands = commands(events);

        commands.join("auc_j1", "usr_a");
        commands.join("auc_j1", "usr_a");

        assertEquals(1L, Fixtures.seq(ds, "auc_j1"), "重复加入不能推进版本号：否则任何人都能靠反复加入逼全场重同步");
        List<AuctionEvent> joined = events.ofType(AuctionEventType.PARTICIPANT_JOINED);
        assertEquals(1, joined.size());
        assertEquals(AnonymousId.of("usr_a"), joined.get(0).payload().get("participant"));
        assertEquals(1, joined.get(0).payload().get("participantCount"));
    }

    @Test
    @DisplayName("第二个参与者加入：人数递增，各自都是自己的匿名标识")
    void secondJoinBroadcastsUpdatedCount() {
        Fixtures.draftAuction(ds, "auc_j2", 100, 10, 600);
        Fixtures.startAuction(ds, "auc_j2", 600);
        AuctionCommandService commands = commands(events);

        commands.join("auc_j2", "usr_a");
        commands.join("auc_j2", "usr_b");

        List<AuctionEvent> joined = events.ofType(AuctionEventType.PARTICIPANT_JOINED);
        assertEquals(2, joined.size());
        assertEquals(1, joined.get(0).payload().get("participantCount"));
        assertEquals(2, joined.get(1).payload().get("participantCount"));
        assertEquals(2L, Fixtures.seq(ds, "auc_j2"), "两次加入 = 两次状态变更");
    }

    // ------------------------------------------------------------ 开拍 / 结算

    @Test
    @DisplayName("开拍广播完整快照（含 seq 与 serverTime），让首帧就是权威状态")
    void startBroadcastsSnapshot() {
        Fixtures.draftAuction(ds, "auc_s1", 100, 10, 600);

        commands(events).start("auc_s1");

        List<AuctionEvent> snapshots = events.ofType(AuctionEventType.AUCTION_SNAPSHOT);
        assertEquals(1, snapshots.size());
        AuctionEvent event = snapshots.get(0);
        assertEquals("RUNNING", event.payload().get("status"));
        assertEquals(Fixtures.seq(ds, "auc_s1"), event.seq());
        assertEquals(String.valueOf(event.seq()), String.valueOf(event.payload().get("seq")),
                "payload 里的 seq 与外层信封来自同一次读取，必须一致");
        assertNotNull(event.payload().get("endsAt"));
        assertEquals(0, event.payload().get("extensionCount"));
    }

    @Test
    @DisplayName("结算广播 AUCTION_FINISHED（赢家为匿名标识），重放不再广播")
    void settlementBroadcastsFinishedOnce() {
        String auctionId = runningAuction(600);
        bids(events).placeBid(auctionId, "usr_a", 110, "req-1");
        Fixtures.expireAuction(ds, auctionId);
        events.broadcast.clear();
        events.unicast.clear();

        SettlementService service = settlements(events);
        SettlementService.SettlementResult result = service.settleIfDue(auctionId);

        assertFalse(result.replay());
        List<AuctionEvent> finished = events.ofType(AuctionEventType.AUCTION_FINISHED);
        assertEquals(1, finished.size());
        assertEquals(AnonymousId.of("usr_a"), finished.get(0).payload().get("winner"));
        assertEquals(110L, finished.get(0).payload().get("finalPrice"));
        assertEquals("FINISHED", finished.get(0).payload().get("status"));
        assertEquals(Fixtures.seq(ds, auctionId), finished.get(0).seq(), "结算同样推进 seq");

        // 再调一次：状态已经是终态，属于重放。
        SettlementService.SettlementResult replay = service.settleIfDue(auctionId);
        assertTrue(replay.replay());
        assertEquals(1, events.ofType(AuctionEventType.AUCTION_FINISHED).size(),
                "重放不该重复广播'已结束'");
    }

    @Test
    @DisplayName("无人出价而结束：终局事件没有 winner 字段，而不是 winner 为 null")
    void finishedWithoutBidsOmitsWinner() {
        String auctionId = runningAuction(600);
        Fixtures.expireAuction(ds, auctionId);

        settlements(events).settleIfDue(auctionId);

        List<AuctionEvent> finished = events.ofType(AuctionEventType.AUCTION_FINISHED);
        assertEquals(1, finished.size());
        assertFalse(finished.get(0).payload().containsKey("winner"));
        assertEquals("NO_BIDS", finished.get(0).payload().get("reason"));
    }

    // ------------------------------------------------------------ 全流程隐私

    @Test
    @DisplayName("一条完整流程里的所有事件都不含原始 user_id（NS 只认匿名标识）")
    void noEventEverCarriesRawUserId() throws Exception {
        Fixtures.draftAuction(ds, "auc_p1", 100, 10, 600);
        AuctionCommandService commands = commands(events);
        commands.start("auc_p1");
        commands.join("auc_p1", "usr_a");
        commands.join("auc_p1", "usr_b");
        BidService bidService = bids(events);
        bidService.placeBid("auc_p1", "usr_a", 110, "req-1");
        try {
            bidService.placeBid("auc_p1", "usr_b", 115, "req-2");
        } catch (BizException expected) {
            // 拒绝也是一种事件来源，这里只关心它的内容。
        }
        Fixtures.expireAuction(ds, "auc_p1");
        settlements(events).settleIfDue("auc_p1");

        assertFalse(events.all().isEmpty(), "这条流程应当产生事件，否则本用例什么都没验证");
        for (AuctionEvent event : events.all()) {
            String frame = JSON.writeValueAsString(Map.of(
                    "type", event.type().name(),
                    "payload", event.payload()));
            for (String rawId : List.of("usr_a", "usr_b")) {
                assertFalse(frame.contains(rawId), event.type() + " 泄漏了原始 user_id：" + frame);
            }
        }
        // 反过来确认匿名标识确实出现了，否则上一条断言可能因为"什么都没发"而通过。
        assertTrue(JSON.writeValueAsString(events.all()).contains(AnonymousId.of("usr_a")));
    }
}
