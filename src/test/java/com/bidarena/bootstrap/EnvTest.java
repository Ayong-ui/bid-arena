package com.bidarena.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置读取的取值规则。
 *
 * <p>用系统属性而不是环境变量来测：{@code Env} 里系统属性优先（刻意如此，便于命令行临时覆盖），
 * 而环境变量在一个 JVM 里改不了，测试没法构造不同取值。规则本身两条路是同一份代码。
 */
@DisplayName("配置读取：Env")
class EnvTest {

  private static final String KEY = "BID_ARENA_TEST_BOOL_KEY";

  @AfterEach
  void clearSetting() {
    System.clearProperty(KEY);
    Env.resetCacheForTest();
  }

  private static void set(String value) {
    System.setProperty(KEY, value);
    Env.resetCacheForTest();
  }

  @Test
  @DisplayName("没配置时用缺省值，而不是抛异常或当成 false")
  void absentFallsBackToDefault() {
    assertTrue(Env.boolOr(KEY, true));
    assertFalse(Env.boolOr(KEY, false));
  }

  @Test
  @DisplayName("四种写法都认，且不分大小写")
  void acceptsFourSpellings() {
    set("true");
    assertTrue(Env.boolOr(KEY, false));
    set("TRUE");
    assertTrue(Env.boolOr(KEY, false));
    set("1");
    assertTrue(Env.boolOr(KEY, false));

    set("false");
    assertFalse(Env.boolOr(KEY, true));
    set("False");
    assertFalse(Env.boolOr(KEY, true));
    set("0");
    assertFalse(Env.boolOr(KEY, true));
  }

  @Test
  @DisplayName("拼错的开关是启动失败，不是悄悄用缺省值")
  void rejectsUnknownSpelling() {
    // 这条比“能读 true/false”重要得多：MIGRATE_ON_START=no 这种写法如果被当成缺省值，
    // 部署者会以为“自动迁移已关闭”，而实际行为与他想的相反，且没有任何提示。
    for (String bad : new String[] {"yes", "on", "no", "off", "2", "tru"}) {
      set(bad);
      IllegalStateException e =
          assertThrows(IllegalStateException.class, () -> Env.boolOr(KEY, true), bad);
      assertTrue(e.getMessage().contains(KEY), e.getMessage());
      assertTrue(e.getMessage().contains("不是合法布尔值"), e.getMessage());
    }
  }

  @Test
  @DisplayName("首尾空白不算值的一部分")
  void trimsSurroundingWhitespace() {
    // .env / docker compose 的 env_file 里手写一行常常会带上空格，不该因此启动失败。
    set(" false ");
    assertFalse(Env.boolOr(KEY, true));
  }
}
