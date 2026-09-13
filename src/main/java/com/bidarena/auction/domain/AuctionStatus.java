package com.bidarena.auction.domain;

/**
 * 拍卖生命周期状态。取值与 {@code docs/openapi.yaml} 的 {@code AuctionStatus} 逐项一致。
 *
 * <pre>
 * DRAFT ──START──▶ RUNNING ──到期结算──▶ FINISHED
 *   │                 │
 *   └──CANCEL─────────┴──▶ CANCELLED
 * </pre>
 *
 * <p>不设"已售出 / 流拍"两个终态：有无赢家由 {@code settlements.winner_id} 与 {@code reason}
 * 表达，状态本身只表达"是否已结束"。这样结算的幂等只需判断一次状态转换，
 * 不必为两种结束原因各写一条路径。
 */
public enum AuctionStatus {
    DRAFT,
    RUNNING,
    /** 已到期、正在结算。结算前先转到这里，使重复触发在第一步就被挡住。 */
    SETTLING,
    FINISHED,
    CANCELLED;

    public boolean isTerminal() {
        return this == FINISHED || this == CANCELLED;
    }

    public static AuctionStatus parse(String raw) {
        return valueOf(raw);
    }
}
