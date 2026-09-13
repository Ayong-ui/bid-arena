package com.bidarena.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 匿名标识的单元测试。
 *
 * <p>它唯一的职责是"确定性 + 不泄露原值"，而这两点都可以在不连数据库的情况下验完；
 * 这里也就没有测试数据库的依赖，能随时跑。
 */
class AnonymousIdTest {

    @Test
    @DisplayName("同一用户永远得到同一标识（前端才能认出'这是我'）")
    void deterministic() {
        assertEquals(AnonymousId.of("usr_bidder_a"), AnonymousId.of("usr_bidder_a"));
        assertEquals(AnonymousId.of("usr_bidder_a"), AnonymousId.of("usr_bidder_a"));
    }

    @Test
    @DisplayName("不同用户得到不同标识，且标识里不含原始 user_id")
    void distinctAndOpaque() {
        String a = AnonymousId.of("usr_bidder_a");
        String b = AnonymousId.of("usr_bidder_b");

        assertNotEquals(a, b);
        for (String id : new String[] {a, b}) {
            assertTrue(id.startsWith("anon-"), id);
            assertEquals(5 + 8, id.length(), "前缀 + 8 位十六进制：" + id);
            assertFalse(id.contains("usr_"), id);
            assertFalse(id.contains("bidder"), id);
        }
    }

    @Test
    @DisplayName("null 返回 null：终局事件的'无赢家'要用它表达缺席")
    void nullSafe() {
        assertNull(AnonymousId.of(null));
    }

    @Test
    @DisplayName("算法是契约的一部分：固定输入 → 固定输出，改动即破坏前端与回放")
    void pinnedVector() {
        // 这两个值是外部工具算出来的常量（sha256sum 后取前 8 位十六进制），不是本实现的回显：
        // 若有人把 SHA-256 换成别的哈希、改了截断长度或改了前缀，测试立刻失败。
        // 前端（P4）用的是同一算法，所以这是一条真的跨端契约，不是自欺式的镜像断言。
        assertEquals("anon-2952873c", AnonymousId.of("usr_bidder_a"));
        assertEquals("anon-76d6c64f", AnonymousId.of("usr_admin"));
    }
}
