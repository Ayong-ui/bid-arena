package com.bidarena.auction.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.auction.application.AuctionEvents;
import com.bidarena.auction.domain.AuctionEvent;
import com.bidarena.auction.domain.AuctionEventType;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import com.bidarena.support.FakeSocket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 广播器单元测试：验证"发给谁、发多少次、失败了怎么办"。
 *
 * <p>这些都是**选择与计数**逻辑，与真实网络无关，因此用假连接测；真连接负责验证的是
 * 握手与首帧（见 {@code WsIntegrationTest}）。分开之后，这里的用例是确定的、毫秒级的。
 */
class WsEventBroadcasterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2024-05-01T00:00:00Z");

    private final WsEventBroadcaster broadcaster = new WsEventBroadcaster();

    private static AuctionEvent bidAccepted(String auctionId, long seq) {
        return AuctionEvents.bidAccepted(auctionId, seq, 120L, "usr_bidder_a", NOW.plusSeconds(600), 0, NOW);
    }

    private static AuctionEvent rejected(String auctionId) {
        return AuctionEvents.bidRejected(auctionId, 3L,
                new BizException(ErrorCode.BID_TOO_LOW, "出价低于当前最高价加最小加价",
                        Map.of("amount", 100L, "minimum", 120L)), NOW);
    }

    @Test
    @DisplayName("扇出只发给订阅了同一场拍卖的连接")
    void publishOnlyToSubscribersOfSameAuction() {
        FakeSocket a = new FakeSocket("s1", "ws://x/ws/auctions/auc_1");
        FakeSocket b = new FakeSocket("s2", "ws://x/ws/auctions/auc_2");
        broadcaster.subscribe("auc_1", "usr_bidder_a", a);
        broadcaster.subscribe("auc_2", "usr_bidder_b", b);

        broadcaster.publish(bidAccepted("auc_1", 3L));

        assertEquals(1, a.sent().size());
        assertEquals(0, b.sent().size(), "另一场的订阅者不该收到本场事件");
        assertTrue(a.sent().get(0).contains("\"BID_ACCEPTED\""), a.sent().get(0));
    }

    @Test
    @DisplayName("同一用户开两个标签页 = 两条连接，两个都收到（不能按人合并）")
    void twoTabsOfSameUserBothReceive() {
        FakeSocket tab1 = new FakeSocket("s1", "ws://x/1");
        FakeSocket tab2 = new FakeSocket("s2", "ws://x/2");
        broadcaster.subscribe("auc_1", "usr_bidder_a", tab1);
        broadcaster.subscribe("auc_1", "usr_bidder_a", tab2);

        broadcaster.publish(bidAccepted("auc_1", 3L));

        assertEquals(1, tab1.sent().size());
        assertEquals(1, tab2.sent().size());
        assertEquals(2, broadcaster.stats().connections());
    }

    @Test
    @DisplayName("单播只发给该用户自己的连接（'你出价被拒'不该被别人看到）")
    void publishToUserIsUnicast() {
        FakeSocket mine = new FakeSocket("s1", "ws://x/1");
        FakeSocket other = new FakeSocket("s2", "ws://x/2");
        broadcaster.subscribe("auc_1", "usr_bidder_a", mine);
        broadcaster.subscribe("auc_1", "usr_bidder_b", other);

        broadcaster.publishToUser("usr_bidder_a", rejected("auc_1"));

        assertEquals(1, mine.sent().size());
        assertEquals(0, other.sent().size());
        assertTrue(mine.sent().get(0).contains("BID_TOO_LOW"), mine.sent().get(0));
    }

    @Test
    @DisplayName("fail-closed：单播范围的事件被误拿去扇出时拒绝发送并计数")
    void refusesToBroadcastUnicastEvents() {
        FakeSocket socket = new FakeSocket("s1", "ws://x/1");
        broadcaster.subscribe("auc_1", "usr_bidder_a", socket);

        broadcaster.publish(rejected("auc_1"));

        assertEquals(0, socket.sent().size(), "定向事件绝不能出现在扇出路径上");
        assertEquals(1, broadcaster.stats().rejectedBroadcasts());
    }

    @Test
    @DisplayName("断开后不再收到任何事件（订阅表要能被摘干净）")
    void unsubscribeStopsDelivery() {
        FakeSocket socket = new FakeSocket("s1", "ws://x/1");
        broadcaster.subscribe("auc_1", "usr_bidder_a", socket);
        broadcaster.publish(bidAccepted("auc_1", 3L));
        assertEquals(1, socket.sent().size());

        broadcaster.unsubscribe(socket);
        broadcaster.publish(bidAccepted("auc_1", 4L));
        broadcaster.publishToUser("usr_bidder_a", rejected("auc_1"));

        assertEquals(1, socket.sent().size(), "断开之后不该再有任何投递");
        assertEquals(0, broadcaster.stats().connections());
    }

    @Test
    @DisplayName("重复 unsubscribe 不报错（onClose 与 onError 可能都走到）")
    void unsubscribeIsIdempotent() {
        FakeSocket socket = new FakeSocket("s1", "ws://x/1");
        broadcaster.subscribe("auc_1", "usr_bidder_a", socket);

        broadcaster.unsubscribe(socket);
        broadcaster.unsubscribe(socket);

        assertEquals(0, broadcaster.stats().connections());
    }

    @Test
    @DisplayName("一条连接的发送失败不影响同场其它连接，也不抛回调用方")
    void sendFailureIsIsolated() {
        FakeSocket broken = new FakeSocket("s1", "ws://x/1");
        FakeSocket healthy = new FakeSocket("s2", "ws://x/2");
        broadcaster.subscribe("auc_1", "usr_bidder_a", broken);
        broadcaster.subscribe("auc_1", "usr_bidder_b", healthy);
        broken.failNextSend(true);

        broadcaster.publish(bidAccepted("auc_1", 3L));

        assertEquals(1, healthy.sent().size(), "别人的失败不该影响这一条");
        assertEquals(0, broken.sent().size());
        assertTrue(broadcaster.stats().failed() >= 1);
        assertEquals(1, broadcaster.stats().connections(), "失败的连接应当被摘掉，不再重复失败");
    }

    @Test
    @DisplayName("异步失败（Future 异常完成）同样被计数——Solon 的实现就是这种形态")
    void asyncFailureIsCounted() {
        FakeSocket socket = new FakeSocket("s1", "ws://x/1");
        broadcaster.subscribe("auc_1", "usr_bidder_a", socket);
        socket.failNextSend(false);

        broadcaster.publish(bidAccepted("auc_1", 3L));

        assertEquals(1, broadcaster.stats().failed());
        assertEquals(1, broadcaster.stats().connections());
    }

    @Test
    @DisplayName("已失效的连接直接摘除，不尝试发送")
    void deadSocketIsDropped() {
        FakeSocket dead = new FakeSocket("s1", "ws://x/1");
        broadcaster.subscribe("auc_1", "usr_bidder_a", dead);
        dead.kill();

        broadcaster.publish(bidAccepted("auc_1", 3L));

        assertEquals(0, dead.sent().size());
        assertEquals(0, broadcaster.stats().connections());
    }

    @Test
    @DisplayName("没有订阅者时广播是空操作，不报错、不计数失败")
    void publishWithoutSubscribers() {
        broadcaster.publish(bidAccepted("auc_1", 3L));
        broadcaster.publishToUser("usr_ghost", rejected("auc_1"));

        assertEquals(0, broadcaster.stats().sent());
        assertEquals(0, broadcaster.stats().failed());
    }

    @Test
    @DisplayName("帧格式：type/auctionId/seq/serverTime 在顶层，payload 在其下；payload 不含原始 user_id")
    void frameShapeHidesRawUserId() throws Exception {
        FakeSocket socket = new FakeSocket("s1", "ws://x/1");
        broadcaster.subscribe("auc_1", "usr_bidder_a", socket);

        broadcaster.publish(bidAccepted("auc_1", 3L));

        String frame = socket.sent().get(0);
        JsonNode json = JSON.readTree(frame);
        assertEquals("BID_ACCEPTED", json.path("type").asText());
        assertEquals("auc_1", json.path("auctionId").asText());
        assertEquals(3L, json.path("seq").asLong());
        assertEquals("2024-05-01T00:00:00Z", json.path("serverTime").asText());
        assertEquals(120L, json.path("payload").path("price").asLong());
        assertEquals("anon-2952873c", json.path("payload").path("leader").asText(),
                "领先者只能是匿名标识");
        assertFalse(frame.contains("usr_bidder_a"), "事件里不能出现原始 user_id：" + frame);
    }

    @Test
    @DisplayName("缺席即 null：无人出价的终局事件里没有 winner 字段，而不是 winner:null")
    void nullFieldsAreOmitted() throws Exception {
        FakeSocket socket = new FakeSocket("s1", "ws://x/1");
        broadcaster.subscribe("auc_1", "usr_admin", socket);

        broadcaster.publish(AuctionEvents.auctionFinished("auc_1", 7L, null, 0L, "FINISHED", "NO_BIDS", NOW));

        JsonNode payload = JSON.readTree(socket.sent().get(0)).path("payload");
        assertFalse(payload.has("winner"), "无赢家时该字段应当缺席：" + socket.sent().get(0));
        assertEquals("NO_BIDS", payload.path("reason").asText());
    }

    @Test
    @DisplayName("观测计数：扇出与单播都计入 sent，范围拒绝单独计数")
    void stats() {
        FakeSocket socket = new FakeSocket("s1", "ws://x/1");
        broadcaster.subscribe("auc_1", "usr_bidder_a", socket);

        broadcaster.publish(bidAccepted("auc_1", 3L));
        broadcaster.publish(AuctionEvents.auctionExtended("auc_1", 3L, NOW, 1, NOW));
        broadcaster.publishToUser("usr_bidder_a", rejected("auc_1"));
        broadcaster.publish(rejected("auc_1"));

        WsEventBroadcaster.Stats stats = broadcaster.stats();
        assertEquals(3, stats.sent());
        assertEquals(1, stats.rejectedBroadcasts());
        assertEquals(1, stats.auctions());
        assertEquals(1, stats.connections());
    }

    @Test
    @DisplayName("事件类型与范围的对应关系是契约：只有参与者范围的事件可扇出")
    void scopeContract() {
        List<AuctionEventType> broadcastable = List.of(
                AuctionEventType.AUCTION_SNAPSHOT,
                AuctionEventType.PARTICIPANT_JOINED,
                AuctionEventType.BID_ACCEPTED,
                AuctionEventType.AUCTION_EXTENDED,
                AuctionEventType.AUCTION_FINISHED);
        for (AuctionEventType type : broadcastable) {
            assertTrue(type.broadcastable(), type + " 应当可扇出");
        }
        // 两个单播类型都不能出现在扇出路径上：前者是某个人的失败原因，
        // 后者是连接状态（握手里用 sendTo 直接发，不走 publish）。
        assertFalse(AuctionEventType.BID_REJECTED.broadcastable(), "BID_REJECTED 是定向事件");
        assertFalse(AuctionEventType.CONNECTION_STATE.broadcastable(), "CONNECTION_STATE 只发给本人");
    }
}
