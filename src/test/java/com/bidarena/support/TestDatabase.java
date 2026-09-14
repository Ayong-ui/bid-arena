package com.bidarena.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * 集成测试的数据库入口。
 *
 * <h2>为什么必须是真实 MySQL</h2>
 * 原文明确要求"关键并发与结算测试必须使用真实 MySQL 或等价的容器化集成环境，
 * 不能全部由内存 Mock 代替"。这不是形式要求：本项目的正确性建立在
 * InnoDB 的行锁、唯一键冲突与 {@code REPEATABLE READ} 行为之上，
 * 用内存假实现替换它们，测的就不再是同一套语义，结论毫无意义。
 *
 * <h2>连接从哪来</h2>
 * 优先使用环境变量 {@code BID_ARENA_TEST_DB_URL} / {@code _USER} / {@code _PASSWORD}，
 * 允许指向任意已存在的 MySQL（评审机器上通常比 Docker 更省事）。
 * 未设置时由 {@link #provideDataSource()} 的调用方决定回退策略。
 *
 * <h2>安全护栏</h2>
 * {@link #wipe()} 会清空所有业务表。为避免误伤开发库，URL 指向 {@code bid_arena}
 * 时直接拒绝启动——测试库名必须与开发库不同。
 */
public final class TestDatabase {

    private static final List<String> TABLES_IN_WIPE_ORDER = List.of(
            "ledger_entries",
            "bids",
            "bid_requests",
            "settlements",
            "auction_participants",
            "auctions",
            "agent_token_auctions",
            "agent_tokens",
            "agent_proxies",
            "wallets",
            "users");

    private static DataSource dataSource;

    private TestDatabase() {}

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = create();
        }
        return dataSource;
    }

    /**
     * 取出 JDBC URL 里的库名（忽略查询串与可选的尾斜杠）。取不到时返回空串。
     */
    private static String databaseName(String bareUrl) {
        String trimmed = bareUrl.endsWith("/") ? bareUrl.substring(0, bareUrl.length() - 1) : bareUrl;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? "" : trimmed.substring(slash + 1);
    }

    /**
     * 开发库名：从 {@code DB_URL} 推导，而不是写死 {@code bid_arena}。
     *
     * <p>写死会在别人改了 {@code MYSQL_DATABASE} 后静默失效：测试库指向新开发库时不再被拦下，
     * 而 {@link #wipe()} 会把开发数据真的清掉。没配 {@code DB_URL}（测试环境常见）时退回
     * {@code .env.example} 的默认库名。
     */
    private static String devDatabaseName() {
        String devUrl = System.getenv("DB_URL");
        if (devUrl == null || devUrl.isBlank()) {
            return "bid_arena";
        }
        String name = databaseName(devUrl.split("\\?")[0]);
        return name.isEmpty() ? "bid_arena" : name;
    }

    private static DataSource create() {
        String url = require("BID_ARENA_TEST_DB_URL");
        String user = require("BID_ARENA_TEST_DB_USER");
        String password = System.getenv("BID_ARENA_TEST_DB_PASSWORD");

        String bare = url.split("\\?")[0];
        String testDb = databaseName(bare);
        String devDb = devDatabaseName();
        if (testDb.equals(devDb)) {
            throw new IllegalStateException(
                    "BID_ARENA_TEST_DB_URL 指向了开发库 " + devDb + "。测试会清空业务表，"
                            + "请改用独立库（例如 " + devDb + "_test）。当前值: " + url);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(32);
        config.setPoolName("bid-arena-test-pool");
        // 并发测试会同时占用与线程数相当的连接，连接获取超时必须放宽，
        // 否则"连接池等待超时"会伪装成并发失败，掩盖真正的结论。
        config.setConnectionTimeout(20_000);

        HikariDataSource ds = new HikariDataSource(config);

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .encoding(StandardCharsets.UTF_8)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .load()
                .migrate();

        return ds;
    }

    /**
     * 清空所有业务表，使每个测试从确定状态出发。
     *
     * <p>用 {@code TRUNCATE} 而非 {@code DELETE}：它同时重置自增计数，
     * 使断言里的 id 不依赖前一个测试跑过什么。
     * 关掉外键检查是为了不必维护删除顺序（TRUNCATE 本身会隐式提交，无法放在事务里）。
     */
    public static void wipe() {
        try (Connection conn = dataSource().getConnection(); Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String table : TABLES_IN_WIPE_ORDER) {
                    st.execute("TRUNCATE TABLE " + table);
                }
            } finally {
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清空测试库失败: " + e.getMessage(), e);
        }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 " + name + "。集成测试需要真实 MySQL，"
                            + "请设置 BID_ARENA_TEST_DB_URL / _USER / _PASSWORD 指向一个独立测试库。");
        }
        return value;
    }
}
