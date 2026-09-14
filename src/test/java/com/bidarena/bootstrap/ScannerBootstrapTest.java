package com.bidarena.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.bidarena.support.Fixtures;
import com.bidarena.support.TestDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 后台扫描器开关（D-40）。
 *
 * <h2>为什么既有纯逻辑用例，又有动真库的用例</h2>
 * 开关本身是**纯配置**：缺省、显式关闭、拼错取值，这些不需要数据库也不需要线程，
 * 用 {@link ScannerBootstrap#enabledScanners()} 直接断言。但"开关真的能挡住事"这件事，
 * 只有在真实 MySQL 上才看得见——所以又用一个"同一场到期拍卖，关掉时不动、打开时结算"
 * 的对照用例兜底。只测策略会让"读对了开关却照样启动"这种缺陷溜过去；
 * 只测行为则解释不了缺省与错误取值，两者缺一不可。
 */
@DisplayName("后台扫描器开关（D-40）")
class ScannerBootstrapTest {

    private static final String AUCTION = "auc_scanner";
    private static final String SETTLE_INTERVAL = "SETTLE_SCAN_INTERVAL_MS";

    /** 本类会改的系统属性原值；用例结束逐个还原，避免污染同 JVM 的其它测试。 */
    private static final List<String> TOUCHED_KEYS = List.of(
            ScannerBootstrap.SETTLE_SCHEDULER_ENABLED,
            ScannerBootstrap.AUCTION_START_SCHEDULER_ENABLED,
            ScannerBootstrap.AGENT_PROXY_SCHEDULER_ENABLED,
            SETTLE_INTERVAL);

    private final DataSource ds = TestDatabase.dataSource();
    private final Map<String, String> priorValues = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        TestDatabase.wipe();
        priorValues.clear();
        for (String key : TOUCHED_KEYS) {
            priorValues.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        Env.resetCacheForTest();
    }

    @AfterEach
    void restoreConfig() {
        for (Map.Entry<String, String> entry : priorValues.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        Env.resetCacheForTest();
    }

    // ---------------------------- 纯策略 ----------------------------

    @Test
    @DisplayName("缺省：三个扫描器都归本实例，单实例部署行为不变")
    void defaultsToAllOn() {
        assertEquals(List.of("settlement", "auction-start", "agent-proxy"), ScannerBootstrap.enabledScanners());
    }

    @Test
    @DisplayName("显式关闭只影响对应那一项，其余仍开")
    void explicitOffOnlySkipsThatScanner() {
        setSwitch(ScannerBootstrap.AGENT_PROXY_SCHEDULER_ENABLED, "false");

        assertEquals(List.of("settlement", "auction-start"), ScannerBootstrap.enabledScanners());
    }

    @Test
    @DisplayName("取值拼错直接启动失败，而不是猜一个布尔值继续")
    void malformedValueFailsFast() {
        setSwitch(ScannerBootstrap.SETTLE_SCHEDULER_ENABLED, "no");

        IllegalStateException e =
                assertThrows(IllegalStateException.class, ScannerBootstrap::enabledScanners);
        assertTrue(e.getMessage().contains(ScannerBootstrap.SETTLE_SCHEDULER_ENABLED), e.getMessage());
    }

    @Test
    @DisplayName(".env.example 里必须写到每一个开关（新增开关漏文档会让测试变红）")
    void everySwitchIsDocumented() throws IOException {
        Path env = Path.of(".env.example");
        assertTrue(Files.exists(env), "找不到 " + env.toAbsolutePath() + "（该测试须在仓库根目录运行）");
        String text = Files.readString(env, StandardCharsets.UTF_8);

        for (String key : TOUCHED_KEYS) {
            if (key.equals(SETTLE_INTERVAL)) {
                continue;
            }
            assertTrue(text.contains(key + "="), ".env.example 缺少开关 " + key);
        }
    }

    // ---------------------------- 真实行为 ----------------------------

    @Test
    @DisplayName("三个开关全关：start() 不创建任何扫描器，也没有停机动作")
    void allOffStartsNothing() {
        setSwitch(ScannerBootstrap.SETTLE_SCHEDULER_ENABLED, "false");
        setSwitch(ScannerBootstrap.AUCTION_START_SCHEDULER_ENABLED, "false");
        setSwitch(ScannerBootstrap.AGENT_PROXY_SCHEDULER_ENABLED, "false");

        ScannerBootstrap.Started started = ScannerBootstrap.start(Fixtures.services(ds));

        assertEquals(List.of(), started.started());
        assertEquals(List.of("settlement", "auction-start", "agent-proxy"), started.skipped());
        assertEquals(List.of(), started.stoppers());
        assertDoesNotThrow(started::stopAll);
    }

    @Test
    @DisplayName("关掉结算扫描：到期场次不会被自动结算；打开后被结算")
    void settlementSchedulerHonoursTheSwitch() throws InterruptedException {
        givenExpiredAuctionWithOneBid();

        // 1) 关掉：等足够跑好几轮的时间（若被错误启动，500ms 一轮早该结算），应毫无变化。
        setSwitch(ScannerBootstrap.SETTLE_SCHEDULER_ENABLED, "false");
        setSwitch(ScannerBootstrap.AUCTION_START_SCHEDULER_ENABLED, "false");
        setSwitch(ScannerBootstrap.AGENT_PROXY_SCHEDULER_ENABLED, "false");
        ScannerBootstrap.Started off = ScannerBootstrap.start(Fixtures.services(ds));
        try {
            Thread.sleep(1500);
            assertEquals("RUNNING", Fixtures.status(ds, AUCTION), "开关关掉时不应被自动结算");
            assertNull(Fixtures.settlement(ds, AUCTION), "开关关掉时不应产生成交记录");
        } finally {
            off.stopAll();
        }

        // 2) 打开：同一场拍卖在几个轮次内被结算，证明上一步的"没结算"确实是开关挡住的，
        //    而不是场景本身就不会结算（这正是"只测关、不测开"最容易造成的假绿）。
        setSwitch(ScannerBootstrap.SETTLE_SCHEDULER_ENABLED, "true");
        System.setProperty(SETTLE_INTERVAL, "200");
        Env.resetCacheForTest();
        ScannerBootstrap.Started on = ScannerBootstrap.start(Fixtures.services(ds));
        try {
            awaitStatus("FINISHED", 8000);
            assertEquals("u_1|110|TIMEOUT", Fixtures.settlement(ds, AUCTION));
        } finally {
            on.stopAll();
        }
        assertEquals(List.of("settlement"), on.started(), "其余两项被显式关掉，本步只验证结算扫描");
    }

    // ---------------------------- 夹具与工具 ----------------------------

    /** 一个真人用户加入并出价 110，然后把截止时间推到 1 秒前（到期未结算）。 */
    private void givenExpiredAuctionWithOneBid() {
        Fixtures.user(ds, "u_1", 1000);
        Fixtures.draftAuction(ds, AUCTION, 100, 10, 600);
        Fixtures.startAuction(ds, AUCTION, 600);
        Fixtures.join(ds, AUCTION, "u_1");
        Fixtures.bidService(ds).placeBid(AUCTION, "u_1", 110, "r1");
        Fixtures.expireAuction(ds, AUCTION);
    }

    private void awaitStatus(String expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(Fixtures.status(ds, AUCTION))) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等待超时：期望状态 " + expected + "，实际 " + Fixtures.status(ds, AUCTION));
    }

    private static void setSwitch(String key, String value) {
        System.setProperty(key, value);
        Env.resetCacheForTest();
    }
}
