package com.bidarena.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 后端读取的每个配置键，都必须能在 {@code .env.example} 里找到一条真正的赋值。
 *
 * <h2>这条守卫为什么有用</h2>
 * {@link Env} 是配置的唯一读取点，{@code .env.example} 是部署者唯一的清单。两者脱节时
 * 代码照样编译、测试照样全绿——只有"照着样例部署的人"会踩到：他根本不知道存在这个键
 * （例如 {@code JWT_TTL_SECONDS}、{@code AGENT_SERVER_HOST}），于是只能吃默认值，
 * 或者以为它不可配。本仓库的立场是"配置的可发现性也是可部署性的一部分"，
 * 所以把"代码读的键 ⊆ 样例写的键"变成一条会失败的断言。
 *
 * <h2>为什么扫源码，而不是维护一份键的清单</h2>
 * 手写清单会过期，而过期的清单只提供虚假的安全感。这里扫 {@code src/main/java} 下
 * {@code Env.required/read/intOr/longOr/boolOr("KEY"…)} 的字面量调用；用常量传键的读取点
 * 扫不到（目前只有 {@link ScannerBootstrap} 的三个扫描器开关），显式登记在
 * {@link #KEYS_READ_VIA_CONSTANTS}。扫描本身有失效风险，所以另用
 * {@link #theScanActuallyFindsKeys()} 先证明它确实抓到一批已知键——守卫不能"永远为真"。
 */
@DisplayName("配置键与 .env.example 的一致性")
class EnvDocumentationTest {

    /** 以常量（而非字面量）传键的读取点：正则扫不到，必须手工登记；漏登记会让守卫变松。 */
    private static final List<String> KEYS_READ_VIA_CONSTANTS = List.of(
            ScannerBootstrap.SETTLE_SCHEDULER_ENABLED,
            ScannerBootstrap.AUCTION_START_SCHEDULER_ENABLED,
            ScannerBootstrap.AGENT_PROXY_SCHEDULER_ENABLED);

    /** 只认这五种读取方法紧跟一个全大写键名，避免把无关的 {@code read(...)} 扫进来。 */
    private static final Pattern ENV_READ =
            Pattern.compile("(?:required|read|intOr|longOr|boolOr)\\(\"([A-Z][A-Z0-9_]*)\"");

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path ENV_EXAMPLE = Path.of(".env.example");

    @Test
    @DisplayName("扫描确实抓到了一批已知键（守卫不是永远为真）")
    void theScanActuallyFindsKeys() throws IOException {
        Set<String> keys = keysReadByTheBackend();

        assertTrue(keys.contains("DB_URL"), "没扫到 DB_URL，先确认扫描根目录与正则是否失效：" + keys);
        assertTrue(keys.contains("JWT_SECRET"), "没扫到 JWT_SECRET：" + keys);
        assertTrue(keys.contains("SETTLE_SCAN_INTERVAL_MS"), "没扫到扫描器间隔键：" + keys);
        assertTrue(
                keys.contains(ScannerBootstrap.SETTLE_SCHEDULER_ENABLED),
                "常量登记的开关没进集合：" + keys);
        assertTrue(keys.size() >= 15, "只扫到 " + keys.size() + " 个键，怀疑扫描范围被改窄了：" + keys);
    }

    @Test
    @DisplayName("后端读取的每个键，在 .env.example 里都有一条赋值")
    void everyKeyReadByTheBackendIsDocumented() throws IOException {
        List<String> missing = missingKeys(read(ENV_EXAMPLE), keysReadByTheBackend());

        assertTrue(
                missing.isEmpty(),
                "这些键后端会读，但 .env.example 里没有（照着样例部署的人不会知道它们存在）：\n  "
                        + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("只写在注释里的键不算文档：必须有一条真正的赋值")
    void aKeyMentionedOnlyInACommentIsReportedMissing() {
        String sample = "#DB_URL=jdbc:mysql://localhost/x\nJWT_SECRET=abc\n";

        assertEquals(
                List.of("DB_URL"),
                missingKeys(sample, Set.of("DB_URL", "JWT_SECRET")),
                "注释掉的示例不算“写清楚了”");
    }

    // ---------------------------- 工具 ----------------------------

    /** 扫 {@code src/main/java} 下所有 {@code Env.*("KEY")} 字面量，再并入常量式登记的键。 */
    private static Set<String> keysReadByTheBackend() throws IOException {
        assertTrue(
                Files.isDirectory(MAIN_SOURCES),
                "找不到 " + MAIN_SOURCES.toAbsolutePath() + "（该测试须在仓库根目录运行）");

        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = ENV_READ.matcher(read(file));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        keys.addAll(KEYS_READ_VIA_CONSTANTS);
        return keys;
    }

    /** 返回"在非注释行里没有赋值"的键，保持字典序。 */
    private static List<String> missingKeys(String envExample, Set<String> keys) {
        Set<String> assigned = new TreeSet<>();
        for (String line : envExample.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                assigned.add(trimmed.substring(0, eq).strip());
            }
        }
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!assigned.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "找不到 " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
