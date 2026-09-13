package com.bidarena.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.agentaccess.application.AgentProxyService;
import com.bidarena.auction.application.AuctionCommandService;
import com.bidarena.support.ApiTestHarness;
import com.bidarena.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 托管 AI 代理的端到端用例（真实服务 + 真实 MySQL）。
 *
 * <h2>为什么这里既有 HTTP 又有直接调用服务</h2>
 * 用户侧的动作（创建、撤销、列表）必须走 HTTP：它们是契约的一部分，路径与状态码本身就是被测对象。
 * 而"代理跟价"是服务端节奏，线上由 {@code AgentProxyScheduler} 每 500ms 驱动，
 * 测试里由 {@link AgentProxyService#tick(int)} 逐轮驱动——等定时器会让用例要么慢、要么靠 sleep 猜时间。
 * 后台调度器在测试基座里被调到 1 小时一次（见 {@code ApiTestHarness}），
 * 否则它会与这里的 {@code tick()} 抢跑，把确定性断言变成随机结果。
 */
class AgentProxyIntegrationTest extends ApiTestHarness {

    private static final String PROXIES = "/api/v1/me/agent-proxies";

    /**
     * 预告开拍用的字面量时刻，刻意<b>不</b>用 {@code Instant.now()} 现算。
     *
     * <p>测试 JVM 的时钟与 MySQL 容器的时钟并不保证一致（实测这里就差了数分钟），
     * 而"预告时间必须晚于当前时间"是拿**数据库时间**判定的。
     * 用现算的时刻会让用例随两台机器的钟差随机变红；用一个远古/远景的字面量则永远成立。
     */
    private static final String FAR_FUTURE = "2035-01-01T00:00:00Z";

    /** 与线上同一个组合根装配出的服务：状态都在数据库里，因此与 HTTP 侧的实例等价。 */
    private AgentProxyService proxies() {
        return Fixtures.services(ds).agentProxies;
    }

    private AuctionCommandService auctionCommands() {
        return Fixtures.services(ds).auctionCommands;
    }

    private String bidderA() {
        return bidderToken();
    }

    /** 创建代理，返回响应（成功与失败都由用例自己断言）。 */
    private Resp createProxy(String token, String auctionId, long budgetLimit) {
        return call("POST", PROXIES, token, json("auctionId", auctionId, "budgetLimit", budgetLimit));
    }

    private Resp myProxies(String token) {
        return call("GET", PROXIES, token, null);
    }

    // ------------------------------------------------------------------ 创建

    @Test
    @DisplayName("在草稿拍卖上创建代理：201，状态 PENDING，并算出下一口跟价")
    void createOnDraftAuction() {
        String auctionId = createAuction("AI 代拍", 100, 10, 300);

        Resp created = createProxy(bidderA(), auctionId, 500);

        assertEquals(201, created.code(), created.raw());
        assertCode("OK", created);
        assertFalse(created.data("proxyId").isEmpty(), created.raw());
        assertEquals("PENDING", created.data("status"));
        assertEquals(0, created.dataLong("bidCount"));
        assertEquals(100, created.dataLong("currentPrice"));
        assertEquals(10, created.dataLong("minIncrement"));
        assertEquals(110, created.dataLong("nextBidAmount"));
        assertFalse(created.body().at("/data/leading").asBoolean(), created.raw());
        assertEquals("DRAFT", created.data("auctionStatus"));
        assertNotNull(created.data("createdAt"), created.raw());

        // 列表里能看到自己刚建的那个，且只有它。
        Resp list = myProxies(bidderA());
        assertEquals(200, list.code(), list.raw());
        assertEquals(1, list.body().at("/data/total").asLong(), list.raw());
        assertEquals(auctionId, list.body().at("/data/items/0/auctionId").asText(), list.raw());
    }

    @Test
    @DisplayName("同一场同一人只能有一个代理：第二次创建 409")
    void secondProxyOnSameAuctionConflicts() {
        String auctionId = createAuction("唯一代理位", 100, 10, 300);
        assertEquals(201, createProxy(bidderA(), auctionId, 500).code());

        Resp again = createProxy(bidderA(), auctionId, 600);

        assertEquals(409, again.code(), again.raw());
        assertCode("CONFLICT", again);
        // 拒绝时不泄露"已存在的那个代理"的内部信息之外的东西，但给出可用的下一步线索。
        assertFalse(again.body().at("/data/proxyId").asText().isEmpty(), again.raw());
    }

    @Test
    @DisplayName("另一个人可以在同一场建自己的代理")
    void otherUserCanCreateOwnProxy() {
        String auctionId = createAuction("两个代理", 100, 10, 300);
        assertEquals(201, createProxy(bidderA(), auctionId, 500).code());

        Resp other = createProxy(bidderTokenB(), auctionId, 500);

        assertEquals(201, other.code(), other.raw());
        // 各自只看得到自己的。
        assertEquals(1, myProxies(bidderTokenB()).body().at("/data/total").asLong());
        assertEquals(1, myProxies(bidderA()).body().at("/data/total").asLong());
    }

    @Test
    @DisplayName("预算超过可用额：409 INSUFFICIENT_BALANCE（AI 出价会真实冻结）")
    void budgetAboveAvailableIsRejected() {
        String auctionId = createAuction("预算超限", 100, 10, 300);

        Resp resp = createProxy(bidderA(), auctionId, 10_000);

        assertEquals(409, resp.code(), resp.raw());
        assertCode("INSUFFICIENT_BALANCE", resp);
        assertEquals(0, myProxies(bidderA()).body().at("/data/total").asLong(), "被拒绝时不应落库");
    }

    @Test
    @DisplayName("预算缺失或非正数：400 VALIDATION_FAILED")
    void invalidBudgetIsRejected() {
        String auctionId = createAuction("非法预算", 100, 10, 300);

        Resp missing = call("POST", PROXIES, bidderA(), json("auctionId", auctionId));
        assertEquals(400, missing.code(), missing.raw());
        assertCode("VALIDATION_FAILED", missing);

        Resp zero = createProxy(bidderA(), auctionId, 0);
        assertEquals(400, zero.code(), zero.raw());
        assertCode("VALIDATION_FAILED", zero);

        Resp negative = createProxy(bidderA(), auctionId, -5);
        assertEquals(400, negative.code(), negative.raw());
        assertCode("VALIDATION_FAILED", negative);
    }

    @Test
    @DisplayName("拍卖不存在：404；已结束：409 INVALID_STATE")
    void unknownAndFinishedAuctions() {
        Resp unknown = createProxy(bidderA(), "auc_doesnotexist", 500);
        assertEquals(404, unknown.code(), unknown.raw());
        assertCode("NOT_FOUND", unknown);

        String auctionId = createRunningAuction("马上就取消", 100, 10, 300);
        assertEquals(200, call("POST", "/api/v1/admin/auctions/" + auctionId + "/cancel", adminToken(), null).code());

        Resp finished = createProxy(bidderA(), auctionId, 500);
        assertEquals(409, finished.code(), finished.raw());
        assertCode("INVALID_STATE", finished);
    }

    // ------------------------------------------------------------------ 跟价

    @Test
    @DisplayName("不是最高价时按最小加价跟一口，状态 PENDING → BIDDING")
    void proxyFollowsWithMinimumIncrement() {
        String auctionId = createRunningAuction("AI 跟价", 100, 10, 300);
        join(auctionId, bidderTokenB());
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-1", 110).code());
        createProxy(bidderA(), auctionId, 500);

        int actions = proxies().tick(50);

        assertEquals(1, actions, "应当恰好跟了一口");
        assertEquals(120, Fixtures.currentPrice(ds, auctionId));
        assertEquals(BIDDER_A_ID, Fixtures.leader(ds, auctionId));
        // 代理出价也带 actor_type=AGENT：这是"成交主体可追溯"的基础。
        assertEquals("AGENT", Fixtures.latestBidActorType(ds, auctionId, BIDDER_A_ID));

        Resp list = myProxies(bidderA());
        assertEquals("BIDDING", list.body().at("/data/items/0/status").asText(), list.raw());
        assertEquals(1, list.body().at("/data/items/0/bidCount").asLong(), list.raw());
        assertEquals(120, list.body().at("/data/items/0/lastBidAmount").asLong(), list.raw());
        assertTrue(list.body().at("/data/items/0/leading").asBoolean(), list.raw());
        assertEquals(130, list.body().at("/data/items/0/nextBidAmount").asLong(), list.raw());
    }

    @Test
    @DisplayName("已经领先时不再加价（不会自己顶自己）")
    void proxyDoesNotOutbidItself() {
        String auctionId = createRunningAuction("不自顶", 100, 10, 300);
        join(auctionId, bidderA());
        assertEquals(200, placeBid(auctionId, bidderA(), "human-a-1", 110).code());
        createProxy(bidderA(), auctionId, 500);

        assertEquals(0, proxies().tick(50));
        assertEquals(110, Fixtures.currentPrice(ds, auctionId));
        // 已经领先也算"已进场"，状态应推进到 BIDDING。
        assertEquals("BIDDING", myProxies(bidderA()).body().at("/data/items/0/status").asText());
    }

    @Test
    @DisplayName("跟到预算上限后停手，且只提醒一次（budgetReachedAt 幂等）")
    void proxyStopsAtBudgetCapAndRemindsOnce() {
        String auctionId = createRunningAuction("预算上限", 100, 10, 300);
        join(auctionId, bidderTokenB());
        createProxy(bidderA(), auctionId, 140);

        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-1", 110).code());
        assertEquals(1, proxies().tick(50));
        assertEquals(120, Fixtures.currentPrice(ds, auctionId));

        // 对手再加一口：下一口要 130 ≤ 140，仍然在预算内，继续跟。
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-2", 130).code());
        assertEquals(1, proxies().tick(50));
        assertEquals(140, Fixtures.currentPrice(ds, auctionId));

        // 对手再加一口：下一口要 150 > 140，触顶。
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-3", 150).code());
        assertEquals(1, proxies().tick(50), "触顶也是一次状态变更");
        assertEquals(150, Fixtures.currentPrice(ds, auctionId), "触顶后不得再出价，价格停在对手那一口");
        assertEquals(BIDDER_B_ID, Fixtures.leader(ds, auctionId));

        Resp list = myProxies(bidderA());
        assertEquals("BUDGET_REACHED", list.body().at("/data/items/0/status").asText(), list.raw());
        assertEquals(2, list.body().at("/data/items/0/bidCount").asLong(), list.raw());
        assertTrue(list.body().at("/data/items/0/budgetReached").asBoolean(), list.raw());
        String firstReminder = list.body().at("/data/items/0/budgetReachedAt").asText();
        assertFalse(firstReminder.isEmpty(), list.raw());

        // 再跑几轮也不重复出价，且不刷新提醒时间。
        assertEquals(0, proxies().tick(50));
        assertEquals(0, proxies().tick(50));
        assertEquals("BUDGET_REACHED", myProxies(bidderA()).body().at("/data/items/0/status").asText());
        assertEquals(firstReminder, myProxies(bidderA()).body().at("/data/items/0/budgetReachedAt").asText(),
                "提醒只发一次：时间戳必须保持首次写入的值");

        // 对手继续加价，代理也不跟：已经停手。
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-4", 160).code());
        assertEquals(0, proxies().tick(50));
        assertEquals(160, Fixtures.currentPrice(ds, auctionId));
        assertEquals(2, myProxies(bidderA()).body().at("/data/items/0/bidCount").asLong());
    }

    @Test
    @DisplayName("尾段博弈时间：AI 在最后 20 秒内无法出价，人仍然可以")
    void proxyCannotBidInsideFinalGameWindow() {
        String auctionId = createRunningAuction("尾段锁 AI", 100, 10, 300);
        join(auctionId, bidderTokenB());
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-1", 110).code());
        createProxy(bidderA(), auctionId, 500);
        // 把截止时间拉进窗口内（默认窗口 20s）：留下的时间比窗口短。
        Fixtures.setEndsAtIn(ds, auctionId, 3);

        assertEquals(0, proxies().tick(50), "窗口内不应产生任何出价");

        assertEquals(110, Fixtures.currentPrice(ds, auctionId));
        assertEquals(BIDDER_B_ID, Fixtures.leader(ds, auctionId));
        assertEquals("PENDING", myProxies(bidderA()).body().at("/data/items/0/status").asText(),
                "被尾段拒绝不是业务失败，代理保持待命而不是被标成异常");

        // 同一时刻人仍然可以出价（D-32：博弈时间只拒绝 AI）。
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-2", 120).code());

        // 被拒绝不等于代理阵亡：把截止时间移出窗口后，下一轮马上恢复跟价。
        Fixtures.setEndsAtIn(ds, auctionId, 300);
        assertEquals(1, proxies().tick(50));
        assertEquals(130, Fixtures.currentPrice(ds, auctionId));
        assertEquals(BIDDER_A_ID, Fixtures.leader(ds, auctionId));
    }

    @Test
    @DisplayName("拍卖结束后收尾：记录是否成交与成交价")
    void proxyRecordsOutcomeWhenAuctionFinishes() {
        String auctionId = createRunningAuction("AI 赢下", 100, 10, 300);
        join(auctionId, bidderTokenB());
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-1", 110).code());
        createProxy(bidderA(), auctionId, 500);
        assertEquals(1, proxies().tick(50));
        assertEquals(BIDDER_A_ID, Fixtures.leader(ds, auctionId));

        Fixtures.expireAuction(ds, auctionId);
        Fixtures.settlementService(ds).settleDue(50);
        assertEquals("FINISHED", Fixtures.status(ds, auctionId));

        int actions = proxies().tick(50);

        assertEquals(1, actions, "结束收尾是一次状态变更");
        Resp list = myProxies(bidderA());
        assertEquals("FINISHED", list.body().at("/data/items/0/status").asText(), list.raw());
        assertTrue(list.body().at("/data/items/0/won").asBoolean(), list.raw());
        assertEquals(120, list.body().at("/data/items/0/finalPrice").asLong(), list.raw());
        assertEquals("FINISHED", list.body().at("/data/items/0/auctionStatus").asText(), list.raw());

        // 再跑不会重复改：FINISHED 不在活状态集合里。
        assertEquals(0, proxies().tick(50));
    }

    @Test
    @DisplayName("预算已触顶的代理在拍卖结束后同样要收尾（否则会永远停在\"预算不够\"）")
    void budgetReachedProxyStillFinishes() {
        String auctionId = createRunningAuction("触顶后收尾", 100, 10, 300);
        join(auctionId, bidderTokenB());
        createProxy(bidderA(), auctionId, 100);
        // 起点就是 100、下一口要 110 > 100：一开始就触顶。
        assertEquals(1, proxies().tick(50));
        assertEquals("BUDGET_REACHED", myProxies(bidderA()).body().at("/data/items/0/status").asText());

        Fixtures.expireAuction(ds, auctionId);
        Fixtures.settlementService(ds).settleDue(50);

        assertEquals(1, proxies().tick(50));
        Resp list = myProxies(bidderA());
        assertEquals("FINISHED", list.body().at("/data/items/0/status").asText(), list.raw());
        assertFalse(list.body().at("/data/items/0/won").asBoolean(), list.raw());
    }

    // ------------------------------------------------------------------ 撤销

    @Test
    @DisplayName("撤销后不再出价；重建同一场会重置那一行")
    void revokeThenRecreateResetsTheSameSlot() {
        String auctionId = createRunningAuction("撤销重建", 100, 10, 300);
        join(auctionId, bidderTokenB());
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-1", 110).code());
        String proxyId = createProxy(bidderA(), auctionId, 500).data("proxyId");
        assertEquals(1, proxies().tick(50));

        Resp revoked = call("POST", PROXIES + "/" + proxyId + "/revoke", bidderA(), null);
        assertEquals(200, revoked.code(), revoked.raw());
        assertEquals("REVOKED", revoked.data("status"));
        assertNotNull(revoked.data("revokedAt"), revoked.raw());

        // 撤销后对手继续加价，代理不再参与。
        assertEquals(200, placeBid(auctionId, bidderTokenB(), "human-2", 130).code());
        assertEquals(0, proxies().tick(50));
        assertEquals(130, Fixtures.currentPrice(ds, auctionId));

        // 重新开启：复用同一行，计数与提醒都清空（唯一键不允许新增）。
        Resp recreated = createProxy(bidderA(), auctionId, 800);
        assertEquals(201, recreated.code(), recreated.raw());
        assertEquals(proxyId, recreated.data("proxyId"), "重建的是同一个代理位");
        assertEquals("PENDING", recreated.data("status"));
        assertEquals(0, recreated.dataLong("bidCount"));
        assertFalse(recreated.body().at("/data/budgetReached").asBoolean(), recreated.raw());
        assertEquals(1, myProxies(bidderA()).body().at("/data/total").asLong(), "不应出现第二行");

        // 重置之后立刻恢复跟价能力。
        assertEquals(1, proxies().tick(50));
        assertEquals(140, Fixtures.currentPrice(ds, auctionId));
    }

    @Test
    @DisplayName("撤销别人的代理：404（不泄露存在性）")
    void revokeForeignProxyIsNotFound() {
        String auctionId = createAuction("越权撤销", 100, 10, 300);
        String proxyId = createProxy(bidderA(), auctionId, 500).data("proxyId");

        Resp resp = call("POST", PROXIES + "/" + proxyId + "/revoke", bidderTokenB(), null);

        assertEquals(404, resp.code(), resp.raw());
        assertCode("NOT_FOUND", resp);
        assertEquals("PENDING", myProxies(bidderA()).body().at("/data/items/0/status").asText(),
                "别人的撤销尝试不得改变原代理状态");
    }

    // ------------------------------------------------------------------ 管理员总览

    @Test
    @DisplayName("管理员总览要求 ADMIN；普通用户 403")
    void adminOverviewRequiresAdmin() {
        String auctionId = createAuction("总览", 100, 10, 300);
        createProxy(bidderA(), auctionId, 500);

        Resp forbidden = call("GET", "/api/v1/admin/agent-proxies", bidderA(), null);
        assertEquals(403, forbidden.code(), forbidden.raw());

        Resp resp = call("GET", "/api/v1/admin/agent-proxies", adminToken(), null);
        assertEquals(200, resp.code(), resp.raw());
        assertEquals(1, resp.body().at("/data/total").asLong(), resp.raw());
        // 管理员需要知道"这是谁的 AI"，因此总览里有归属；用户自己的列表里它只是自己的 id。
        assertEquals(BIDDER_A_ID, resp.body().at("/data/items/0/ownerUserId").asText(), resp.raw());
        assertEquals(auctionId, resp.body().at("/data/items/0/auctionId").asText(), resp.raw());
    }

    // ------------------------------------------------------------------ 预告开拍（D-35）

    @Test
    @DisplayName("带预告时间的拍品是 DRAFT，到点由调度器自动开拍，代理随即进场")
    void scheduledAuctionAutoStartsAndProxyEnters() {
        Resp created = call("POST", "/api/v1/admin/auctions", adminToken(),
                createScheduledAuctionJson("预告场", 100, 10, 300, FAR_FUTURE));
        assertEquals(201, created.code(), created.raw());
        String auctionId = created.body().at("/data/id").asText();
        assertEquals("DRAFT", created.data("status"));
        assertFalse(created.data("startsAt").isEmpty(), created.raw());

        createProxy(bidderA(), auctionId, 500);
        // 还没到点：自动开拍找不到它，代理也只能等。
        assertEquals(0, auctionCommands().startDueScheduled(50));

        // 把预告时间推到过去，等价于"时间到了"。
        Fixtures.exec(ds, "UPDATE auctions SET starts_at = NOW(6) - INTERVAL 5 SECOND WHERE id = ?", auctionId);

        assertEquals(1, auctionCommands().startDueScheduled(50));
        assertEquals("RUNNING", Fixtures.status(ds, auctionId));
        assertNotNull(snapshot(auctionId, bidderA()).data("endsAt"), "开拍后必须有截止时间");

        // 开拍后代理自动进场：起拍价即当前价，下一口 = 起拍价 + 最小加价。
        assertEquals(1, proxies().tick(50));
        assertEquals(110, Fixtures.currentPrice(ds, auctionId));
        assertEquals(BIDDER_A_ID, Fixtures.leader(ds, auctionId));
    }

    @Test
    @DisplayName("没有预告时间的草稿不会被自动开拍（保留手动开始的旧行为）")
    void unscheduledDraftIsNotAutoStarted() {
        String auctionId = createAuction("手动开始", 100, 10, 300);

        assertEquals(0, auctionCommands().startDueScheduled(50));

        assertEquals("DRAFT", Fixtures.status(ds, auctionId));
        assertEquals(200, call("POST", "/api/v1/admin/auctions/" + auctionId + "/start", adminToken(), null).code());
        assertEquals("RUNNING", Fixtures.status(ds, auctionId));
    }

    @Test
    @DisplayName("预告时间必须是未来的 ISO-8601 时刻")
    void invalidStartsAtIsRejected() {
        Resp expired = call("POST", "/api/v1/admin/auctions", adminToken(),
                createScheduledAuctionJson("过去时刻", 100, 10, 300, "2000-01-01T00:00:00Z"));
        assertEquals(400, expired.code(), expired.raw());
        assertCode("VALIDATION_FAILED", expired);

        // 不带时区的写法有歧义（按谁的时区解释？），明确拒绝。
        Resp ambiguous = call("POST", "/api/v1/admin/auctions", adminToken(),
                createScheduledAuctionJson("无时区", 100, 10, 300, "2030-01-01T10:00:00"));
        assertEquals(400, ambiguous.code(), ambiguous.raw());
        assertCode("VALIDATION_FAILED", ambiguous);
    }

    @Test
    @DisplayName("手动开始与自动开拍共用同一路径：重复开始报 INVALID_STATE")
    void autoStartSharesTheManualPath() {
        Resp created = call("POST", "/api/v1/admin/auctions", adminToken(),
                createScheduledAuctionJson("两条路", 100, 10, 300, FAR_FUTURE));
        String auctionId = created.body().at("/data/id").asText();

        assertEquals(200, call("POST", "/api/v1/admin/auctions/" + auctionId + "/start", adminToken(), null).code());

        // 管理员抢先手动开了之后，自动开拍不能再开一次（状态转换条件保证只有一次成功）。
        assertEquals(0, auctionCommands().startDueScheduled(50));
        assertEquals("RUNNING", Fixtures.status(ds, auctionId));
        assertTrue(Fixtures.extensionCount(ds, auctionId) == 0);
    }
}
