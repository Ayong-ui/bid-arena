package com.bidarena.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.bidarena.support.ApiTestHarness;
import com.bidarena.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WebSocket 端到端测试：**真连接、真握手、真帧**。
 *
 * <h2>为什么这一层不能只用假连接测</h2>
 * 假连接证明不了：路由模板 {@code /ws/auctions/{auctionId}} 是否真的匹配、
 * 查询串里的 ticket 是否真的被解析出来、拒绝帧是否真的发出去了、首帧顺序是否如契约。
 * 这些都是"框架与代码的接缝"，只有真连一次才知道。
 *
 * <h2>为什么驱动状态变更走 HTTP 而不是直接调服务</h2>
 * 广播器活在**运行中服务的对象图**里（{@code Services}）。测试进程里新建一套服务
 * 去改库，事件只会发到一个没人订阅的广播器上，客户端永远等不到——那测出来的不是真的。
 * 因此这里的命令一律走 HTTP 接口，与前端的行为路径完全一致。
 */
class WsIntegrationTest extends ApiTestHarness {

    private static final long AWAIT_MILLIS = 5_000;

    /** 收帧的测试客户端：把帧存起来按类型等，避免用例去猜顺序与时间。 */
    private static final class Client extends WebSocketClient {

        private final List<JsonNode> frames = Collections.synchronizedList(new ArrayList<>());
        private final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        private volatile int closeCode = -1;

        Client(String url) throws Exception {
            super(new URI(url));
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            // 握手成功由帧内容体现，这里不需要做什么。
        }

        @Override
        public void onMessage(String message) {
            try {
                frames.add(JSON.readTree(message));
            } catch (Exception e) {
                throw new IllegalStateException("服务端发出了非法 JSON：" + message, e);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeCode = code;
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
            // 连接被拒（服务端主动关闭）时也会走到这里，用例通过帧与关闭事件判断，不在这里失败。
        }

        /** 等待某一类型的事件，忽略期间的其它帧。 */
        JsonNode awaitType(String type) {
            long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                for (JsonNode frame : snapshot()) {
                    if (type.equals(frame.path("type").asText())) {
                        return frame;
                    }
                }
                sleep();
            }
            fail("在 " + AWAIT_MILLIS + "ms 内没有收到 " + type + "，已收到：" + snapshot());
            return null;
        }

        /** 等待连接被服务端关闭。 */
        boolean awaitClosed() throws InterruptedException {
            return closed.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS);
        }

        List<JsonNode> snapshot() {
            synchronized (frames) {
                return new ArrayList<>(frames);
            }
        }

        List<JsonNode> framesOf(String type) {
            List<JsonNode> found = new ArrayList<>();
            for (JsonNode frame : snapshot()) {
                if (type.equals(frame.path("type").asText())) {
                    found.add(frame);
                }
            }
            return found;
        }

        int closeCode() {
            return closeCode;
        }

        private static void sleep() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private final List<Client> opened = new ArrayList<>();

    @AfterEach
    void closeClients() {
        for (Client client : opened) {
            try {
                client.closeBlocking();
            } catch (Exception ignored) {
                // 用例可能已经把它关掉了。
            }
        }
        opened.clear();
    }

    private Client connect(String url) throws Exception {
        Client client = new Client(url);
        assertTrue(client.connectBlocking(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "WebSocket 连接没有在 " + AWAIT_MILLIS + "ms 内建立：" + url);
        opened.add(client);
        return client;
    }

    /** 连接并断言握手成功（快照 + connected=true），返回客户端与快照帧。 */
    private Client connectAsParticipant(String auctionId, String token) throws Exception {
        WsTicket ticket = wsTicket(token);
        Client client = connect(ticket.url("localhost", auctionId));
        JsonNode snapshot = client.awaitType("AUCTION_SNAPSHOT");
        assertEquals(auctionId, snapshot.path("auctionId").asText());
        JsonNode state = client.awaitType("CONNECTION_STATE");
        assertTrue(state.path("payload").path("connected").asBoolean());
        return client;
    }

    // ------------------------------------------------------------ 握手

    @Test
    @DisplayName("参与者连上后：先收到权威快照，再收到 connected=true 的连接状态；两者 seq 一致")
    void handshakeSendsSnapshotThenConnectionState() throws Exception {
        String auctionId = createRunningAuction("WS 握手", 100, 10, 300);
        String token = bidderToken();
        join(auctionId, token);

        WsTicket ticket = wsTicket(token);
        Client client = connect(ticket.url("localhost", auctionId));

        JsonNode snapshot = client.awaitType("AUCTION_SNAPSHOT");
        JsonNode state = client.awaitType("CONNECTION_STATE");

        // 首帧必须是快照：客户端在拿到权威状态前无法解释任何增量事件。
        assertEquals("AUCTION_SNAPSHOT", client.snapshot().get(0).path("type").asText());
        assertEquals(auctionId, snapshot.path("payload").path("id").asText());
        assertEquals("RUNNING", snapshot.path("payload").path("status").asText());
        assertEquals(1, snapshot.path("payload").path("participantCount").asInt());
        assertEquals(snapshot.path("seq").asLong(), snapshot.path("payload").path("seq").asLong(),
                "payload 里的 seq 与外层信封必须一致");
        assertEquals(snapshot.path("seq").asLong(), state.path("payload").path("snapshotSeq").asLong());
        assertEquals(snapshot.path("seq").asLong(), state.path("seq").asLong(),
                "连接状态携带的是当前订阅版本，客户端据此对齐");
        assertNotNull(snapshot.path("serverTime").asText());
        assertFalse(snapshot.path("serverTime").asText().isEmpty());
    }

    @Test
    @DisplayName("事件里没有原始 user_id，只有确定性匿名标识（NS 不承载身份）")
    void framesHideRawUserId() throws Exception {
        String auctionId = createRunningAuction("WS 匿名", 100, 10, 300);
        String token = bidderToken();
        join(auctionId, token);
        Client client = connectAsParticipant(auctionId, token);

        placeBid(auctionId, token, "req-anon-1", 110);
        JsonNode accepted = client.awaitType("BID_ACCEPTED");

        String frame = accepted.toString();
        assertFalse(frame.contains(BIDDER_A_ID), "事件里出现了原始 user_id：" + frame);
        assertEquals("anon-2952873c", accepted.path("payload").path("leader").asText());
    }

    @Test
    @DisplayName("票是一次性的：同一张票第二次连接被拒（且带上可区分的原因码）")
    void ticketIsSingleUse() throws Exception {
        String auctionId = createRunningAuction("WS 单次票", 100, 10, 300);
        String token = bidderToken();
        join(auctionId, token);
        WsTicket ticket = wsTicket(token);

        Client first = connect(ticket.url("localhost", auctionId));
        first.awaitType("AUCTION_SNAPSHOT");

        Client second = connect(ticket.url("localhost", auctionId));
        JsonNode rejected = second.awaitType("CONNECTION_STATE");

        assertFalse(rejected.path("payload").path("connected").asBoolean());
        assertEquals("UNAUTHENTICATED", rejected.path("payload").path("code").asText());
        assertTrue(second.awaitClosed(), "被拒的连接应当被服务端关闭");
    }

    @Test
    @DisplayName("伪造/缺失的票被拒，且先收到失败帧再断开（否则客户端分不清'票过期'与'断网'）")
    void invalidOrMissingTicketIsRejected() throws Exception {
        String auctionId = createRunningAuction("WS 坏票", 100, 10, 300);

        Client forged = connect("ws://localhost:" + ticketPort() + "/ws/auctions/" + auctionId + "?ticket=forged");
        JsonNode forgedState = forged.awaitType("CONNECTION_STATE");
        assertFalse(forgedState.path("payload").path("connected").asBoolean());
        assertEquals("UNAUTHENTICATED", forgedState.path("payload").path("code").asText());
        assertTrue(forged.awaitClosed());

        Client anonymous = connect("ws://localhost:" + ticketPort() + "/ws/auctions/" + auctionId);
        JsonNode anonymousState = anonymous.awaitType("CONNECTION_STATE");
        assertFalse(anonymousState.path("payload").path("connected").asBoolean());
        assertEquals("UNAUTHENTICATED", anonymousState.path("payload").path("code").asText());
        assertNotNull(anonymousState.path("payload").path("message").asText());
    }

    @Test
    @DisplayName("未加入者不能订阅：拒绝原因是 NOT_JOINED 而不是 UNAUTHENTICATED（错因要分得清）")
    void nonParticipantIsRejected() throws Exception {
        String auctionId = createRunningAuction("WS 非参与者", 100, 10, 300);
        String token = bidderToken();

        // 先证明票是好的：同一时刻另一个已加入的账号能连上。
        join(auctionId, token);
        WsTicket goodTicket = wsTicket(token);
        Client participant = connect(goodTicket.url("localhost", auctionId));
        participant.awaitType("AUCTION_SNAPSHOT");

        WsTicket outsiderTicket = wsTicket(bidderTokenB());
        Client outsider = connect(outsiderTicket.url("localhost", auctionId));
        JsonNode rejected = outsider.awaitType("CONNECTION_STATE");

        assertFalse(rejected.path("payload").path("connected").asBoolean());
        assertEquals("NOT_JOINED", rejected.path("payload").path("code").asText());
        assertTrue(outsider.awaitClosed());
    }

    @Test
    @DisplayName("ADMIN 可以旁观任意一场拍卖而不必加入（管理员不需要是参与者）")
    void adminCanObserveWithoutJoining() throws Exception {
        String auctionId = createRunningAuction("WS 管理员旁观", 100, 10, 300);
        String token = bidderToken();
        join(auctionId, token);

        Client admin = connectAsParticipant(auctionId, adminToken());
        placeBid(auctionId, token, "req-admin-watch", 110);

        JsonNode accepted = admin.awaitType("BID_ACCEPTED");
        assertEquals(110L, accepted.path("payload").path("price").asLong());
    }

    @Test
    @DisplayName("不存在的拍卖被拒为 NOT_FOUND（不能报成'你不是参与者'——那是另一种错）")
    void unknownAuctionIsRejected() throws Exception {
        WsTicket ticket = wsTicket(adminToken());

        Client client = connect(ticket.url("localhost", "auc_does_not_exist"));
        JsonNode rejected = client.awaitType("CONNECTION_STATE");

        assertFalse(rejected.path("payload").path("connected").asBoolean());
        assertEquals("NOT_FOUND", rejected.path("payload").path("code").asText());
    }

    // ------------------------------------------------------------ 事件分发

    @Test
    @DisplayName("他人出价：所有参与者收到同一 seq 的 BID_ACCEPTED；一次提交只推进一个版本号")
    void bidIsBroadcastToAllParticipantsOnce() throws Exception {
        String auctionId = createRunningAuction("WS 广播出价", 100, 10, 300);
        String tokenA = bidderToken();
        String tokenB = bidderTokenB();
        join(auctionId, tokenA);
        join(auctionId, tokenB);

        Client a = connectAsParticipant(auctionId, tokenA);
        Client b = connectAsParticipant(auctionId, tokenB);
        long seqBefore = snapshot(auctionId, tokenA).dataLong("seq");

        assertEquals(200, placeBid(auctionId, tokenA, "req-bc-1", 110).code());

        JsonNode acceptedA = a.awaitType("BID_ACCEPTED");
        JsonNode acceptedB = b.awaitType("BID_ACCEPTED");
        assertEquals(acceptedA.path("seq").asLong(), acceptedB.path("seq").asLong());
        assertEquals(seqBefore + 1, acceptedA.path("seq").asLong(),
                "一次出价 = 一个版本号：不是每个参与者各一个，也不是出价 + 延时各一个");
        assertEquals(110L, acceptedB.path("payload").path("price").asLong());
        assertEquals(a.snapshot().size(), b.snapshot().size(),
                "同场参与者收到的事件条数应当相同（每个连接一份快照与一份连接状态）");
    }

    @Test
    @DisplayName("出价被拒只发给请求者本人：别人看不到你的失败原因（余额/最低价是隐私）")
    void rejectionIsUnicastOnly() throws Exception {
        String auctionId = createRunningAuction("WS 定向拒绝", 100, 10, 300);
        String tokenA = bidderToken();
        String tokenB = bidderTokenB();
        join(auctionId, tokenA);
        join(auctionId, tokenB);
        Client a = connectAsParticipant(auctionId, tokenA);
        Client b = connectAsParticipant(auctionId, tokenB);
        assertEquals(200, placeBid(auctionId, tokenA, "req-first", 110).code());
        a.awaitType("BID_ACCEPTED");
        b.awaitType("BID_ACCEPTED");

        Resp rejected = placeBid(auctionId, tokenB, "req-reject", 111);

        assertCode("BID_TOO_LOW", rejected);
        JsonNode rejection = b.awaitType("BID_REJECTED");
        assertEquals("BID_TOO_LOW", rejection.path("payload").path("code").asText());
        assertTrue(rejection.path("payload").has("minimum"), "要给出可解释的最低出价：" + rejection);

        // 给 A 一点时间：如果拒绝被误扇出，A 会在这段时间里收到。
        Thread.sleep(300);
        assertTrue(a.framesOf("BID_REJECTED").isEmpty(), "拒绝事件泄漏给了其他人：" + a.snapshot());
    }

    @Test
    @DisplayName("一次出价触发延时：BID_ACCEPTED 与 AUCTION_EXTENDED 共享同一 seq（客户端按 seq+type 去重）")
    void extensionSharesSeq() throws Exception {
        String auctionId = createRunningAuction("WS 延时", 100, 10, 300);
        String token = bidderToken();
        join(auctionId, token);
        Client client = connectAsParticipant(auctionId, token);
        // 把剩余时间压到延时窗口内，出价将顺带延时。
        Fixtures.setEndsAtIn(ds, auctionId, 3);

        assertEquals(200, placeBid(auctionId, token, "req-ext", 110).code());

        JsonNode accepted = client.awaitType("BID_ACCEPTED");
        JsonNode extended = client.awaitType("AUCTION_EXTENDED");
        assertEquals(accepted.path("seq").asLong(), extended.path("seq").asLong());
        assertEquals(1, extended.path("payload").path("extensionCount").asInt());
        assertTrue(extended.path("payload").path("endsAt").asText()
                .compareTo(accepted.path("payload").path("endsAt").asText()) >= 0);
    }

    @Test
    @DisplayName("订阅者的 seq 无缺口：加入、出价、取消各推进一次，收到的事件序列版本号连续")
    void seqIsGaplessForSubscriber() throws Exception {
        String auctionId = createRunningAuction("WS 缺口", 100, 10, 300);
        String tokenA = bidderToken();
        String tokenB = bidderTokenB();
        join(auctionId, tokenA);
        Client a = connectAsParticipant(auctionId, tokenA);
        long seqAtConnect = snapshot(auctionId, tokenA).dataLong("seq");

        join(auctionId, tokenB);
        placeBid(auctionId, tokenB, "req-gap-1", 110);
        assertEquals(200, call("POST", "/api/v1/admin/auctions/" + auctionId + "/cancel", adminToken(), null).code());

        a.awaitType("AUCTION_FINISHED");

        List<Long> observed = new ArrayList<>();
        observed.add(seqAtConnect);
        for (JsonNode frame : a.snapshot()) {
            observed.add(frame.path("seq").asLong());
        }
        // 从第二个版本号开始比对：相邻两帧的 seq 只能相等（一次提交的多条事件）或 +1。
        long previous = observed.get(0);
        for (int i = 1; i < observed.size(); i++) {
            long seq = observed.get(i);
            assertTrue(seq >= previous, "事件版本号不能倒退：" + observed);
            assertTrue(seq <= previous + 1, "版本号出现缺口（客户端会因此触发重同步）：" + observed);
            previous = seq;
        }
        assertEquals(snapshot(auctionId, tokenA).dataLong("seq"), previous,
                "最后收到的事件版本应当追上库里的当前版本（=1 加入 + 1 出价 + 1 取消）");
    }

    @Test
    @DisplayName("断线期间错过的事件由重连快照补齐：新快照的 seq 严格大于上一次的 seq")
    void reconnectSnapshotCatchesUp() throws Exception {
        String auctionId = createRunningAuction("WS 重连", 100, 10, 300);
        String token = bidderToken();
        join(auctionId, token);

        Client first = connectAsParticipant(auctionId, token);
        long seqBeforeDisconnect = first.awaitType("AUCTION_SNAPSHOT").path("seq").asLong();
        first.closeBlocking();

        // 断线期间发生两次状态变更（一次出价 + 它触发的延时共享 seq，这里用两次出价）。
        assertEquals(200, placeBid(auctionId, token, "req-recon-1", 110).code());
        assertEquals(200, placeBid(auctionId, token, "req-recon-2", 120).code());

        Client second = connectAsParticipant(auctionId, token);
        JsonNode resync = second.awaitType("AUCTION_SNAPSHOT");

        assertTrue(resync.path("seq").asLong() > seqBeforeDisconnect,
                "重连快照必须已经包含断线期间的变化：" + resync);
        assertEquals(snapshot(auctionId, token).dataLong("seq"), resync.path("seq").asLong());
        assertEquals(120L, resync.path("payload").path("currentPrice").asLong());
    }

    @Test
    @DisplayName("客户端发来的消息被忽略：不会产生任何命令，也不会断连（命令入口只有 HTTP）")
    void clientMessagesAreIgnored() throws Exception {
        String auctionId = createRunningAuction("WS 单向", 100, 10, 300);
        String token = bidderToken();
        join(auctionId, token);
        Client client = connectAsParticipant(auctionId, token);
        int framesBefore = client.snapshot().size();

        client.send("{\"type\":\"BID\",\"amount\":999}");
        client.send("{\"type\":\"RESYNC\",\"fromSeq\":0}");
        Thread.sleep(400);

        assertEquals(framesBefore, client.snapshot().size(), "服务端不该回复业务帧：" + client.snapshot());
        assertTrue(client.isOpen(), "忽略消息不等于断连");
        assertNull(Fixtures.leader(ds, auctionId), "客户端消息不能变成命令（没有出价生效）");
    }

    @Test
    @DisplayName("取消（终局）广播给全场，且赢家字段按匿名规则缺席")
    void cancelBroadcastsFinish() throws Exception {
        String auctionId = createRunningAuction("WS 取消", 100, 10, 300);
        String tokenA = bidderToken();
        String tokenB = bidderTokenB();
        join(auctionId, tokenA);
        join(auctionId, tokenB);
        Client a = connectAsParticipant(auctionId, tokenA);
        Client b = connectAsParticipant(auctionId, tokenB);
        placeBid(auctionId, tokenA, "req-cancel-1", 110);
        a.awaitType("BID_ACCEPTED");

        Resp cancelled = call("POST", "/api/v1/admin/auctions/" + auctionId + "/cancel", adminToken(), null);

        assertEquals(200, cancelled.code(), cancelled.raw());
        JsonNode finished = b.awaitType("AUCTION_FINISHED");
        assertEquals("CANCELLED", finished.path("payload").path("status").asText());
        assertEquals("CANCELLED", finished.path("payload").path("reason").asText());
        assertFalse(finished.path("payload").has("winner"), "取消没有赢家：" + finished);
    }

    /** 从票据里取出的连接端口（与 HTTP 端口不同）。 */
    private int ticketPort() {
        return wsTicket(adminToken()).wsPort();
    }
}
