package com.bidarena.shared;

/**
 * 一次出价 / 一条资金动作的**主体类型**：真人还是竞拍 Agent。
 *
 * <p>放在共享内核而不是某个上下文里，是因为它被两个上下文同时需要：
 * auction（出价与结算）要知道"这笔是谁出的"，wallet（流水）要把它落账。
 * 而 wallet 不能反向依赖 auction（会形成上下文环，见 {@code ArchitectureTest}），
 * 因此这个最小词汇表只能住在 {@code shared}。
 *
 * <p>为什么不能复用 {@code auction_participants.participant_type}：
 * 那条记录首次加入后就不再更新（join 是 {@code ON DUPLICATE KEY UPDATE user_id = user_id}），
 * 人先加入、Agent 后用同一账号出价会把它记成 {@code HUMAN}。
 * 主体类型必须跟着**每一笔出价/流水**走，而不是跟着参与记录走。
 */
public enum ActorType {

    /** 真人通过用户 JWT 出价。 */
    HUMAN,

    /** 竞拍 Agent 通过受限 Agent Token 出价。 */
    AGENT;

    /**
     * 解析入库值。
     *
     * <p>未知或缺失一律归为 {@link #HUMAN}：迁移前写入的存量行没有这一列，
     * 而它们全部来自真人脚本与前端，归为 HUMAN 是事实而不是猜测。
     */
    public static ActorType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return HUMAN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return HUMAN;
        }
    }
}
