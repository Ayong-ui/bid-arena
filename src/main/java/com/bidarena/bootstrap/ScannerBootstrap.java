package com.bidarena.bootstrap;

import com.bidarena.agentaccess.application.AgentProxyScheduler;
import com.bidarena.auction.application.AuctionStartScheduler;
import com.bidarena.auction.application.SettlementScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组合根里"本实例负责哪些后台扫描器"的那一段（D-40）。
 *
 * <h2>为什么需要开关</h2>
 * 三个扫描器都是"每轮回数据库查一遍该做什么"，因此**能**多实例同时跑
 * （重复触发由行锁与唯一约束收敛）。但"能跑"不等于"应该跑"：多实例部署时，
 * 谁都不希望 N 个实例各自每 500ms 去扫同一张表；滚动发布时也常有"先起一批只对外服务的
 * 实例"的诉求。开关把这个决定变成显式配置，而不是靠运维去猜"这个进程到底会不会自己结算"。
 *
 * <h2>为什么是三个开关，而不是一个"worker 模式"</h2>
 * 三件事的职责与代价差别很大：结算与自动开拍是"到点必须发生"的（关掉它们业务就停摆），
 * 托管代理是"每 500ms 跟一次价"的高频抖动（可能只想在特定实例上关掉）。
 * 更关键的是：开关本身是**排查信息**——出故障时第一个要看的就是"哪个实例负责什么"，
 * 一个总开关会把这层信息压成"不知道"。
 *
 * <h2>缺省全开</h2>
 * 单实例部署与本地开发**零改动**：不设任何环境变量，行为与引入本类之前完全一致。
 * 这与 {@code MIGRATE_ON_START}（D-39）是同一口径——保守的缺省值不能改变既有部署的行为。
 *
 * <h2>它没有解决什么</h2>
 * 它只表达"本实例不跑"，不表达"别人一定在跑"。真正的多实例自动分工（选主/分片）仍未实现
 * （Redis 锁一类方案已被明确拒绝，见 DECISIONS）；所以关掉开关的实例必须由部署者保证
 * 还有别的实例在跑，否则到期结算、预告开拍、托管代理都会**静默地**不再发生。
 * 这一点在启动日志里会以 WARN 反复提醒。
 */
public final class ScannerBootstrap {

    /** 到期结算扫描器是否由本实例负责。缺省 true。 */
    public static final String SETTLE_SCHEDULER_ENABLED = "SETTLE_SCHEDULER_ENABLED";

    /** 预告开拍扫描器是否由本实例负责。缺省 true。 */
    public static final String AUCTION_START_SCHEDULER_ENABLED = "AUCTION_START_SCHEDULER_ENABLED";

    /** 托管 AI 代理调度器是否由本实例负责。缺省 true。 */
    public static final String AGENT_PROXY_SCHEDULER_ENABLED = "AGENT_PROXY_SCHEDULER_ENABLED";

    private static final Logger log = LoggerFactory.getLogger(ScannerBootstrap.class);

    /** 扫描器的稳定名字：同时用于启动日志、{@link Started} 与测试断言。 */
    private static final String SETTLEMENT = "settlement";

    private static final String AUCTION_START = "auction-start";
    private static final String AGENT_PROXY = "agent-proxy";

    /**
     * 扫描器名单：**一处**定义"名字 ↔ 开关"的对应关系。
     *
     * <p>把它抽成列表而不是在三段 {@code if} 里各写一遍，是因为"漏掉一个开关"
     * 与"多写一个开关"都必须只有一个地方会错；测试也可以直接对着这份名单断言缺省全开。
     */
    private static final List<Switch> SWITCHES = List.of(
            new Switch(SETTLEMENT, SETTLE_SCHEDULER_ENABLED),
            new Switch(AUCTION_START, AUCTION_START_SCHEDULER_ENABLED),
            new Switch(AGENT_PROXY, AGENT_PROXY_SCHEDULER_ENABLED));

    private ScannerBootstrap() {}

    private record Switch(String name, String key) {}

    /**
     * 启动结果：本实例实际负责的扫描器、按开关跳过的扫描器，以及对应的停机动作。
     *
     * <p>把停机动作一并返回而不是在 {@code Application} 里逐个登记钩子：三段几乎一样的
     * {@code addShutdownHook} 最容易出现"新加一个扫描器却忘了加钩子"，测试也能直接断言
     * "关掉的扫描器没有停机动作"（即根本没有被创建）。
     */
    public record Started(List<String> started, List<String> skipped, List<Runnable> stoppers) {

        public Started {
            started = List.copyOf(started);
            skipped = List.copyOf(skipped);
            stoppers = List.copyOf(stoppers);
        }

        /** 停止本实例启动过的扫描器（与启动相反的顺序）。允许重复调用。 */
        public void stopAll() {
            for (int i = stoppers.size() - 1; i >= 0; i--) {
                stoppers.get(i).run();
            }
        }
    }

    /**
     * 按开关启动本实例负责的扫描器。
     *
     * @param services 组合根对象图；扫描器只从中取各自需要的服务
     */
    public static Started start(Services services) {
        // 每个扫描器的"怎么建、怎么停"只在这里出现一次。构造参数仍由各自决定，
        // 因为它们的间隔键名与默认值本就不同。
        Map<String, Supplier<Runnable>> starters = Map.of(
                SETTLEMENT, () -> startSettlement(services),
                AUCTION_START, () -> startAuctionStart(services),
                AGENT_PROXY, () -> startAgentProxy(services));

        List<String> started = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Runnable> stoppers = new ArrayList<>();

        for (Switch sw : SWITCHES) {
            String raw = Env.read(sw.key());
            if (Env.boolOr(sw.key(), true)) {
                stoppers.add(starters.get(sw.name()).get());
                started.add(sw.name());
            } else {
                skipped.add(sw.name());
                log.warn("后台扫描器「{}」已按 {}={} 关闭：本实例不承担这项后台任务；"
                                + "多实例部署必须保证至少有一个实例开着它，否则相关的到期动作会静默停摆",
                        sw.name(), sw.key(), raw);
            }
        }

        // 一行说清"本实例负责什么"。多实例排查时这是第一手信息：
        // grep "本实例负责的后台扫描器" 就能确认每个进程的分工。
        log.info("本实例负责的后台扫描器：{}；按开关关闭：{}",
                started.isEmpty() ? "无" : started, skipped.isEmpty() ? "无" : skipped);
        return new Started(started, skipped, stoppers);
    }

    /**
     * 只读三个开关后的策略结果（不启动任何线程）。
     *
     * <p>给单元测试用：开关是纯配置，验证它不需要真的起线程、连数据库。
     */
    static List<String> enabledScanners() {
        List<String> enabled = new ArrayList<>();
        for (Switch sw : SWITCHES) {
            if (Env.boolOr(sw.key(), true)) {
                enabled.add(sw.name());
            }
        }
        return List.copyOf(enabled);
    }

    private static Runnable startSettlement(Services services) {
        SettlementScheduler scheduler = new SettlementScheduler(
                services.settlement,
                Env.intOr("SETTLE_SCAN_INTERVAL_MS", 1000),
                Env.intOr("SETTLE_BATCH_SIZE", 50));
        scheduler.start();
        return scheduler::stop;
    }

    private static Runnable startAuctionStart(Services services) {
        AuctionStartScheduler scheduler = new AuctionStartScheduler(
                services.auctionCommands,
                Env.intOr("AUCTION_START_SCAN_INTERVAL_MS", 1000),
                Env.intOr("AUCTION_START_BATCH_SIZE", 50));
        scheduler.start();
        return scheduler::stop;
    }

    private static Runnable startAgentProxy(Services services) {
        AgentProxyScheduler scheduler = new AgentProxyScheduler(
                services.agentProxies,
                Env.intOr("AGENT_PROXY_TICK_INTERVAL_MS", 500),
                Env.intOr("AGENT_PROXY_BATCH_SIZE", 50));
        scheduler.start();
        return scheduler::stop;
    }
}
