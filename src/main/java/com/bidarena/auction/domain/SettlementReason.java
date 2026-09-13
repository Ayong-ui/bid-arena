package com.bidarena.auction.domain;

/**
 * 结算原因。取值与 {@code docs/openapi.yaml} 的 {@code AuctionResult.reason} 逐项一致。
 *
 * <p>有没有赢家不是单独的状态，而是由 reason 决定：只有 {@link #TIMEOUT} 会产生成交扣款。
 * 这样"要不要动钱"只有一个判断点，不会出现两条路径各自判断、结果不一致。
 */
public enum SettlementReason {
    /** 到期且有人出价：赢家冻结额转为实际扣款。 */
    TIMEOUT,
    /** 到期但无人出价：允许无赢家结束，不产生任何扣款。 */
    NO_BIDS,
    /** 被取消：冻结全部释放，不产生扣款。 */
    CANCELLED;

    public boolean hasWinner() {
        return this == TIMEOUT;
    }
}
