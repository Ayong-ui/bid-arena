package com.bidarena.agentaccess.application;

import java.util.Collection;
import java.util.Set;

/**
 * 出站端口：确认授权范围里的拍卖 ID 真实存在。
 *
 * <p>为什么要在这里挡一下：{@code agent_token_auctions.auction_id} 有外键指向
 * {@code auctions}，所以一个写错的 ID 无论如何都进不了库。区别只在错误长什么样——
 * 不校验的话它是一条 {@code SQLIntegrityConstraintViolationException}，
 * 被兜底过滤器翻译成 500，管理员看到"服务器内部错误"，而真实原因是他自己把
 * {@code auc_ab12} 打成了 {@code auc_ab21}。校验之后就是一句 400 + 缺失的 ID 列表。
 *
 * <p>用接口而不是直接依赖 {@code AuctionQueryService}：本类的单元测试只需要回答
 * "这几个 ID 在不在"，为此引入一个真实的查询服务（连带 DataSource）会让测试
 * 从"验证签发规则"变成"搭一套数据库脚手架"，而被测的那几条规则恰恰与数据库无关。
 */
@FunctionalInterface
public interface AuctionScopeLookup {

    /** 返回入参中真实存在的那些 ID；不存在的照原样丢弃。 */
    Set<String> existing(Collection<String> auctionIds);
}
