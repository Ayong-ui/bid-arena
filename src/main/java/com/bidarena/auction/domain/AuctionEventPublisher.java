package com.bidarena.auction.domain;

/**
 * 领域事件的发布端口。
 *
 * <p>接口放在 {@code domain}、实现放在 {@code adapter}（WebSocket 广播），
 * 这样出价/结算事务服务只依赖"把事件交出去"这件事，不知道也不关心有没有人连在上面
 * （见 {@code DESIGN.md} §2.2 的端口与适配器）。
 *
 * <h2>两条调用路径，而不是一条带参数的路</h2>
 * {@link #publish} 是"给全场参与者的扇出"，{@link #publishToUser} 是"只给某个人"。
 * 合成一个带"收件人可选"的方法，迟早会有人把 {@code null} 传成"发给所有人"——
 * 那正好是信息泄露。分开之后，{@code AuctionEventType.Scope} 与这两个方法一一对应，
 * 实现侧还能对用错的组合直接拒绝。
 *
 * <h2>实现必须吞掉异常</h2>
 * 事件在事务提交**之后**发布；广播失败不得回滚已提交的事务（见
 * {@code docs/REALTIME_AND_COMMAND_FLOW.md} §7）。因此实现方要保证
 * "方法不因广播失败而抛异常"，调用方也会再兜一层。
 */
public interface AuctionEventPublisher {

    /** 扇出给该场拍卖的参与者。传入 {@code Scope} 不是 {@code PARTICIPANTS} 的事件应被实现拒绝。 */
    void publish(AuctionEvent event);

    /** 只发给某个用户（他自己的连接）。用于 {@code BID_REJECTED} 与 {@code CONNECTION_STATE}。 */
    void publishToUser(String userId, AuctionEvent event);
}
