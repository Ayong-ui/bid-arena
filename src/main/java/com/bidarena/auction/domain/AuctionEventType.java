package com.bidarena.auction.domain;

/**
 * 实时事件类型与其**广播范围**。
 *
 * <p>范围写进枚举，而不是留给调用方记着：{@link Scope#REQUESTER} 与 {@link Scope#SELF}
 * 的事件只允许单播，{@code WsEventBroadcaster} 会对它们拒绝扇出（fail-closed）。
 * "拒绝出价"这类事件如果被误扇出，就是一次真实的信息泄露（把某人的失败暴露给全场），
 * 让这种错误在代码层面不可能发生，比在评审时靠人看更可靠。
 */
public enum AuctionEventType {

    /** 权威快照。连接建立时单播给本人，也用于"参与者"扇出（例如开拍）。 */
    AUCTION_SNAPSHOT(Scope.PARTICIPANTS),
    /** 有人加入拍卖间：参与人数变化。 */
    PARTICIPANT_JOINED(Scope.PARTICIPANTS),
    /** 出价被接受：价格、领先者、结束时间、延长次数。 */
    BID_ACCEPTED(Scope.PARTICIPANTS),
    /** 出价被拒绝。**只发给请求者本人**：失败原因是隐私，也是可被利用的信息（试探余额）。 */
    BID_REJECTED(Scope.REQUESTER),
    /** 截止时间被延长。与触发它的 {@code BID_ACCEPTED} 共享同一个 {@code seq}。 */
    AUCTION_EXTENDED(Scope.PARTICIPANTS),
    /** 拍卖终局：成交或取消。 */
    AUCTION_FINISHED(Scope.PARTICIPANTS),
    /** 连接与快照同步状态。**只发给本人**。 */
    CONNECTION_STATE(Scope.SELF);

    /** 事件可见范围。 */
    public enum Scope {
        /** 该场拍卖的参与者（以及订阅了该场的 ADMIN）。 */
        PARTICIPANTS,
        /** 仅触发这次命令的用户。 */
        REQUESTER,
        /** 仅连接本人。 */
        SELF
    }

    private final Scope scope;

    AuctionEventType(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }

    /** 是否允许扇出给全场参与者。单播类事件只能走"发给某人"的路径。 */
    public boolean broadcastable() {
        return scope == Scope.PARTICIPANTS;
    }
}
