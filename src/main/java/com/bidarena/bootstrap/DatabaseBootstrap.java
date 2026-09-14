package com.bidarena.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.exception.FlywayValidateException;
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
 * <p>迁移默认在应用对外提供服务之前同步执行且失败即终止启动：一个金额系统不允许在表结构不确定的状态下开始处理请求。
 * 但“谁去迁移”是可配的，见 {@link #applySchema(DataSource)} 与 {@link MigrateMain}（D-39）。
 */
public final class DatabaseBootstrap {

  private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);

  /** 迁移脚本位置。对应 pom.xml 中把仓库根目录 db/ 打进 classpath 的 &lt;resources&gt; 配置。 */
  private static final String MIGRATION_LOCATION = "classpath:db/migration";

  private static HikariDataSource dataSource;

  private DatabaseBootstrap() {}

  /**
   * 建立连接池并对齐 schema。重复调用返回同一实例。
   *
   * @throws IllegalStateException 必填配置缺失，或迁移/校验失败
   */
  public static synchronized HikariDataSource start() {
    if (dataSource != null) {
      return dataSource;
    }

    HikariDataSource ds = connect();

    try {
      applySchema(ds);
    } catch (RuntimeException e) {
      ds.close();
      throw e;
    }

    dataSource = ds;
    return dataSource;
  }

  /**
   * 只建立连接池，不碰 schema。
   *
   * <p>给“只负责迁移”的进程用（{@link MigrateMain}）：它跑完就该退出，让它在应用对象里
   * 多待一会儿没有意义。把“建池”与“改 schema”拆成两个可分别调用的步骤，
   * 是 {@code MIGRATE_ON_START} 能存在的前提。
   */
  static HikariDataSource connect() {
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
    return ds;
  }

  /**
   * 把 schema 对齐到应用期望的版本。
   *
   * <p>{@code MIGRATE_ON_START}（默认 true）决定本进程做哪一件事：
   *
   * <ul>
   *   <li>{@code true}——执行迁移。单实例部署的默认值，也是加上开关之前的历史行为。
   *   <li>{@code false}——不迁移，只校验：库必须已经由一次性迁移步骤对齐过
   *       （compose 的 migrate 服务 / {@link MigrateMain}），对不上就拒绝启动。
   * </ul>
   *
   * <p>为什么需要后者：多实例同时启动时每个实例都会尝试迁移，在 {@code flyway_schema_history}
   * 上争表锁，抢不到的实例直接启动失败；滚动发布时新实例一启动就改 schema，
   * 而旧实例还在跑旧代码。把迁移拆成只跑一次的步骤后，“改 schema”与“跑应用”不再混在一个进程里。
   */
  static void applySchema(DataSource ds) {
    Flyway flyway = configuredFlyway(ds);
    if (Env.boolOr("MIGRATE_ON_START", true)) {
      migrate(flyway);
    } else {
      verifyOnly(flyway);
    }
  }

  /**
   * 无条件迁移到最新版本。
   *
   * <p>{@link MigrateMain} 用它，因此**不受** {@code MIGRATE_ON_START} 影响：
   * 一次性迁移进程的全部意义就是去迁移，让它跟着那个开关走只会制造“以为迁移了其实没迁”的局面。
   */
  static void migrateToLatest(DataSource ds) {
    migrate(configuredFlyway(ds));
  }

  private static Flyway configuredFlyway(DataSource ds) {
    return Flyway.configure()
        .dataSource(ds)
        .locations(MIGRATION_LOCATION)
        // 显式指定 UTF-8：脚本里有中文注释与种子数据，不能依赖平台默认字符集。
        .encoding(StandardCharsets.UTF_8)
        // 不对非空 schema 自动 baseline：宁可启动失败，也不要悄悄跳过一个没跑过的版本。
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .load();
  }

  private static void migrate(Flyway flyway) {
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

  /**
   * {@code MIGRATE_ON_START=false} 的那条路：不动 schema，但必须证明“库与代码是匹配的”。
   *
   * <p>先查“有没有没执行的迁移”，再交给 {@link Flyway#validate()} 查其余不一致（校验和漂移、
   * 代码回滚后库里多出陌生版本）。前者单独拎出来判，是因为它是最常见的现场——
   * 新代码 + 旧库——而 Flyway 原生的报错只说“validation failed”，运维得自己去翻日志才知道
   * 少跑了几号脚本、接下来该干什么。
   *
   * @throws IllegalStateException 库落后于代码，或校验不通过
   */
  private static void verifyOnly(Flyway flyway) {
    MigrationInfo[] pending = flyway.info().pending();
    if (pending.length > 0) {
      throw new IllegalStateException(
          "数据库结构落后于应用：还有 "
              + pending.length
              + " 个迁移没有执行（最早未执行的是 V"
              + pending[0].getVersion()
              + "）。本次启动 MIGRATE_ON_START=false，不会代为迁移；"
              + "请先跑一次性迁移（java -cp app.jar:libs/* com.bidarena.MigrateMain，或 compose 的 migrate 服务）再启动应用。");
    }

    try {
      flyway.validate();
    } catch (FlywayValidateException e) {
      throw new IllegalStateException(
          "数据库结构与迁移脚本不一致，且已关闭自动迁移（MIGRATE_ON_START=false）：" + e.getMessage(), e);
    }

    MigrationInfo current = flyway.info().current();
    log.info(
        "MIGRATE_ON_START=false：本实例不迁移，只校验（校验通过，当前版本 {}）",
        current == null ? "空库" : current.getVersion());
  }

  /** 运行时取出连接池。必须在 {@link #start()} 之后调用。 */
  public static HikariDataSource dataSource() {
    if (dataSource == null) {
      throw new IllegalStateException("DatabaseBootstrap.start() 尚未调用，无法获取数据源");
    }
    return dataSource;
  }

  private static String read(String key) {
    return Env.read(key);
  }

  private static String required(String key) {
    return Env.required(key);
  }

  /** 读取整数配置，缺省时用 {@code fallback}。系统属性优先于环境变量，便于命令行临时覆盖。 */
  public static int intOr(String key, int fallback) {
    return Env.intOr(key, fallback);
  }
}
