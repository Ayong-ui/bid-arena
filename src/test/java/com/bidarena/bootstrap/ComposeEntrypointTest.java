package com.bidarena.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 编排里写死的 Java 入口类必须真的存在（DBG-33）。
 *
 * <p>Maven 不认识 {@code docker-compose.yml}：把 {@code com.bidarena.bootstrap.MigrateMain} 写成
 * {@code com.bidarena.MigrateMain} 照样能编译、能打包、能通过 review，直到真的去跑那个容器才报
 * {@code ClassNotFoundException}——代价是一轮 CI。这里把“编排里写的类名”与“classpath 上的类”
 * 对起来，代价是一次 {@code forName}，而且不连库、不依赖 Docker。
 */
class ComposeEntrypointTest {

  /** 只认“整行就是一个 Java 全限定类名”的 YAML 列表项（`- com.bidarena.X.Y`）；注释行不会误命中。 */
  private static final Pattern ENTRYPOINT_CLASS =
      Pattern.compile("^\\s*-\\s*(com\\.bidarena\\.[A-Za-z0-9_.]+)\\s*$");

  @Test
  @DisplayName("docker-compose.yml 里的 entrypoint 类都能在 classpath 上找到，且带 main(String[])")
  void everyComposeEntrypointClassExistsWithMainMethod() throws IOException {
    Path compose = Path.of("docker-compose.yml");
    assertTrue(
        Files.exists(compose), "找不到 " + compose.toAbsolutePath() + "（该测试须在仓库根目录运行）");

    List<String> entrypointClasses = new ArrayList<>();
    for (String line : Files.readAllLines(compose, StandardCharsets.UTF_8)) {
      Matcher matcher = ENTRYPOINT_CLASS.matcher(line);
      if (matcher.matches()) {
        entrypointClasses.add(matcher.group(1));
      }
    }
    // 防“永远为真”的守卫：正则一旦改坏、什么都匹配不到，这条会先响（与 DBG-19 同类）。
    assertFalse(
        entrypointClasses.isEmpty(), "没有在 compose 里解析到任何 com.bidarena.* 入口类，先确认正则是否失效");

    for (String name : entrypointClasses) {
      Class<?> type =
          assertDoesNotThrow(
              () -> Class.forName(name),
              "compose 的 entrypoint 指向了不存在的类：" + name + "（包名少写一层就会这样，见 DBG-33）");
      Method main =
          assertDoesNotThrow(() -> type.getMethod("main", String[].class), name + " 缺少 main(String[])");
      assertTrue(
          Modifier.isPublic(main.getModifiers()) && Modifier.isStatic(main.getModifiers()),
          name + " 的 main 必须是 public static");
    }
  }
}
