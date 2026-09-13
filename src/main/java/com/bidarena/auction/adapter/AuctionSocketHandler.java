package com.bidarena.auction.adapter;

import com.bidarena.auction.application.AuctionEvents;
import com.bidarena.auction.application.AuctionQueryService;
import com.bidarena.auction.application.AuctionViews;
import com.bidarena.identity.application.WsTicketService;
import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /ws/auctions/{auctionId}} 的入站适配器：握手鉴权、首帧快照、订阅登记。
 *
 * <h2>它不做业务</h2>
 * 没有出价、没有状态判断，甚至**不解析**客户端发来的消息（见 {@link #onMessage}）：
 * WS 只承载服务端到客户端的通知，命令入口只有 HTTP / Agent API 一处。
 * 多一条命令通道就多一套 RBAC、幂等与审计要维护，而收益是零。
 *
 * <h2>为什么拒绝也要"先说一句"</h2>
 * 握手失败时先发一帧 {@code CONNECTION_STATE(connected=false, code=...)} 再关闭：
 * 浏览器无法从"连接被关闭"里读出原因（WebSocket 没有 HTTP 那样的响应体），
 * 没有这一帧，客户端就分不清"票过期了"和"网络断了"——前者应该自动重新取票，后者应该退避重连。
 *
 * <h2>参数从哪来</h2>
 * {@code auctionId} 来自路由变量 {@code {auctionId}}，{@code ticket} 来自查询串；
 * 二者在 Solon 的 WS 实现里都被塞进同一张 {@code paramMap}（握手时解析查询串，路由匹配时写入路径变量）。
 * ticket 是 base64url（无 {@code =} 填充、只用 {@code -_}），因此不需要 URL 解码。
 */
public class AuctionSocketHandler implements WebSocketListener {

    private static final Logger log = LoggerFactory.getLogger(AuctionSocketHandler.class);

    private final WsTicketService tickets;
    private final AuctionQueryService auctions;
    private final WsEventBroadcaster broadcaster;

    public AuctionSocketHandler(
            WsTicketService tickets, AuctionQueryService auctions, WsEventBroadcaster broadcaster) {
        this.tickets = tickets;
        this.auctions = auctions;
        this.broadcaster = broadcaster;
    }

    /** 注册到 {@code WebSocketRouter} 的路径模板。 */
    public static final String PATH = "/ws/auctions/{auctionId}";

    @Override
    public void onOpen(WebSocket socket) {
        String auctionId = socket.param("auctionId");
        Optional<Principal> principal = tickets.redeem(socket.param("ticket"));
        if (principal.isEmpty()) {
            reject(socket, auctionId, ErrorCode.UNAUTHENTICATED, "WebSocket 票无效或已过期");
            return;
        }
        Principal me = principal.get();

        AuctionViews.Snapshot snapshot;
        try {
            // 先确认拍卖存在（不存在 → NOT_FOUND），再判断订阅资格。
            // 顺序反过来的话，"不存在的拍卖"会被报成"你不是参与者"，把两类错误混成一种。
            snapshot = auctions.snapshot(auctionId);
            if (!me.isAdmin() && !auctions.isParticipant(auctionId, me.userId())) {
                reject(socket, auctionId, ErrorCode.NOT_JOINED, "尚未加入该拍卖间，无法订阅其事件");
                return;
            }
        } catch (BizException e) {
            reject(socket, auctionId, e.code(), e.getMessage());
            return;
        }

        // 先订阅再发快照：反过来的话，"读快照"与"订阅"之间提交的事件会被永久错过——
        // 客户端只能等到下一次版本变化才发现缺口。代价是并发窗口里可能先收到事件后收到快照，
        // 契约 §6.3 已经规定客户端在拿到快照前暂停合并、并丢弃 seq <= snapshot.seq 的缓存事件。
        broadcaster.subscribe(auctionId, me.userId(), socket);
        broadcaster.sendTo(socket, AuctionEvents.snapshot(snapshot));
        broadcaster.sendTo(socket, AuctionEvents.connectionState(
                auctionId, snapshot.seq(), true, snapshot.seq(), null, null, Instant.parse(snapshot.serverTime())));
        log.info("WS 订阅建立 auction={} user={} seq={}", auctionId, me.userId(), snapshot.seq());
    }

    /**
     * 客户端消息一律忽略。
     *
     * <p>不解析、不执行、不回复业务语义：协议层的 ping/pong 由 WS 实现自己处理，
     * 不属于业务消息。重新同步走 HTTP 快照接口（契约 §6），因此这里没有"客户端说漏了哪些事件"的入口，
     * 也就没有伪造缺口、拉取他人快照的可能。
     */
    @Override
    public void onMessage(WebSocket socket, String message) {
        log.debug("忽略客户端发来的 WS 消息（WS 是单向通知通道）length={}", message == null ? 0 : message.length());
    }

    @Override
    public void onMessage(WebSocket socket, java.nio.ByteBuffer message) {
        log.debug("忽略客户端发来的 WS 二进制帧（WS 是单向通知通道）");
    }

    @Override
    public void onClose(WebSocket socket) {
        broadcaster.unsubscribe(socket);
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        // 出错就当作断开处理：留着死连接会让每次广播都白跑一次，还会让 stats 的连接数虚高。
        broadcaster.unsubscribe(socket);
        log.warn("WS 连接异常：{}", error == null ? "unknown" : error.getMessage());
    }

    private void reject(WebSocket socket, String auctionId, ErrorCode code, String message) {
        log.info("拒绝 WS 订阅 auction={} code={} reason={}", auctionId, code, message);
        // 失败帧的 serverTime 用本机时钟：此刻还没有读到任何数据库状态，
        // 而该字段不参与任何时间判定（D-5 约束的是业务截止时间）。
        broadcaster.sendTo(socket, AuctionEvents.connectionState(
                auctionId, 0L, false, null, code.name(), message, Instant.now()));
        socket.close();
    }
}
