package com.bidarena.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bidarena.support.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code MIGRATE_ON_START} 在真实 MySQL 上的行为（D-39）。
 *
 * <h2>为什么这个测试要动库</h2>
 * 开关的全部价值都体现在“库与代码对不上”的那一刻，而那一刻用内存假实现造不出来：
 * 它需要一个真的落后的 schema。所以这里把 V6 从库里退回去
 * （删历史行，并删掉 V6 真正建出来的索引、列与表），制造出“新代码 + 旧库”的现场，
 * 跑完在 {@code finally} 里把共享测试库补回来。
 *
 * <h2>它挡的是哪一类事故</h2>
 * 发布时迁移步骤没跑、或失败了却没人看退出码：应用若照旧自己迁移，这件事会被掩盖成
 * “重启一下就好了”；应用若只校验，就会在发布阶段当场拒绝启动，而不是把问题留到某个用户的出价请求上。
 */
@DisplayName("MIGRATE_ON_START：迁移还是只校验")
class MigrationToggleTest {

  private static final String KEY = "MIGRATE_ON_START";

  private final DataSource ds = TestDatabase.dataSource();

  @AfterEach
  void restoreDefaultSetting() {
    System.clearProperty(KEY);
    Env.resetCacheForTest();
  }

  @Test
  @DisplayName("库已是最新时：只校验不报错，也不改动任何东西")
  void verifyOnlyAcceptsUpToDateSchema() throws SQLException {
    int before = appliedMaxVersion();
    withMigrateOnStart(false, () -> DatabaseBootstrap.applySchema(ds));
    assertEquals(before, appliedMaxVersion());
  }

  @Test
  @DisplayName("库落后于代码时：只校验会拒绝启动，且是真的没迁移；开关打开则补齐")
  void verifyOnlyRefusesWhenSchemaIsBehind() throws SQLException {
    rollbackToV5();
    try {
      IllegalStateException e =
          assertThrows(
              IllegalStateException.class,
              () -> withMigrateOnStart(false, () -> DatabaseBootstrap.applySchema(ds)));
      assertTrue(e.getMessage().contains("落后于应用"), e.getMessage());
      assertTrue(e.getMessage().contains("MIGRATE_ON_START=false"), e.getMessage());
      // 关键断言：拒绝启动 ≠ 偷偷迁移。少了这一条，“只校验”退化成“照旧迁移”也能全绿。
      assertEquals(5, appliedMaxVersion(), "库应当仍停在 V5");
    } finally {
      withMigrateOnStart(true, () -> DatabaseBootstrap.applySchema(ds));
    }
    assertEquals(6, appliedMaxVersion(), "开关打开后应当补齐到 V6");
  }

  @Test
  @DisplayName("一次性迁移入口不看开关：MIGRATE_ON_START=false 时它照样迁移")
  void migrateToLatestIgnoresTheSwitch() throws SQLException {
    rollbackToV5();
    try {
      withMigrateOnStart(false, () -> DatabaseBootstrap.migrateToLatest(ds));
      assertEquals(6, appliedMaxVersion());
    } finally {
      // 兜底：断言失败时也要把共享测试库恢复到最新，别把失败传染给后面的测试类。
      DatabaseBootstrap.migrateToLatest(ds);
    }
  }

  /** 在指定取值的 {@code MIGRATE_ON_START} 下跑一段逻辑，跑完把配置恢复原样。 */
  private static void withMigrateOnStart(boolean enabled, Runnable action) {
    System.setProperty(KEY, Boolean.toString(enabled));
    Env.resetCacheForTest();
    try {
      action.run();
    } finally {
      System.clearProperty(KEY);
      Env.resetCacheForTest();
    }
  }

  /**
   * 把库退回到 V5：删掉 V6 的历史行，以及 V6 建出来的三样东西。
   *
   * <p>顺序是有意的：索引要在列之前删。{@code idx_auctions_status_starts} 是
   * {@code (status, starts_at)} 的复合索引，只删列的话它可能以 {@code (status)} 的形态留下来，
   * 于是 V6 重跑时会以“重复索引名”失败——那样测试会因为一件与开关无关的事变红。
   */
  private void rollbackToV5() throws SQLException {
    try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
      st.execute("ALTER TABLE auctions DROP INDEX idx_auctions_status_starts");
      st.execute("ALTER TABLE auctions DROP COLUMN starts_at");
      st.execute("DROP TABLE agent_proxies");
      st.execute("DELETE FROM flyway_schema_history WHERE version = '6'");
    }
  }

  /** 库里已成功应用的最高迁移版本号；空库返回 0。 */
  private int appliedMaxVersion() throws SQLException {
    try (Connection conn = ds.getConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) FROM flyway_schema_history"
                    + " WHERE success = 1")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
