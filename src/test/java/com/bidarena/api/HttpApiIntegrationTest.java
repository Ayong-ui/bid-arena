package com.bidarena.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.support.ApiTestHarness;
import com.bidarena.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HTTP 层集成测试：真启动服务、真发包、真查库。
 *
 * <h2>为什么必须起真服务</h2>
 * P2 要证明的东西几乎全部住在"框架与业务之间"：过滤器顺序、鉴权白名单、
 * 异常到封套的翻译、201/405 这些状态码、Jackson 的字段名、分页参数校验。
 * 直接调控制器方法会把这些**全部跳过**，测出来的东西和线上跑的不是一回事。
 *
 * <h2>为什么服务由基座持有</h2>
 * Solon 是进程级单例，一个 JVM 只能 start 一次，而 WebSocket 用例需要连的是**同一个**服务。
 * 起停、端口探测、演示账号播种都在 {@link ApiTestHarness} 里，这里只写断言。
 */
class HttpApiIntegrationTest extends ApiTestHarness {

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
        // seq 是拍卖的单调版本号，每次成功提交推进一次：开始拍卖一次、加入一次，所以第一次出价是 3。
        // 这里写死具体值（而不是“比之前大”）是有意的：它把“哪一次状态变更占用了哪个版本号”钉成了契约。
        assertEquals(3, bid.body().at("/data/seq").asLong());
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

}