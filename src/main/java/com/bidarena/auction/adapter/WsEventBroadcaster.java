package com.bidarena.auction.adapter;

import com.bidarena.auction.domain.AuctionEvent;
import com.bidarena.auction.domain.AuctionEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.noear.solon.net.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 广播适配器：把领域事件送到"连在这一场上的连接"。
 *
 * <h2>它只做三件事，且都不许失败外溢</h2>
 * 订阅登记、按范围选收件人、发送并计数。
 * 发送失败**绝不能**抛回调用方：事件是在事务提交之后发布的，
 * 一次失败的推送不能把一次已经成功的出价变成失败（契约 §7）。
 * 因此这里每个出口都吞异常，只留下计数与 WARN 日志。
 *
 * <h2>为什么按拍卖和按用户各存一张表</h2>
 * 扇出（参与者）与单播（本人）是两种投递范围，各有一张表就不必"遍历所有连接再逐个判断"。
 * 第三张表按连接索引订阅关系，使断开时能 O(1) 摘除，而不是扫描两张表。
 *
 * <h2>为什么用 {@code WebSocket} 对象本身当键</h2>
 * 它的实现类没有覆写 {@code equals}/{@code hashCode}，因此键比较就是连接身份比较——
 * 不会被"两个连接恰好有相同的业务属性"混淆（若用 {@code auctionId+userId} 当键，
 * 同一用户开两个标签页就会互相顶掉）。
 */
public class WsEventBroadcaster implements AuctionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WsEventBroadcaster.class);

    /** 这一份 JSON 是我们自己的 wire format，与框架的 HTTP 序列化器无关，因此自建一个。 */
    private final ObjectMapper json = new ObjectMapper();

    private final Map<String, Set<WebSocket>> byAuction = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocket>> byUser = new ConcurrentHashMap<>();
    private final Map<WebSocket, Subscription> bySocket = new ConcurrentHashMap<>();

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    private record Subscription(String auctionId, String userId) {}

    /**
     * 观测数据（见 {@code docs/REALTIME_AND_COMMAND_FLOW.md} §8）。测试直接读它做断言。
     *
     * <p>{@code sent} 是“已交给连接去发”的次数（其中一部分会在随后的异步失败里再计入
     * {@code failed}），因此不保证 {@code sent = 送达数}：一个不报错的实时通道
     * 只需要看 {@code failed} 是否为零与 {@code rejectedBroadcasts} 是否为零。
     */
    public record Stats(long sent, long failed, long rejectedBroadcasts, int auctions, int connections) {}

    public void subscribe(String auctionId, String userId, WebSocket socket) {
        bySocket.put(socket, new Subscription(auctionId, userId));
        byAuction.computeIfAbsent(auctionId, key -> ConcurrentHashMap.newKeySet()).add(socket);
        if (userId != null) {
            byUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(socket);
        }
    }

    public void unsubscribe(WebSocket socket) {
        Subscription subscription = bySocket.remove(socket);
        if (subscription == null) {
            return;
        }
        removeFrom(byAuction, subscription.auctionId(), socket);
        removeFrom(byUser, subscription.userId(), socket);
    }

    @Override
    public void publish(AuctionEvent event) {
        if (!event.type().broadcastable()) {
            // fail-closed：单播类事件（例如"你出价被拒"）绝不允许扇出。
            // 这里不抛异常（发布方在事务提交之后），但必须留下痕迹，否则这类编程错误会静默消失。
            rejected.incrementAndGet();
            log.warn("拒绝扇出非参与者范围的事件 type={} auction={} scope={}",
                    event.type(), event.auctionId(), event.type().scope());
            return;
        }
        fanOut(byAuction.get(event.auctionId()), event, "auction=" + event.auctionId());
    }

    @Override
    public void publishToUser(String userId, AuctionEvent event) {
        fanOut(byUser.get(userId), event, "user=" + userId);
    }

    /** 单播给一条具体连接。用于握手阶段的两帧（此时连接还没进任何订阅表）。 */
    public void sendTo(WebSocket socket, AuctionEvent event) {
        send(socket, event, "handshake");
    }

    public Stats stats() {
        return new Stats(sent.get(), failed.get(), rejected.get(), byAuction.size(), bySocket.size());
    }

    private void fanOut(Set<WebSocket> targets, AuctionEvent event, String target) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        // 复制一份再遍历：发送过程中连接可能断开并触发 unsubscribe，
        // 直接遍历"活"集合会把"结构被修改"变成一次偶发的运行时异常。
        for (WebSocket socket : Set.copyOf(targets)) {
            send(socket, event, target);
        }
    }

    private void send(WebSocket socket, AuctionEvent event, String target) {
        String frame;
        try {
            frame = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 序列化失败是**我们自己的**缺陷（payload 里塞了不可序列化的对象），
            // 不能重试也没有客户端可补救，因此记 ERROR 让人看见。
            failed.incrementAndGet();
            log.error("事件序列化失败 type={} auction={}: {}", event.type(), event.auctionId(), e.getMessage());
            return;
        }
        if (!socket.isValid()) {
            failed.incrementAndGet();
            unsubscribe(socket);
            return;
        }
        try {
            Future<Void> future = socket.send(frame);
            sent.incrementAndGet();
            // Solon 的 WS 实现把底层发送异常装进返回的 Future（而不是同步抛出），
            // 因此不看这个 Future 就等于把“发失败了”静默掉。实现返回的其实是
            // 已完成（或已异常完成）的 CompletableFuture，回调会立刻在本线程执行。
            if (future instanceof CompletableFuture<?> completed) {
                completed.whenComplete((ignored, error) -> {
                    if (error != null) {
                        failed.incrementAndGet();
                        log.warn("事件发送失败（异步）type={} auction={} {}: {}",
                                event.type(), event.auctionId(), target, error.getMessage());
                    }
                });
            }
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.warn("事件发送失败 type={} auction={} {}: {}",
                    event.type(), event.auctionId(), target, e.getMessage());
            // 发送即失败的连接通常已经死了（对端关闭、缓冲区错误），顺手摘掉，
            // 免得它一直留在订阅表里，让后续每次广播都白跑一次。
            unsubscribe(socket);
        }
    }

    private static void removeFrom(Map<String, Set<WebSocket>> table, String key, WebSocket socket) {
        if (key == null) {
            return;
        }
        Set<WebSocket> set = table.get(key);
        if (set == null) {
            return;
        }
        set.remove(socket);
        if (set.isEmpty()) {
            // 只删自己看到的那个空集合：并发 add 之后 remove(key, set) 会失败，
            // 从而不会把"刚刚有人订阅进去的新集合"删掉。
            table.remove(key, set);
        }
    }
}
