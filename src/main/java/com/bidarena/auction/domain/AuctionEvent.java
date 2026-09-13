package com.bidarena.auction.domain;

import java.util.Map;

/**
 * 实时事件信封（契约见 {@code docs/REALTIME_AND_COMMAND_FLOW.md} §3）。
 *
 * <p>字段顺序与名称就是 wire format，因此**不**复用 HTTP 的视图类型：
 * HTTP 的封套有 {@code code/message/data/requestId}，而事件的调用方是"已经连上的客户端"，
 * 既没有请求对应的响应，也不该出现业务码以外的错误语义。两者共享底层数据、不共享外壳。
 *
 * <p>{@code serverTime} 在这里是**字符串**而不是 {@code Instant}，是刻意的：
 * 序列化器是否注册 JavaTime 模块属于外部配置（见 {@code ApiTime} 的注释），
 * 若让它决定 wire format，换个环境时间格式就会变，而这是契约字段。
 *
 * <p>{@code payload} 在构造时拷贝成不可变 Map：一个事件对象可能被多个线程同时序列化
 * （广播是并行的），可变 payload 会让"某一帧少了字段"变成偶发问题。
 */
public record AuctionEvent(
        AuctionEventType type, String auctionId, long seq, String serverTime, Map<String, Object> payload) {

    public AuctionEvent {
        if (type == null) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        if (auctionId == null) {
            auctionId = "";
        }
        if (serverTime == null) {
            throw new IllegalArgumentException("事件必须携带 serverTime（契约字段）");
        }
        // Map.copyOf 同时拒绝 null 键值：payload 里出现 null 会变成"字段时有时无"，
        // 而客户端无法区分"没有这个字段"和"这个字段是 null"（HTTP 封套那边已经踩过一次）。
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
