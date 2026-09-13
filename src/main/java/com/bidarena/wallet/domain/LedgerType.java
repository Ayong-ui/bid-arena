package com.bidarena.wallet.domain;

/**
 * 资金流水类型。取值与迁移中的 {@code ck_ledger_type} 约束逐项一致。
 *
 * <p>之所以要做成枚举而不是在各处传字符串：这三个值是数据库 CHECK 约束的一部分，
 * 写错一个字母只会在插入时抛约束错误，而那时已经在一个长事务的中间，
 * 报错信息也只会说"约束被违反"，不指向写错的那一行代码。用枚举把这类错误提前到编译期。
 *
 * <p>三者的资金语义：
 * <ul>
 *   <li>{@link #FREEZE}——可用额减少、冻结额增加，钱还在用户账上；</li>
 *   <li>{@link #RELEASE}——方向相反，竞价失败或取消时把冻结还给可用额；</li>
 *   <li>{@link #SETTLE}——冻结额转为实际扣款，用户**总额也减少**。</li>
 * </ul>
 * 因此对账口径是 {@code 冻结额 = ΣFREEZE − ΣRELEASE − ΣSETTLE}。
 */
public enum LedgerType {
    FREEZE,
    RELEASE,
    SETTLE
}
