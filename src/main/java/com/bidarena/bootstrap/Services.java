package com.bidarena.bootstrap;

import com.bidarena.auction.adapter.AuctionRepository;
import com.bidarena.auction.adapter.AuctionSocketHandler;
import com.bidarena.auction.adapter.SettlementRepository;
import com.bidarena.auction.adapter.WsEventBroadcaster;
import com.bidarena.auction.application.AuctionCommandService;
import com.bidarena.auction.application.AuctionQueryService;
import com.bidarena.auction.application.BidService;
import com.bidarena.auction.application.SettlementService;
import com.bidarena.identity.adapter.BCryptPasswordHasher;
import com.bidarena.identity.adapter.JwtTokens;
import com.bidarena.identity.adapter.UserRepository;
import com.bidarena.identity.application.IdentityService;
import com.bidarena.identity.application.TokenService;
import com.bidarena.identity.application.WsTicketService;
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

    // —— 实时通道 ——
    public final WsEventBroadcaster broadcaster;
    public final WsTicketService wsTickets;
    public final AuctionSocketHandler auctionSocket;

    /** WebSocket 票的默认参数，与 {@code .env.example} 里的默认值保持一致。 */
    private static final long WS_TICKET_TTL_SECONDS_DEFAULT = 60L;
    private static final int WS_TICKET_CAPACITY_DEFAULT = 10_000;

    private Services(DataSource ds, String jwtSecret, long jwtTtlSeconds, long wsTicketTtlSeconds,
            int wsTicketCapacity) {
        // —— 出站适配器（仓储）——
        this.auctions = new AuctionRepository(ds);
        this.wallets = new WalletRepository(ds);
        this.settlements = new SettlementRepository(ds);
        this.users = new UserRepository(ds);

        // —— 端口实现 ——
        // 密码哈希器由 identity 自己实现（BCrypt 是纯算法，没有外部依赖要注入）；
        // 令牌服务要读配置，所以从组合根传入，使 application 层看不到环境变量。
        this.tokens = new JwtTokens(jwtSecret, jwtTtlSeconds);

        // —— 实时通道 ——
        // 广播器要在应用服务之前建出来：它是服务与连接之间的唯一出口，
        // 服务只知道 AuctionEventPublisher 这个端口，不知道 WebSocket 的存在。
        this.broadcaster = new WsEventBroadcaster();
        this.wsTickets = WsTicketService.withSystemClock(
                java.time.Duration.ofSeconds(wsTicketTtlSeconds), wsTicketCapacity);

        // —— 应用服务（事务边界）——
        this.bids = new BidService(ds, auctions, wallets, broadcaster);
        this.settlement = new SettlementService(ds, auctions, wallets, settlements, broadcaster);
        this.identity = new IdentityService(users, new BCryptPasswordHasher(), tokens);

        // —— 只读查询 ——
        this.walletQueries = new WalletQueryService(wallets);
        this.auctionQueries = new AuctionQueryService(ds, auctions, settlements);

        // —— 写用例 ——
        // AuctionCommandService 复用同一个 SettlementService 实例，而不是自己 new 一个：
        // "取消"与"到期结算"必须是同一套资金逻辑，两个实例会让"改了其中一个"变成可能的缺陷。
        this.auctionCommands = new AuctionCommandService(ds, auctions, settlement, broadcaster);

        // 入站适配器放在最后：它依赖查询服务（握手要先读快照），而路由注册在 Application。
        this.auctionSocket = new AuctionSocketHandler(wsTickets, auctionQueries, broadcaster);
    }

    /** 生产接线：令牌配置从环境读取，缺失即启动失败（见 {@link Env#required}）。 */
    public static Services wire(DataSource ds) {
        return new Services(ds, Env.required("JWT_SECRET"), Env.longOr("JWT_TTL_SECONDS", 8 * 3600L),
                Env.longOr("WS_TICKET_TTL_SECONDS", WS_TICKET_TTL_SECONDS_DEFAULT),
                Env.intOr("WS_TICKET_CAPACITY", WS_TICKET_CAPACITY_DEFAULT));
    }

    /**
     * 测试接线：显式给出令牌密钥与有效期，不读环境变量。
     *
     * <p>WebSocket 票用默认 TTL 与容量。需要验证"票过期"的用例自己构造一个带假时钟的
     * {@link WsTicketService}（纯单元测试），而不是让整张对象图为了一个时间参数变形。
     */
    public static Services wire(DataSource ds, String jwtSecret, long jwtTtlSeconds) {
        return new Services(ds, jwtSecret, jwtTtlSeconds, WS_TICKET_TTL_SECONDS_DEFAULT,
                WS_TICKET_CAPACITY_DEFAULT);
    }
}
