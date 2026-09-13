package com.bidarena.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.bidarena.Application;
import com.bidarena.support.Fixtures;
import com.bidarena.support.TestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.test.HttpTester;

/**
 * HTTP 层集成测试：真启动服务、真发包、真查库。
 *
 * <h2>为什么必须起真服务</h2>
 * P2 要证明的东西几乎全部住在"框架与业务之间"：过滤器顺序、鉴权白名单、
 * 异常到封套的翻译、201/405 这些状态码、Jackson 的字段名、分页参数校验。
 * 直接调控制器方法会把这些**全部跳过**，测出来的东西和线上跑的不是一回事。
 *
 * <h2>为什么整个类只起一次服务</h2>
 * Solon 是进程级单例，一个 JVM 只能 start 一次。因此把这个类做成唯一持有服务的测试类，
 * 类内每个用例之间用 {@link TestDatabase#wipe()} 复位数据，而不是复位进程。
 *
 * <h2>为什么把扫描间隔调到一小时</h2>
 * {@code Application} 启动时会拉起到期结算扫描器。它在测试进程里每秒扫一次库，
 * 会把**别的测试类**刚造出来还没结算的拍卖顺手结掉，症状是那些用例随机失败，
 * 而失败信息里看不出是这个后台线程干的。这里把它调远，测试需要结算时显式调
 * {@link com.bidarena.auction.application.SettlementScheduler#tick()}。
 */
class HttpApiIntegrationTest extends HttpTester {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "Admin123456!";
    private static final String BIDDER_A_EMAIL = "bidder_a@example.com";
    private static final String BIDDER_B_EMAIL = "bidder_b@example.com";
    private static final String BIDDER_PASSWORD = "Test123456!";

    private static DataSource ds;
    private static int port;

    static {
        // JDK 的 HttpURLConnection 默认把 Origin、Access-Control-Request-Method/Headers
        // 列为"受限头"并**静默丢弃**。这里是 CORS 用例唯一发包的地方，不打开这个开关，
        // 预检请求会变成"没有 Origin、也没有 Request-Method"的普通 OPTIONS，
        // 服务端于是按普通请求 405 拒绝——测试会以为是 CORS 代码写错了。（DEBUG_LOG DBG-9）
        // 必须在 HttpURLConnection 类初始化之前设置，所以放在静态块而不是 @BeforeAll。
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    // ---------------------------------------------------------------- 生命周期

    @BeforeAll
    static void startServer() throws Exception {
        ds = TestDatabase.dataSource();

        // 这些配置平时来自 .env，测试里用系统属性给出：Env 的读取顺序是系统属性优先，
        // 因此不必为了跑测试去改环境变量（改环境变量会影响同机上的其它进程）。
        System.setProperty("DB_URL", require("BID_ARENA_TEST_DB_URL"));
        System.setProperty("DB_USER", require("BID_ARENA_TEST_DB_USER"));
        System.setProperty("DB_PASSWORD", require("BID_ARENA_TEST_DB_PASSWORD"));
        System.setProperty("JWT_SECRET", "bidarena-http-test-secret-0123456789abcdef");
        System.setProperty("SETTLE_SCAN_INTERVAL_MS", "3600000");
        System.setProperty("SETTLE_BATCH_SIZE", "50");
        System.setProperty("CORS_ORIGINS", "http://localhost:5173");

        // 端口用随机空闲端口，避免与开发机上跑着的实例撞车。
        // 注意这里设的是 server.port 而不是 SERVER_PORT：app.yml 里的
        // ${SERVER_PORT:8080} 那种占位符只认**环境变量**，测试里改不动；
        // 而 SolonProps 会把系统属性里与 app.yml 同名的键盖上去（见 SolonProps.loadInit），
        // 所以 server.port 是测试唯一能可靠改到端口的方式。（DEBUG_LOG DBG-10）
        port = freePort();
        System.setProperty("server.port", String.valueOf(port));

        Application.main(new String[0]);
        assertEquals(port, Solon.cfg().serverPort(), "服务没有按测试指定的端口启动，后续请求会打到别处");
        awaitHealthy();
    }

    @AfterAll
    static void stopServer() {
        // 不能用 Solon.stop()：它会把停止流程丢到一个新线程里，而那个线程最后调的是
        // System.exit(0)。在 surefire 的 fork 里这等于杀掉测试 JVM，
        // 表现为 "forked VM terminated without properly saying goodbye"、
        // 整轮构建失败（DEBUG_LOG DBG-8）。
        // stopBlock(false, 0) 走的是同一条停止路径，只是不阻塞、不退出 JVM。
        Solon.stopBlock(false, 0);

        // 清掉带凭证的系统属性：surefire 会把 fork 的系统属性快照写进
        // target/surefire-reports/*.xml，并且因为 fork 被复用，同一个 JVM 里
        // 后面跑的测试类报告里也会带上它们——库口令就这样落到了构建产物里。
        // （与 CONTRIBUTING.md「禁止提交的内容」及 .gitignore 的意图一致：
        // target/ 不入库，但“不入库”不是理由把口令写进去。DEBUG_LOG DBG-12）
        System.clearProperty("DB_URL");
        System.clearProperty("DB_USER");
        System.clearProperty("DB_PASSWORD");
        System.clearProperty("JWT_SECRET");
    }

    @BeforeEach
    void seed() {
        TestDatabase.wipe();
        Fixtures.userWithPassword(ds, "usr_admin", ADMIN_EMAIL, ADMIN_PASSWORD, "ADMIN", 1000);
        Fixtures.userWithPassword(ds, "usr_bidder_a", BIDDER_A_EMAIL, BIDDER_PASSWORD, "BIDDER", 1000);
        Fixtures.userWithPassword(ds, "usr_bidder_b", BIDDER_B_EMAIL, BIDDER_PASSWORD, "BIDDER", 1000);
    }

    // ---------------------------------------------------------------- 基础：健康检查 / 鉴权 / 封套

    @Test
    @DisplayName("健康检查公开可访问，并且也走统一封套")
    void healthIsPublicAndEnveloped() {
        Resp resp = call("GET", "/api/v1/health", null, null);

        assertEquals(200, resp.code());
        assertCode("OK", resp);
        assertEquals("UP", resp.body().at("/data/status").asText());
        // 追踪 ID 必须存在：客户端报障时这是唯一能把一次请求与日志对上的线索。
        assertFalse(resp.body().path("requestId").asText().isEmpty(), resp.raw());
    }

    @Test
    @DisplayName("受保护路由缺令牌：401 且响应体仍是封套（不是框架的 HTML 错误页）")
    void missingTokenYieldsEnvelope() {
        Resp resp = call("GET", "/api/v1/auctions", null, null);

        assertEquals(401, resp.code());
        assertCode("UNAUTHENTICATED", resp);
        assertNotNull(resp.body().path("requestId").asText());
    }

    @Test
    @DisplayName("未知路径默认拒绝：没有令牌时是 401，而不是 404（不暴露路由是否存在）")
    void unknownPathIsDeniedByDefault() {
        Resp resp = call("GET", "/api/v1/definitely-not-a-route", null, null);

        assertEquals(401, resp.code());
        assertCode("UNAUTHENTICATED", resp);
    }

    @Test
    @DisplayName("未知路径带合法令牌：404 且是封套")
    void unknownPathWithTokenIsEnvelopedNotFound() {
        Resp resp = call("GET", "/api/v1/definitely-not-a-route", adminToken(), null);

        assertEquals(404, resp.code());
        assertCode("NOT_FOUND", resp);
    }

    @Test
    @DisplayName("路径对但方法不对：405 且是封套")
    void wrongMethodIsEnveloped() {
        // 带令牌才能走到路由层：AuthFilter 在路由之前，会先用 401 拦下无令牌请求。
        Resp resp = call("GET", "/api/v1/auth/login", adminToken(), null);

        assertEquals(405, resp.code());
        assertCode("METHOD_NOT_ALLOWED", resp);
    }

    @Test
    @DisplayName("坏令牌与错口令都得到同一种 401，不区分原因")
    void invalidTokenIsRejected() {
        Resp badToken = call("GET", "/api/v1/users/me", "not-a-real-token", null);
        assertEquals(401, badToken.code());
        assertCode("UNAUTHENTICATED", badToken);

        Resp badPassword = call("POST", "/api/v1/auth/login", null,
                json("email", ADMIN_EMAIL, "password", "WrongPassword1!"));
        assertEquals(401, badPassword.code());
        assertCode("UNAUTHENTICATED", badPassword);
    }

    // ---------------------------------------------------------------- 登录 / 当前用户

    @Test
    @DisplayName("登录返回令牌，用它访问 /users/me 得到自己的身份")
    void loginThenFetchCurrentUser() {
        Resp login = call("POST", "/api/v1/auth/login", null,
                json("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        assertEquals(200, login.code());
        assertCode("OK", login);

        String token = login.body().at("/data/accessToken").asText();
        assertFalse(token.isEmpty());
        // 口令哈希绝不能出现在响应里——包括被其它字段顺带带出来。
        assertFalse(login.raw().contains("$2a$"), "登录响应里出现了 BCrypt 哈希：" + login.raw());

        Resp me = call("GET", "/api/v1/users/me", token, null);
        assertEquals(200, me.code());
        assertCode("OK", me);
        assertEquals("usr_admin", me.body().at("/data/id").asText());
        assertEquals("ADMIN", me.body().at("/data/role").asText());
        assertFalse(me.body().at("/data/name").asText().isEmpty());
        assertFalse(me.raw().contains("password"), me.raw());
    }

    // ---------------------------------------------------------------- 管理员 / RBAC

    @Test
    @DisplayName("普通用户创建拍品被拒 403；管理员可创建，返回 201 与草稿快照")
    void rbacOnAdminRoutes() {
        Resp forbidden = call("POST", "/api/v1/admin/auctions", bidderToken(),
                createAuctionJson("非法创建", 100, 10, 60));
        assertEquals(403, forbidden.code());
        assertCode("FORBIDDEN", forbidden);

        Resp created = call("POST", "/api/v1/admin/auctions", adminToken(),
                createAuctionJson("测试拍品", 100, 10, 60));
        assertEquals(201, created.code(), created.raw());
        assertCode("OK", created);
        assertEquals("DRAFT", created.body().at("/data/status").asText());
        assertEquals(100, created.body().at("/data/startPrice").asLong());
        assertEquals(0, created.body().at("/data/participantCount").asInt());
        assertFalse(created.body().at("/data/serverTime").asText().isEmpty(),
                "快照必须带 serverTime，客户端倒计时要靠它：" + created.raw());
        assertNotNull(created.body().at("/data/id").asText());
    }

    @Test
    @DisplayName("创建参数校验：缺字段、起拍价<=0、时长越界都是 400 且带可读原因")
    void createValidation() {
        assertCode("VALIDATION_FAILED", call("POST", "/api/v1/admin/auctions", adminToken(),
                "{\"title\":\"缺字段\",\"startPrice\":100,\"minIncrement\":10}"));
        assertCode("VALIDATION_FAILED", call("POST", "/api/v1/admin/auctions", adminToken(),
                createAuctionJson("起拍价为0", 0, 10, 60)));
        assertCode("VALIDATION_FAILED", call("POST", "/api/v1/admin/auctions", adminToken(),
                createAuctionJson("时长太短", 100, 10, 1)));
        assertCode("VALIDATION_FAILED", call("POST", "/api/v1/admin/auctions", adminToken(),
                "{\"title\":\"\",\"startPrice\":100,\"minIncrement\":10,\"durationSeconds\":60}"));
    }

    @Test
    @DisplayName("开始拍卖：截止时间由服务端算出；重复开始是 409 而不是静默成功")
    void startAuctionIsNotIdempotent() {
        String auctionId = createAuction("开始测试", 100, 10, 60);

        Resp started = call("POST", "/api/v1/admin/auctions/" + auctionId + "/start", adminToken(), null);
        assertEquals(200, started.code(), started.raw());
        assertEquals("RUNNING", started.body().at("/data/status").asText());
        assertFalse(started.body().at("/data/endsAt").asText().isEmpty(), started.raw());

        // 第二次"开始"没有生效，必须让调用方知道，而不是返回同一个结果假装成功。
        Resp again = call("POST", "/api/v1/admin/auctions/" + auctionId + "/start", adminToken(), null);
        assertEquals(409, again.code(), again.raw());
        assertCode("INVALID_STATE", again);
    }

    @Test
    @DisplayName("开始/取消不存在的拍卖：404 封套")
    void adminOnUnknownAuction() {
        assertCode("NOT_FOUND", call("POST", "/api/v1/admin/auctions/auc_ghost/start", adminToken(), null));
        assertCode("NOT_FOUND", call("POST", "/api/v1/admin/auctions/auc_ghost/cancel", adminToken(), null));
    }

    // ---------------------------------------------------------------- 加入 / 出价

    @Test
    @DisplayName("出价全链路：加入 → 出价 → 幂等重放 → 余额冻结 → 流水可追溯")
    void bidFlowWithIdempotentReplay() {
        String auctionId = createRunningAuction("出价测试", 100, 10, 600);
        String tokenA = bidderToken();

        Resp joined = call("POST", "/api/v1/auctions/" + auctionId + "/join", tokenA, null);
        assertEquals(200, joined.code(), joined.raw());
        assertEquals(auctionId, joined.body().at("/data/auctionId").asText());
        assertEquals("usr_bidder_a", joined.body().at("/data/userId").asText());

        // 重复加入幂等：刷新页面重新加入不该报错，也不该改首次加入时间。
        Resp rejoin = call("POST", "/api/v1/auctions/" + auctionId + "/join", tokenA, null);
        assertEquals(200, rejoin.code(), rejoin.raw());
        assertEquals(joined.body().at("/data/joinedAt").asText(), rejoin.body().at("/data/joinedAt").asText());

        String requestId = "req-bid-000001";
        Resp bid = call("POST", "/api/v1/auctions/" + auctionId + "/bids", tokenA,
                json("requestId", requestId, "amount", 120));
        assertEquals(200, bid.code(), bid.raw());
        assertCode("OK", bid);
        assertTrue(bid.body().at("/data/accepted").asBoolean());
        assertFalse(bid.body().at("/data/idempotent").asBoolean());
        assertEquals(120, bid.body().at("/data/price").asLong());
        // seq 是拍卖的单调版本号，开始拍卖本身就会推进一次，所以第一次出价是 2。
        assertEquals(2, bid.body().at("/data/seq").asLong());
        assertFalse(bid.body().at("/data/serverTime").asText().isEmpty());

        // 同一个幂等键重发：必须返回首次结果，并让客户端能认出"这是重放"。
        Resp replay = call("POST", "/api/v1/auctions/" + auctionId + "/bids", tokenA,
                json("requestId", requestId, "amount", 120));
        assertEquals(200, replay.code(), replay.raw());
        assertCode("IDEMPOTENCY_REPLAY", replay);
        assertTrue(replay.body().at("/data/idempotent").asBoolean());
        assertEquals(bid.body().at("/data/seq").asLong(), replay.body().at("/data/seq").asLong(),
                "重放不该推进 seq");
        assertEquals(bid.body().at("/data/price").asLong(), replay.body().at("/data/price").asLong());

        // 资金：只冻结一次。
        Resp wallet = call("GET", "/api/v1/wallets/me", tokenA, null);
        assertEquals(200, wallet.code());
        assertEquals(1000, wallet.body().at("/data/totalBalance").asLong());
        assertEquals(120, wallet.body().at("/data/frozenAmount").asLong());
        assertEquals(880, wallet.body().at("/data/availableBalance").asLong());

        // 流水：一条 FREEZE，且能追回到这次请求。
        Resp ledger = call("GET", "/api/v1/wallets/me/ledger", tokenA, null);
        assertEquals(200, ledger.code());
        assertEquals(1, ledger.body().at("/data/items").size(), ledger.raw());
        assertEquals("FREEZE", ledger.body().at("/data/items/0/type").asText());
        assertEquals(120, ledger.body().at("/data/items/0/amount").asLong());
        assertEquals(requestId, ledger.body().at("/data/items/0/requestId").asText());
        assertEquals(auctionId, ledger.body().at("/data/items/0/auctionId").asText());

        // 出价列表与快照一致。
        Resp bids = call("GET", "/api/v1/auctions/" + auctionId + "/bids", tokenA, null);
        assertEquals(1, bids.body().at("/data/items").size());
        assertEquals("usr_bidder_a", bids.body().at("/data/items/0/userId").asText());
        assertEquals(120, bids.body().at("/data/items/0/amount").asLong());
        assertEquals(1, bids.body().at("/data/total").asLong());

        Resp snapshot = call("GET", "/api/v1/auctions/" + auctionId, tokenA, null);
        assertEquals(120, snapshot.body().at("/data/currentPrice").asLong());
        assertEquals("usr_bidder_a", snapshot.body().at("/data/leader").asText());
        assertEquals(1, snapshot.body().at("/data/participantCount").asInt());
    }

    @Test
    @DisplayName("未加入不能出价；加价不足是 BID_TOO_LOW；金额非法是 400")
    void bidGuards() {
        String auctionId = createRunningAuction("出价守卫", 100, 10, 600);
        String tokenA = bidderToken();
        String tokenB = bidderTokenB();

        // 未加入：这是"用户忘了点加入"的唯一可解释结论，不能笼统报冲突。
        Resp notJoined = call("POST", "/api/v1/auctions/" + auctionId + "/bids", tokenB,
                json("requestId", "req-not-joined-1", "amount", 200));
        assertEquals(409, notJoined.code(), notJoined.raw());
        assertCode("NOT_JOINED", notJoined);

        join(auctionId, tokenA);
        join(auctionId, tokenB);

        Resp first = call("POST", "/api/v1/auctions/" + auctionId + "/bids", tokenA,
                json("requestId", "req-guard-0001", "amount", 120));
        assertCode("OK", first);

        // 当前价 120 + 最小加价 10 = 130，出 120 必须被拒。
        Resp tooLow = call("POST", "/api/v1/auctions/" + auctionId + "/bids", tokenB,
                json("requestId", "req-guard-0002", "amount", 120));
        assertEquals(409, tooLow.code(), tooLow.raw());
        assertCode("BID_TOO_LOW", tooLow);

        Resp zero = call("POST", "/api/v1/auctions/" + auctionId + "/bids", tokenB,
                json("requestId", "req-guard-0003", "amount", 0));
        assertEquals(400, zero.code(), zero.raw());
        assertCode("VALIDATION_FAILED", zero);

        // 失败请求不能留下任何痕迹：没有流水、没有出价记录、序号不变。
        Resp bids = call("GET", "/api/v1/auctions/" + auctionId + "/bids", tokenA, null);
        assertEquals(1, bids.body().at("/data/total").asLong(), bids.raw());
        Resp walletB = call("GET", "/api/v1/wallets/me", tokenB, null);
        assertEquals(0, walletB.body().at("/data/frozenAmount").asLong());
        assertEquals(0, call("GET", "/api/v1/wallets/me/ledger", tokenB, null)
                .body().at("/data/total").asLong());
    }

    @Test
    @DisplayName("幂等键：缺失是 400；body 与头不一致是 400（猜错会把重试变成重复冻结）")
    void idempotencyKeyResolution() {
        String auctionId = createRunningAuction("幂等键", 100, 10, 600);
        String token = bidderToken();
        join(auctionId, token);

        Resp missing = call("POST", "/api/v1/auctions/" + auctionId + "/bids", token,
                "{\"amount\":120}");
        assertEquals(400, missing.code(), missing.raw());
        assertCode("VALIDATION_FAILED", missing);

        Resp onlyHeader = call("POST", "/api/v1/auctions/" + auctionId + "/bids", token,
                "{\"amount\":120}", "Idempotency-Key", "req-header-only-1");
        assertCode("OK", onlyHeader);

        // 只给头也是合法的写法，但重放必须认得出来。
        Resp onlyHeaderReplay = call("POST", "/api/v1/auctions/" + auctionId + "/bids", token,
                "{\"amount\":120}", "Idempotency-Key", "req-header-only-1");
        assertCode("IDEMPOTENCY_REPLAY", onlyHeaderReplay);

        Resp mismatch = call("POST", "/api/v1/auctions/" + auctionId + "/bids", token,
                json("requestId", "req-body-000001", "amount", 200), "Idempotency-Key", "req-header-x");
        assertEquals(400, mismatch.code(), mismatch.raw());
        assertCode("VALIDATION_FAILED", mismatch);
    }

    // ---------------------------------------------------------------- 查询 / 分页 / 结算结果

    @Test
    @DisplayName("拍卖列表：状态过滤、分页回显；非法状态与越界分页都是 400")
    void listAuctionsWithFilters() {
        createRunningAuction("在跑", 100, 10, 600);
        createAuction("草稿", 100, 10, 60);
        String token = adminToken();

        Resp all = call("GET", "/api/v1/auctions", token, null);
        assertCode("OK", all);
        assertEquals(2, all.body().at("/data/items").size(), all.raw());
        assertEquals(2, all.body().at("/data/total").asLong());
        assertEquals(1, all.body().at("/data/page").asInt());
        assertEquals(20, all.body().at("/data/size").asInt());

        Resp running = call("GET", "/api/v1/auctions?status=RUNNING", token, null);
        assertEquals(1, running.body().at("/data/items").size(), running.raw());
        assertEquals("RUNNING", running.body().at("/data/items/0/status").asText());

        Resp paged = call("GET", "/api/v1/auctions?page=2&size=1", token, null);
        assertEquals(1, paged.body().at("/data/items").size());
        assertEquals(2, paged.body().at("/data/page").asInt());
        assertEquals(1, paged.body().at("/data/size").asInt());
        assertEquals(2, paged.body().at("/data/total").asLong());

        assertCode("VALIDATION_FAILED", call("GET", "/api/v1/auctions?status=NOPE", token, null));
        // size 上限是契约写死的：不设上限时一个 size=1000000 就能把整张表读进内存。
        assertCode("VALIDATION_FAILED", call("GET", "/api/v1/auctions?size=1000", token, null));
        assertCode("VALIDATION_FAILED", call("GET", "/api/v1/auctions?page=0", token, null));
        assertCode("VALIDATION_FAILED", call("GET", "/api/v1/auctions?page=abc", token, null));
    }

    @Test
    @DisplayName("未结算的拍卖查结果是 404；结算后返回赢家与原因")
    void resultBeforeAndAfterSettlement() {
        String auctionId = createRunningAuction("结果测试", 100, 10, 600);
        String token = bidderToken();
        join(auctionId, token);
        assertCode("OK", call("POST", "/api/v1/auctions/" + auctionId + "/bids", token,
                json("requestId", "req-result-0001", "amount", 150)));

        assertCode("NOT_FOUND", call("GET", "/api/v1/auctions/" + auctionId + "/result", token, null));

        // 把截止时间推到过去，再用调度器跑一轮——这条路径与线上到期结算是同一段代码。
        Fixtures.expireAuction(ds, auctionId);
        Fixtures.scheduler(ds, 50).tick();

        Resp result = call("GET", "/api/v1/auctions/" + auctionId + "/result", token, null);
        assertEquals(200, result.code(), result.raw());
        assertCode("OK", result);
        assertEquals("FINISHED", result.body().at("/data/status").asText());
        assertEquals("usr_bidder_a", result.body().at("/data/winner").asText());
        assertEquals(150, result.body().at("/data/finalPrice").asLong());
        assertEquals("TIMEOUT", result.body().at("/data/reason").asText());

        // 结算后赢家被实际扣款：总额减少，冻结归零。
        Resp wallet = call("GET", "/api/v1/wallets/me", token, null);
        assertEquals(850, wallet.body().at("/data/totalBalance").asLong());
        assertEquals(0, wallet.body().at("/data/frozenAmount").asLong());
    }

    @Test
    @DisplayName("取消拍卖：冻结全部释放，拍卖不可再加入，结果原因是 CANCELLED")
    void cancelReleasesEverything() {
        String auctionId = createRunningAuction("取消测试", 100, 10, 600);
        String token = bidderToken();
        join(auctionId, token);
        assertCode("OK", call("POST", "/api/v1/auctions/" + auctionId + "/bids", token,
                json("requestId", "req-cancel-0001", "amount", 200)));

        Resp cancelled = call("POST", "/api/v1/admin/auctions/" + auctionId + "/cancel", adminToken(), null);
        assertEquals(200, cancelled.code(), cancelled.raw());
        assertEquals("CANCELLED", cancelled.body().at("/data/status").asText());

        Resp wallet = call("GET", "/api/v1/wallets/me", token, null);
        assertEquals(1000, wallet.body().at("/data/totalBalance").asLong());
        assertEquals(0, wallet.body().at("/data/frozenAmount").asLong());

        Resp rejoin = call("POST", "/api/v1/auctions/" + auctionId + "/join", bidderTokenB(), null);
        assertEquals(409, rejoin.code(), rejoin.raw());
        assertCode("INVALID_STATE", rejoin);

        Resp result = call("GET", "/api/v1/auctions/" + auctionId + "/result", adminToken(), null);
        assertEquals("CANCELLED", result.body().at("/data/reason").asText());
        // 序列化器会省略 null 字段，所以"没有赢家"既可能是 null 也可能是整个键缺失。
        assertTrue(result.body().path("data").path("winner").isMissingNode()
                        || result.body().at("/data/winner").isNull(),
                "取消的拍卖不应有赢家：" + result.raw());
    }

    @Test
    @DisplayName("没有流水的钱包：返回空页而不是 404")
    void emptyLedgerIsAnEmptyPage() {
        Resp ledger = call("GET", "/api/v1/wallets/me/ledger", bidderTokenB(), null);
        assertEquals(200, ledger.code());
        assertEquals(0, ledger.body().at("/data/items").size());
        assertEquals(0, ledger.body().at("/data/total").asLong());
        assertEquals(20, ledger.body().at("/data/size").asInt());
    }

    @Test
    @DisplayName("CORS：白名单来源回显 Origin 并可预检；非白名单不加头")
    void corsFollowsAllowList() {
        Resp allowed = call("OPTIONS", "/api/v1/auctions", null, null,
                "Origin", "http://localhost:5173",
                "Access-Control-Request-Method", "GET");
        assertEquals(204, allowed.code(), allowed.raw());
        assertEquals("http://localhost:5173", allowed.header("Access-Control-Allow-Origin"));

        Resp denied = call("OPTIONS", "/api/v1/auctions", null, null,
                "Origin", "http://evil.example",
                "Access-Control-Request-Method", "GET");
        assertNotEquals("http://evil.example", denied.header("Access-Control-Allow-Origin"));
    }

    // ---------------------------------------------------------------- 工具

    /** 一次响应的最小快照：状态码、解析后的 JSON、原始文本（断言失败时打印它）。 */
    private record Resp(int code, JsonNode body, String raw, java.util.Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private Resp call(String method, String url, String token, String jsonBody, String... headers) {
        HttpUtils http = path(url);
        if (token != null) {
            http = http.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            http = http.header(headers[i], headers[i + 1]);
        }
        if (jsonBody != null) {
            http = http.bodyJson(jsonBody);
        }
        try (HttpResponse resp = http.exec(method)) {
            String raw = resp.bodyAsString();
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (String name : resp.headerNames()) {
                map.put(name.toLowerCase(), resp.header(name));
            }
            JsonNode parsed = raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
            return new Resp(resp.code(), parsed, raw, map);
        } catch (IOException e) {
            throw new UncheckedIOException("请求失败 " + method + " " + url + "：" + e.getMessage(), e);
        }
    }

    private static void assertCode(String expected, Resp resp) {
        assertEquals(expected, resp.body().path("code").asText(),
                "业务码不符，实际响应：" + resp.raw());
    }

    private String login(String email, String password) {
        Resp resp = call("POST", "/api/v1/auth/login", null, json("email", email, "password", password));
        assertEquals(200, resp.code(), resp.raw());
        return resp.body().at("/data/accessToken").asText();
    }

    private String adminToken() {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String bidderToken() {
        return login(BIDDER_A_EMAIL, BIDDER_PASSWORD);
    }

    private String bidderTokenB() {
        return login(BIDDER_B_EMAIL, BIDDER_PASSWORD);
    }

    private String createAuction(String title, long startPrice, long minIncrement, int durationSeconds) {
        Resp resp = call("POST", "/api/v1/admin/auctions", adminToken(),
                createAuctionJson(title, startPrice, minIncrement, durationSeconds));
        assertEquals(201, resp.code(), resp.raw());
        String id = resp.body().at("/data/id").asText();
        assertFalse(id.isEmpty(), resp.raw());
        return id;
    }

    private String createRunningAuction(String title, long startPrice, long minIncrement, int durationSeconds) {
        String id = createAuction(title, startPrice, minIncrement, durationSeconds);
        Resp started = call("POST", "/api/v1/admin/auctions/" + id + "/start", adminToken(), null);
        assertEquals(200, started.code(), started.raw());
        assertEquals("RUNNING", started.body().at("/data/status").asText());
        return id;
    }

    private void join(String auctionId, String token) {
        Resp resp = call("POST", "/api/v1/auctions/" + auctionId + "/join", token, null);
        assertEquals(200, resp.code(), resp.raw());
    }

    private static String createAuctionJson(String title, long startPrice, long minIncrement, int durationSeconds) {
        return "{\"title\":\"" + title + "\",\"description\":\"测试描述\",\"startPrice\":" + startPrice
                + ",\"minIncrement\":" + minIncrement + ",\"durationSeconds\":" + durationSeconds + "}";
    }

    /**
     * 拼 JSON。字符串值自动加引号，数字/布尔原样写入。
     *
     * <p>刻意不接受"预拼好的 JSON 片段"：早先的版本把字符串值当成裸值拼进去，
     * 生成 {@code "password":Admin123456!} 这种非法 JSON，而服务端只会回一个
     * 500，排查成本比在这里多写两行高得多。
     */
    private static String json(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keyValues[i]).append("\":");
            Object value = keyValues[i + 1];
            if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(value).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            fail("缺少环境变量 " + name + "，本测试需要真实 MySQL（见 README 的一键验证节）");
        }
        return value;
    }

    /**
     * 取一个空闲端口，但**刻意避开高位端口**。
     *
     * <p>WebSocket 插件默认绑在 HTTP 端口 + 10000 上（{@code WebSocketServerProps.getPort()}），
     * 而 Windows 的临时端口段一直在 49152–65535。用 {@code new ServerSocket(0)} 抽到的端口
     * 有机会落在 55536 以上，加上偏移量就超过 65535，服务会直接起不来。
     * 固定在一个较低的区间内探测，两边都能绑上。
     */
    private static int freePort() {
        for (int attempt = 0; attempt < 50; attempt++) {
            int candidate = 40000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9000);
            try (ServerSocket probe = new ServerSocket(candidate)) {
                return probe.getLocalPort();
            } catch (IOException busy) {
                // 被占用就换一个，50 次足够。
            }
        }
        throw new IllegalStateException("在 40000-49000 区间内找不到空闲端口");
    }

    private static void awaitHealthy() {
        for (int attempt = 0; attempt < 100; attempt++) {
            try (HttpResponse resp = HttpUtils.http("http://localhost:" + port + "/api/v1/health").exec("GET")) {
                if (resp.code() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // 端口还没起来是正常的，继续等。
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待服务启动时被中断", e);
            }
        }
        throw new IllegalStateException("服务在 10 秒内没有在端口 " + port + " 上就绪");
    }
}
