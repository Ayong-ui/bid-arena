package com.bidarena.agentaccess.application;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 托管 AI 代理的驱动器。
 *
 * <p>与 {@code SettlementScheduler} 同构，理由也一样：<b>自己不保存任何状态</b>，
 * 每轮回数据库查"现在有哪些活着的代理、它们的拍卖进行到哪了"。进程重启后
 * 内存里没有任何需要恢复的东西，第一轮扫描就会把"该跟价但没人管"的补上。
 *
 * <p>间隔默认 500ms：代理是被"别人加价"驱动的，用户看到的落后时间就是两个间隔。
 * 1 秒会让人觉得 AI 反应迟钝，100ms 则在没有对手的空场上浪费四次数据库往返。
 *
 * <p>三个必须踩对的点与结算扫描器完全相同（详见 {@code SettlementScheduler} 的类注释）：
 * 捕获 {@link Throwable}（否则调度线程静默死亡）、用 {@code scheduleWithFixedDelay}
 * （避免慢轮次堆积）、CAS 防重入（测试或人工直接调 {@link #tick()} 时可能与调度线程相撞）。
 *
 * <p>多实例并行是安全的：出价的竞争由 {@code BidService} 的拍卖行锁收敛，
 * 重复提交由 {@code requestId} 幂等键与 {@code bid_requests} 表收敛。
 */
public class AgentProxyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentProxyScheduler.class);

    private final AgentProxyService service;
    private final long intervalMillis;
    private final int batchSize;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong executedTicks = new AtomicLong();
    private final AtomicLong actions = new AtomicLong();
    private final AtomicLong failedTicks = new AtomicLong();

    private volatile ScheduledExecutorService executor;

    public AgentProxyScheduler(AgentProxyService service, long intervalMillis, int batchSize) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis 必须为正数");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正数");
        }
        this.service = service;
        this.intervalMillis = intervalMillis;
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-proxy-tick");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("AI 代理调度已启动，间隔 {}ms，每轮上限 {} 个", intervalMillis, batchSize);
    }

    public synchronized void stop() {
        ScheduledExecutorService current = executor;
        executor = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(intervalMillis + 5_000L, TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("AI 代理调度已停止，共执行 {} 轮，动作 {} 次，异常 {} 轮",
                executedTicks.get(), actions.get(), failedTicks.get());
    }

    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int performed = service.tick(batchSize);
            executedTicks.incrementAndGet();
            if (performed > 0) {
                actions.addAndGet(performed);
            }
        } catch (Throwable t) {
            failedTicks.incrementAndGet();
            log.error("AI 代理调度整轮失败，调度器保持存活并将重试", t);
        } finally {
            running.set(false);
        }
    }

    public long executedTicks() {
        return executedTicks.get();
    }

    public long actions() {
        return actions.get();
    }

    public long failedTicks() {
        return failedTicks.get();
    }
}
