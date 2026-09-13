package com.bidarena.auction.application;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 到期自动结算的驱动器。
 *
 * <p>它自身**不保存任何与拍卖有关的状态**：每一轮都从数据库查"已到期且仍为 RUNNING"的拍卖。
 * 这是"服务重启后必须能继续结算已到期但未结算的拍卖"最省事的实现方式——
 * 重启后内存里没有任何东西需要恢复，第一轮扫描就会把没结的补上。
 *
 * <h2>三个容易写错的地方</h2>
 * <ol>
 *   <li><b>必须捕获 {@link Throwable}</b>。{@link ScheduledExecutorService} 在任务抛出异常后会
 *       **静默取消后续所有执行**——不报错、不重启，定时结算就此永远停摆，而表面上服务一切正常。
 *       这类缺陷在上线后极难发现，所以这里连 {@code Error} 一起兜住。</li>
 *   <li><b>用 {@code scheduleWithFixedDelay} 而不是 {@code scheduleAtFixedRate}</b>。
 *       前者保证"上一轮结束后再等固定间隔"，一轮变慢不会堆积出多轮重叠执行；
 *       后者会在任务变慢时追赶式地连续触发。</li>
 *   <li><b>不可重入</b>。即使调度器本身是单线程，直接调 {@link #tick()}（测试、管理接口）也可能与
 *       调度线程撞上，用 CAS 挡住。</li>
 * </ol>
 *
 * <p>多实例同时运行是允许的：重复触发由 {@link SettlementService} 的拍卖行锁与
 * {@code settlements} 主键收敛为一次结算。
 */
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementService settlementService;
    private final long intervalMillis;
    private final int batchSize;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong executedTicks = new AtomicLong();
    private final AtomicLong settledAuctions = new AtomicLong();
    private final AtomicLong failedTicks = new AtomicLong();

    private volatile ScheduledExecutorService executor;

    public SettlementScheduler(SettlementService settlementService, long intervalMillis, int batchSize) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis 必须为正数");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正数");
        }
        this.settlementService = settlementService;
        this.intervalMillis = intervalMillis;
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "settlement-scan");
            t.setDaemon(true);
            return t;
        });
        // 立即跑一轮：进程刚起来时可能已经有大量历史到期拍卖，不必再等一个间隔。
        executor.scheduleWithFixedDelay(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("到期结算扫描已启动，间隔 {}ms，每轮上限 {} 场", intervalMillis, batchSize);
    }

    /** 停止并等待当前轮结束。允许重复调用，允许在未启动时调用。 */
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
        log.info("到期结算扫描已停止，共执行 {} 轮，结算 {} 场，异常 {} 轮",
                executedTicks.get(), settledAuctions.get(), failedTicks.get());
    }

    /** 执行一轮扫描。异常一律吞掉并计数，绝不让它逃逸到调度线程。 */
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            log.debug("上一轮结算尚未结束，跳过本轮");
            return;
        }
        try {
            int settled = settlementService.settleDue(batchSize);
            executedTicks.incrementAndGet();
            if (settled > 0) {
                settledAuctions.addAndGet(settled);
                log.info("本轮结算 {} 场拍卖", settled);
            }
        } catch (Throwable t) {
            failedTicks.incrementAndGet();
            log.error("结算扫描整轮失败，调度器保持存活并将重试", t);
        } finally {
            running.set(false);
        }
    }

    public long executedTicks() {
        return executedTicks.get();
    }

    public long settledAuctions() {
        return settledAuctions.get();
    }

    public long failedTicks() {
        return failedTicks.get();
    }
}
