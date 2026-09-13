package com.bidarena.agentaccess.domain;

/**
 * 托管 AI 代理的生命周期。
 *
 * <pre>
 * 创建 ──▶ PENDING ──首次跟价──▶ BIDDING ──下次加价会超预算──▶ BUDGET_REACHED
 *             │                     │                              │
 *             │                     └──────────拍卖结束────────────┴──▶ FINISHED
 *             └──────────────────用户撤销────────────────────────────▶ REVOKED
 * </pre>
 *
 * <p>两个刻意的设计：
 *
 * <ul>
 *   <li><b>{@code BUDGET_REACHED} 不是终态。</b>它只表示"再加价就会超预算"，代理仍然可能是最高价，
 *       也可能被别人反超后一直保持这个状态直到拍卖结束。把它做成终态会丢掉"我很可能赢"这个信息，
 *       用户最关心的恰恰是"我的 AI 到顶了，现在到底领先没有"。</li>
 *   <li><b>{@code PENDING} 承担"已创建但拍卖还没开始"</b>（对应拍卖的 DRAFT，或带预告的未开拍）。
 *       调度器每轮都会看它一眼，拍卖一进入 RUNNING 就自动进场，所以"到点自动进场"不需要
 *       任何定时器或内存状态——重启后照样成立。</li>
 * </ul>
 */
public enum AgentProxyStatus {
    PENDING,
    BIDDING,
    BUDGET_REACHED,
    FINISHED,
    REVOKED;

    /** 调度器是否还要继续照看这个代理。 */
    public boolean isLive() {
        return this == PENDING || this == BIDDING;
    }

    /** 是否已经不再可能产生新的出价（用于界面上的"已停手"文案）。 */
    public boolean isStopped() {
        return this == BUDGET_REACHED || this == FINISHED || this == REVOKED;
    }

    public static AgentProxyStatus parse(String raw) {
        return valueOf(raw);
    }
}
