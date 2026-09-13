package com.bidarena.bootstrap;

import com.bidarena.auction.adapter.AuctionRepository;
import com.bidarena.auction.adapter.SettlementRepository;
import com.bidarena.auction.application.AuctionCommandService;
import com.bidarena.auction.application.AuctionQueryService;
import com.bidarena.auction.application.BidService;
import com.bidarena.auction.application.SettlementService;
import com.bidarena.identity.adapter.BCryptPasswordHasher;
import com.bidarena.identity.adapter.JwtTokens;
import com.bidarena.identity.adapter.UserRepository;
import com.bidarena.identity.application.IdentityService;
import com.bidarena.identity.application.TokenService;
import com.bidarena.wallet.adapter.WalletRepository;
import com.bidarena.wallet.application.WalletQueryService;
import javax.sql.DataSource;

/**
 * 组合根：唯一的接线处。
 *
 * <p>存在的理由是测试与生产**共用同一套注入关系**。若集成测试自己 {@code new}
 * 一组仓储和服务，生产环境里少接一个参数（例如忘了传 {@code SettlementRepository}）
 * 就不会被任何测试发现。把接线抽成一件事之后，测试跑的就是线上那份图。
 *
 * <p>这里刻意不做依赖注入容器：整个应用的装配关系只有十来个对象，
 * 一个构造器就能表达清楚；引入容器反而把"谁依赖谁"藏进了注解与扫描里。
 *
 * <h2>为什么有两个 wire</h2>
 * 令牌签名密钥必须来自环境（{@link Env#required}），而集成测试运行时不保证有
 * {@code JWT_SECRET}。若让测试也走 {@link #wire(DataSource)}，测试就会依赖一个与
 * 被测逻辑无关的环境变量，配错时报的是"缺配置"而不是测试失败——会浪费很多排查时间。
 * 因此把密钥与有效期显式参数化，测试用 {@link #wire(DataSource, String, long)}。
 * 除密钥外，两个入口构造的是**同一张**对象图，这正是组合根的意义。
 */
public final class Services {

    public final AuctionRepository auctions;
    public final WalletRepository wallets;
    public final SettlementRepository settlements;
    public final BidService bids;
    public final SettlementService settlement;

    public final UserRepository users;
    public final TokenService tokens;
    public final IdentityService identity;

    public final WalletQueryService walletQueries;
    public final AuctionQueryService auctionQueries;
    public final AuctionCommandService auctionCommands;

    private Services(DataSource ds, String jwtSecret, long jwtTtlSeconds) {
        // —— 出站适配器（仓储）——
        this.auctions = new AuctionRepository(ds);
        this.wallets = new WalletRepository(ds);
        this.settlements = new SettlementRepository(ds);
        this.users = new UserRepository(ds);

        // —— 端口实现 ——
        // 密码哈希器由 identity 自己实现（BCrypt 是纯算法，没有外部依赖要注入）；
        // 令牌服务要读配置，所以从组合根传入，使 application 层看不到环境变量。
        this.tokens = new JwtTokens(jwtSecret, jwtTtlSeconds);

        // —— 应用服务（事务边界）——
        this.bids = new BidService(ds, auctions, wallets);
        this.settlement = new SettlementService(ds, auctions, wallets, settlements);
        this.identity = new IdentityService(users, new BCryptPasswordHasher(), tokens);

        // —— 只读查询 ——
        this.walletQueries = new WalletQueryService(wallets);
        this.auctionQueries = new AuctionQueryService(ds, auctions, settlements);

        // —— 写用例 ——
        // AuctionCommandService 复用同一个 SettlementService 实例，而不是自己 new 一个：
        // "取消"与"到期结算"必须是同一套资金逻辑，两个实例会让"改了其中一个"变成可能的缺陷。
        this.auctionCommands = new AuctionCommandService(ds, auctions, settlement);
    }

    /** 生产接线：令牌配置从环境读取，缺失即启动失败（见 {@link Env#required}）。 */
    public static Services wire(DataSource ds) {
        return new Services(ds, Env.required("JWT_SECRET"), Env.longOr("JWT_TTL_SECONDS", 8 * 3600L));
    }

    /** 测试接线：显式给出令牌密钥与有效期，不读环境变量。 */
    public static Services wire(DataSource ds, String jwtSecret, long jwtTtlSeconds) {
        return new Services(ds, jwtSecret, jwtTtlSeconds);
    }
}
