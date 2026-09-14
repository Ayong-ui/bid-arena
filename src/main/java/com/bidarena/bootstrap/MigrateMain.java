package com.bidarena.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次性迁移入口：把库迁移到最新版本，然后退出（D-39）。
 *
 * <p>用法：{@code java -cp "app.jar:libs/*" com.bidarena.bootstrap.MigrateMain}。
 * {@code docker-compose.yml} 的 {@code migrate} 服务就是用它（覆盖 entrypoint 即可，
 * 与 backend 镜像同一个 jar，不需要第二份构建产物）。
 *
 * <h2>为什么要把迁移从应用启动里摘出来</h2>
 * 多实例同时启动时，每个实例都会尝试迁移并在 {@code flyway_schema_history} 上争表锁，
 * 抢不到的实例直接启动失败；滚动发布时新实例一启动就改 schema，旧实例还在跑旧代码。
 * 把这些交给一个只跑一次的进程之后，应用实例只做校验（{@code MIGRATE_ON_START=false}），
 * 于是“改 schema”与“跑应用”在时间上被显式分开——这正是排队等待、失败即停的前提。
 *
 * <h2>退出码</h2>
 * 0 = 迁移成功（含“本来就是最新”）；1 = 配置缺失、连不上库或迁移失败。
 * 刻意不吞异常也不返回 0：编排与 CI 要靠退出码决定要不要继续放流量。
 */
public final class MigrateMain {

  private static final Logger log = LoggerFactory.getLogger(MigrateMain.class);

  private MigrateMain() {}

  public static void main(String[] args) {
    System.exit(run());
  }

  /** 迁移一次并返回退出码。抽出来是为了能在测试里直接调用，不必启动第二个 JVM。 */
  static int run() {
    HikariDataSource ds = null;
    try {
      ds = DatabaseBootstrap.connect();
      // 无条件迁移：这里不看 MIGRATE_ON_START，否则一个忘配的环境变量就能让
      // “迁移步骤”变成什么都不做的空转，而退出码还是 0。
      DatabaseBootstrap.migrateToLatest(ds);
      return 0;
    } catch (RuntimeException e) {
      log.error("一次性迁移失败，应用不应在此时被启动：{}", e.getMessage(), e);
      return 1;
    } finally {
      if (ds != null) {
        ds.close();
      }
    }
  }
}
