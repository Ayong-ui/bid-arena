package com.bidarena.auction.application;

import com.bidarena.shared.ApiTime;
import com.bidarena.auction.application.AuctionViews;
import com.bidarena.auction.domain.AuctionEvent;
import com.bidarena.auction.domain.AuctionEventType;
import com.bidarena.shared.AnonymousId;
import com.bidarena.shared.BizException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件 payload 的**唯一**组装处（契约见 {@code docs/REALTIME_AND_COMMAND_FLOW.md} §4）。
 *
 * <h2>为什么集中在这里</h2>
 * payload 的字段名就是给前端的契约。若每个事务服务各自拼 Map，
 * "BID_ACCEPTED 里叫 price、AUCTION_EXTENDED 里叫 newPrice"这类漂移只会等到前端联调时才发现。
 * 集中一处之后，字段可以从本文件直接对照契约表格逐条核对。
 *
 * <h2>为什么放在 application 而不是 domain</h2>
 * 它已经知道 HTTP/契约层的字段格式（时间用 {@link ApiTime} 格式化），
 * 属于"对外表达"而不是"业务规则"。domain 只保留信封与类型（见 {@link AuctionEvent}）。
 */
public final class AuctionEvents {

    /** 拒绝事件里允许外传的上下文字段：都是可公开解释的数值，不含任何内部标识。 */
    private static final List<String> REJECTION_CONTEXT_KEYS =
            List.of("amount", "minimum", "currentPrice", "minIncrement", "availableBalance", "requiredDelta",
                    "endsAt");

    private AuctionEvents() {}

    /**
     * 写一个字段，{@code null} 直接跳过。
     *
     * <p>事件 payload 与 HTTP 响应遵守同一条约定：**{@code null} 即"没有这个字段"**（见
     * {@code docs/REALTIME_AND_COMMAND_FLOW.md} §4）。这不只是为了好看：{@link AuctionEvent}
     * 用 {@code Map.copyOf} 固化 payload，而它不允许 {@code null} 值——
     * "忘了判空"会在发布那一刻变成 NPE，而不是一个悄悄多出来的 {@code "leader": null}。
     */
    private static void put(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    /**
     * 权威快照。与 HTTP {@code AuctionSnapshot} 同构（少一个 {@code description}，事件不承载文案）。
     *
     * <p>{@code seq}/{@code serverTime} 在 payload 里再出现一次，是因为契约把它们列为快照的组成部分；
     * 它们与外层信封取自**同一次读取**，因此不可能不一致（测试对此有断言）。
     */
    public static AuctionEvent snapshot(AuctionViews.Snapshot view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "id", view.id());
        put(payload, "title", view.title());
        put(payload, "status", view.status());
        put(payload, "startPrice", view.startPrice());
        put(payload, "minIncrement", view.minIncrement());
        put(payload, "currentPrice", view.currentPrice());
        put(payload, "leader", AnonymousId.of(view.leader()));
        // 未开拍的 DRAFT 没有截止时间：此时这个字段缺席，而不是 null。
        put(payload, "endsAt", view.endsAt());
        put(payload, "extensionCount", view.extensionCount());
        put(payload, "participantCount", view.participantCount());
        put(payload, "seq", view.seq());
        put(payload, "serverTime", view.serverTime());
        return new AuctionEvent(AuctionEventType.AUCTION_SNAPSHOT, view.id(), view.seq(), view.serverTime(), payload);
    }

    public static AuctionEvent participantJoined(
            String auctionId, long seq, String userId, int participantCount, Instant serverTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "participant", AnonymousId.of(userId));
        put(payload, "participantCount", participantCount);
        return new AuctionEvent(AuctionEventType.PARTICIPANT_JOINED, auctionId, seq,
                ApiTime.format(serverTime), payload);
    }

    public static AuctionEvent bidAccepted(String auctionId, long seq, long price, String leaderId, Instant endsAt,
            int extensionCount, Instant serverTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "price", price);
        put(payload, "leader", AnonymousId.of(leaderId));
        put(payload, "endsAt", ApiTime.format(endsAt));
        put(payload, "extensionCount", extensionCount);
        return new AuctionEvent(AuctionEventType.BID_ACCEPTED, auctionId, seq, ApiTime.format(serverTime), payload);
    }

    /**
     * 延时事件。{@code seq} 与触发它的 {@code BID_ACCEPTED} **相同**：
     * 一次出价提交只推进一个版本号，客户端按 {@code seq} 去重（见契约 §3）。
     */
    public static AuctionEvent auctionExtended(
            String auctionId, long seq, Instant endsAt, int extensionCount, Instant serverTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "endsAt", ApiTime.format(endsAt));
        put(payload, "extensionCount", extensionCount);
        return new AuctionEvent(AuctionEventType.AUCTION_EXTENDED, auctionId, seq, ApiTime.format(serverTime), payload);
    }

    /**
     * 出价被拒。只发给请求者本人。
     *
     * <p>只透传白名单里的上下文字段：异常里的 {@code data} 可能包含内部标识（例如 {@code userId}），
     * 而事件是"给客户端看"的，顺手把整张 Map 塞进去会让一次异常重构变成一次信息泄露。
     */
    public static AuctionEvent bidRejected(String auctionId, long seq, BizException cause, Instant serverTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "code", cause.code().name());
        put(payload, "reason", cause.getMessage());
        // details() 永不为 null（构造时就 Map.copyOf），逐个白名单键取值即可。
        for (String key : REJECTION_CONTEXT_KEYS) {
            Object value = cause.details().get(key);
            if (value == null) {
                continue;
            }
            // 时间统一成字符串（契约要求 ISO-8601），金额保持数字：
            // 前端要拿它做比较与展示，转成字符串会逼出一次 parseInt。
            put(payload, key, value instanceof Instant instant ? ApiTime.format(instant) : value);
        }
        return new AuctionEvent(AuctionEventType.BID_REJECTED, auctionId, seq, ApiTime.format(serverTime), payload);
    }

    public static AuctionEvent auctionFinished(String auctionId, long seq, String winnerId, long finalPrice,
            String status, String reason, Instant serverTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // 无人出价或取消时没有赢家：这个字段缺席（与 HTTP 快照的 leader 同样处理）。
        put(payload, "winner", AnonymousId.of(winnerId));
        put(payload, "finalPrice", finalPrice);
        put(payload, "status", status);
        put(payload, "reason", reason);
        return new AuctionEvent(AuctionEventType.AUCTION_FINISHED, auctionId, seq, ApiTime.format(serverTime), payload);
    }

    /**
     * 连接状态。{@code connected = false} 时携带 {@code code}（原因码）与 {@code message}（可读原因），
     * 使客户端能区分"票过期了"和"网络断了"；此时 {@code snapshotSeq} 传 {@code null}，不下发。
     */
    public static AuctionEvent connectionState(String auctionId, long seq, boolean connected, Long snapshotSeq,
            String code, String message, Instant serverTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connected", connected);
        if (snapshotSeq != null) {
            payload.put("snapshotSeq", snapshotSeq);
        }
        if (code != null) {
            payload.put("code", code);
        }
        if (message != null) {
            payload.put("message", message);
        }
        return new AuctionEvent(AuctionEventType.CONNECTION_STATE, auctionId, seq, ApiTime.format(serverTime), payload);
    }
}
