package com.bidarena.auction.domain;

/**
 * 拍卖生命周期状态。取值与 {@code docs/openapi.yaml} 的 {@code AuctionStatus} 逐项一致。
 *
 * <pre>
 * DRAFT ──START──▶ RUNNING ──到期结算──▶ SETTLING ──▶ FINISHED
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
    /**
     * 已到期、正在结算。
     *
     * <p>它在**结算事务内部**被写入，不跨事务提交，因此外部永远观测不到。
     * 这样做的理由见 {@code SettlementService} 类注释：把"已抢占但钱还没动"变成一个持久状态，
     * 会让进程崩溃留下一个资金悬空、且扫描再也找不到的死状态。
     */
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
