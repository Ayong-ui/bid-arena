package com.bidarena.identity.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

/**
 * 验证迁移脚本里播种的演示账号**真的能登录**。
 *
 * <h2>为什么值得单独一个测试</h2>
 * 演示口令是本项目对外承诺的一部分（原文明确给出三个账号）。播种脚本里
 * {@code password_hash} 是一串看起来像 BCrypt 的常量，它是否对应文档里的口令，
 * 光看代码是判断不出来的——写错一个字符，评测者就会在登录页面上直接卡住。
 * 这里用生产同一套 BCrypt 现验一次，把"看起来对"变成"确认对"。
 *
 * <h2>顺带守住"仓库里没有明文口令"</h2>
 * 同一段逻辑反向断言：明文口令不得出现在迁移脚本里。BCrypt 哈希可以入库，
 * 明文不行——包括"注释里写一下方便大家用"这种最常见的泄漏方式。
 */
class SeededDemoCredentialsTest {

    /** 演示账号的明文口令。取自原文与 README，两边必须一致。 */
    private static final Map<String, String> DOCUMENTED = Map.of(
            "admin@example.com", "Admin123456!",
            "bidder_a@example.com", "Test123456!",
            "bidder_b@example.com", "Test123456!");

    /** 匹配 {@code ('id', 'email', 'display_name', '$2a$10$...', 'ROLE', 'STATUS')} 里的邮箱与哈希。 */
    private static final Pattern SEED_ROW = Pattern.compile(
            "'([^']*)'\\s*,\\s*'([^']+@[^']+)'\\s*,\\s*'[^']*'\\s*,\\s*'(\\$2[aby]\\$[^']+)'");

    @Test
    @DisplayName("三个演示账号的播种哈希与文档口令一致")
    void seededHashesMatchDocumentedPasswords() throws IOException {
        String sql = readMigration();
        Map<String, String> seeded = extractSeedHashes(sql);

        for (Map.Entry<String, String> expected : DOCUMENTED.entrySet()) {
            String email = expected.getKey();
            String password = expected.getValue();

            String hash = seeded.get(email);
            assertNotNull(hash, "迁移脚本里缺少演示账号 " + email + " 的播种哈希；实际解析到：" + seeded.keySet());

            assertTrue(BCrypt.checkpw(password, hash),
                    "播种哈希与文档口令不匹配，评测者将无法用 " + email + " 登录");
            assertFalse(BCrypt.checkpw(password + "x", hash), "哈希校验太宽松：加一个字符仍然通过");
        }

        assertEquals(DOCUMENTED.size(), seeded.size(),
                "迁移脚本播种了计划外的账号，演示账号清单需要同步更新：" + seeded.keySet());
    }

    @Test
    @DisplayName("演示口令不短于契约下限，且明文不出现在仓库文件里")
    void noPlaintextPasswordInRepo() throws IOException {
        String sql = readMigration();

        for (Map.Entry<String, String> entry : DOCUMENTED.entrySet()) {
            String password = entry.getValue();
            assertTrue(password.length() >= 8,
                    "演示口令短于契约 LoginRequest.password 的 minLength=8：" + entry.getKey());
            assertFalse(sql.contains(password),
                    "迁移脚本里出现了明文口令（" + entry.getKey() + "）。哈希可以入库，明文不可以。");
        }
    }

    private static Map<String, String> extractSeedHashes(String sql) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = SEED_ROW.matcher(sql);
        while (matcher.find()) {
            result.put(matcher.group(2), matcher.group(3));
        }
        return result;
    }

    /**
     * 从 classpath 读迁移脚本，而不是拼相对路径。
     *
     * <p>相对路径依赖"测试的工作目录恰好是项目根目录"，换一个构建方式就会静默读不到文件；
     * 而迁移脚本本来就被打进 classpath（{@code pom.xml} 的 {@code <resources>}），
     * 走 classpath 才和 Flyway 看到的是同一份文件。
     */
    private static String readMigration() throws IOException {
        String resource = "db/migration/V2__identity_wallet_ledger.sql";
        try (InputStream in = SeededDemoCredentialsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "classpath 中找不到 " + resource + "，无法校验演示凭据");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
