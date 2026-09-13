package com.bidarena.bootstrap;

import com.bidarena.auction.adapter.AuctionRepository;
import com.bidarena.auction.adapter.SettlementRepository;
import com.bidarena.auction.application.BidService;
import com.bidarena.auction.application.SettlementService;
import com.bidarena.wallet.adapter.WalletRepository;
import javax.sql.DataSource;

/**
 * 组合根：唯一的接线处。
 *
 * <p>存在的理由是测试与生产**共用同一套注入关系**。若集成测试自己 {@code new}
 * 一组仓储和服务，生产环境里少接一个参数（例如忘了传 {@code SettlementRepository}）
 * 就不会被任何测试发现。把接线抽成一件事之后，测试跑的就是线上那份图。
 *
 * <p>这里刻意不做依赖注入容器：整个应用的装配关系只有 7 个对象，
 * 一个构造器就能表达清楚；引入容器反而把"谁依赖谁"藏进了注解与扫描里。
 */
public final class Services {

    public final AuctionRepository auctions;
    public final WalletRepository wallets;
    public final SettlementRepository settlements;
    public final BidService bids;
    public final SettlementService settlement;

    private Services(DataSource ds) {
        this.auctions = new AuctionRepository(ds);
        this.wallets = new WalletRepository(ds);
        this.settlements = new SettlementRepository(ds);
        this.bids = new BidService(ds, auctions, wallets);
        this.settlement = new SettlementService(ds, auctions, wallets, settlements);
    }

    public static Services wire(DataSource ds) {
        return new Services(ds);
    }
}
