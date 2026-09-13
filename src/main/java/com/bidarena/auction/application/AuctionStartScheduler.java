package com.bidarena.auction.application;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预告开拍的驱动器：把"到点自动开拍"这件事从人工点击里解放出来。
 *
 * <p>为什么必须有它：没有它，AI 代理的"未开始拍卖"就只能等管理员手动点开始，
 * 用户端看到的是"我建了代理，但不知道什么时候会动"。倒计时预告 + 自动开拍让
 * "时间到自动进场"成为一条可解释的因果链，而不是"看管理员什么时候有空"。
 *
 * <p>它只做一件事：问 {@link AuctionCommandService#startDueScheduled(int)} 要一批到点的场次，
 * 由后者走与手动开始完全相同的事务路径。状态的唯一真相始终在数据库行锁里，
 * 这个类本身没有任何"已经处理过哪些场次"的记忆。
 *
 * <p>间隔 1 秒：开拍不像出价那样需要亚秒响应——用户看到的是倒计时归零后的几帧延迟，
 * 而每轮扫描都会碰一次 {@code auctions} 索引，太频繁没有收益。
 */
public class AuctionStartScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionStartScheduler.class);

    private final AuctionCommandService auctionCommands;
    private final long intervalMillis;
    private final int batchSize;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong executedTicks = new AtomicLong();
    private final AtomicLong startedTotal = new AtomicLong();
    private final AtomicLong failedTicks = new AtomicLong();

    private volatile ScheduledExecutorService executor;

    public AuctionStartScheduler(AuctionCommandService auctionCommands, long intervalMillis, int batchSize) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis 必须为正数");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正数");
        }
        this.auctionCommands = auctionCommands;
        this.intervalMillis = intervalMillis;
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auction-start-tick");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("预告开拍调度已启动，间隔 {}ms，每轮上限 {} 场", intervalMillis, batchSize);
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
        log.info("预告开拍调度已停止，共执行 {} 轮，开拍 {} 场，异常 {} 轮",
                executedTicks.get(), startedTotal.get(), failedTicks.get());
    }

    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int started = auctionCommands.startDueScheduled(batchSize);
            executedTicks.incrementAndGet();
            if (started > 0) {
                startedTotal.addAndGet(started);
            }
        } catch (Throwable t) {
            // 与结算扫描器同样的理由：异常逃出调度方法会让这个线程静默消失，
            // 之后所有预告场次都不再自动开拍，而表面上服务一切正常。
            failedTicks.incrementAndGet();
            log.error("预告开拍调度整轮失败，调度器保持存活并将重试", t);
        } finally {
            running.set(false);
        }
    }

    public long executedTicks() {
        return executedTicks.get();
    }

    public long startedTotal() {
        return startedTotal.get();
    }

    public long failedTicks() {
        return failedTicks.get();
    }
}
