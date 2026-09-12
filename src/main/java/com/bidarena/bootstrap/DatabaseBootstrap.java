package com.bidarena.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组合根的第一段：建立连接池并执行数据库迁移。
 *
 * <p>这里是全项目中唯一读取环境变量、构造具体实现（HikariCP、Flyway）的位置。
 * 其余各层通过构造函数接收依赖，因此 adapter 不会反向依赖 bootstrap —— 该方向由 ArchUnit 规则守卫，
 * 见 {@code DESIGN.md} §2.4。
 *
 * <p>迁移在应用对外提供服务之前同步执行且失败即终止启动：一个金额系统不允许在表结构不确定的状态下开始处理请求。
 */
public final class DatabaseBootstrap {

  private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);

  /** 迁移脚本位置。对应 pom.xml 中把仓库根目录 db/ 打进 classpath 的 &lt;resources&gt; 配置。 */
  private static final String MIGRATION_LOCATION = "classpath:db/migration";

  private static HikariDataSource dataSource;

  private DatabaseBootstrap() {}

  /**
   * 建立连接池并迁移到最新版本。重复调用返回同一实例。
   *
   * @throws IllegalStateException 必填配置缺失，或迁移失败
   */
  public static synchronized HikariDataSource start() {
    if (dataSource != null) {
      return dataSource;
    }

    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(required("DB_URL"));
    hikari.setUsername(required("DB_USER"));
    hikari.setPassword(required("DB_PASSWORD"));
    hikari.setPoolName("bid-arena-pool");
    hikari.setMaximumPoolSize(intOr("DB_POOL_SIZE", 16));
    hikari.setMinimumIdle(intOr("DB_POOL_MIN_IDLE", 4));
    // 出价事务会持有行锁，等待时间可能达到秒级；取连接的超时必须大于事务最长持锁时间，
    // 否则压力下会先被连接池拒绝，掩盖掉真正的锁竞争现象。
    hikari.setConnectionTimeout(intOr("DB_CONNECTION_TIMEOUT_MS", 10_000));
    // 事务边界由业务代码显式控制（一个出价 = 一个事务），池不应替我们包一层。
    hikari.setAutoCommit(true);
    // HikariCP 要求显式设置 maxLifetime；需小于 MySQL 的 wait_timeout。
    hikari.setMaxLifetime(intOr("DB_MAX_LIFETIME_MS", 1_200_000));

    HikariDataSource ds = new HikariDataSource(hikari);
    log.info("已建立连接池 {} -> {}", hikari.getPoolName(), hikari.getJdbcUrl());

    try {
      migrate(ds);
    } catch (RuntimeException e) {
      ds.close();
      throw e;
    }

    dataSource = ds;
    return dataSource;
  }

  /** 运行时取出连接池。必须在 {@link #start()} 之后调用。 */
  public static HikariDataSource dataSource() {
    if (dataSource == null) {
      throw new IllegalStateException("DatabaseBootstrap.start() 尚未调用，无法获取数据源");
    }
    return dataSource;
  }

  private static void migrate(HikariDataSource ds) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(ds)
            .locations(MIGRATION_LOCATION)
            // 显式指定 UTF-8：脚本里有中文注释与种子数据，不能依赖平台默认字符集。
            .encoding(StandardCharsets.UTF_8)
            // 不对非空 schema 自动 baseline：宁可启动失败，也不要悄悄跳过一个没跑过的版本。
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .load();

    MigrateResult result = flyway.migrate();

    // 注意：没执行任何迁移时 result.targetSchemaVersion 为 null，
    // 不能直接拿它当日志里的“当前版本”，否则会打出“当前版本 null”这种误导信息。
    // 真正的当前版本应当从 flyway.info() 读。
    if (result.migrationsExecuted == 0) {
      MigrationInfo current = flyway.info().current();
      log.info("数据库结构已是最新版本（当前版本 {}）", current == null ? "空库" : current.getVersion());
    } else {
      log.info(
          "已执行 {} 个迁移，schema 版本 {} -> {}",
          result.migrationsExecuted,
          result.initialSchemaVersion == null ? "空库" : result.initialSchemaVersion,
          result.targetSchemaVersion);
      result.migrations.forEach(m -> log.info("  已应用 {} - {}", m.version, m.description));
    }
  }

  /** 读取配置：系统属性优先于环境变量，便于 {@code mvn exec:java -DDB_URL=...} 临时覆盖。 */
  private static String read(String key) {
    String value = System.getProperty(key);
    if (value == null || value.isBlank()) {
      value = System.getenv(key);
    }
    return (value == null || value.isBlank()) ? null : value;
  }

  private static String required(String key) {
    String value = read(key);
    if (value == null) {
      throw new IllegalStateException(
          "缺少必填配置 "
              + key
              + "。请从 .env.example 复制出 .env 并加载环境变量后重试（见 README 的快速启动）。");
    }
    return value;
  }

  private static int intOr(String key, int fallback) {
    String value = read(key);
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalStateException("配置 " + key + " 不是合法整数：" + value, e);
    }
  }
}
