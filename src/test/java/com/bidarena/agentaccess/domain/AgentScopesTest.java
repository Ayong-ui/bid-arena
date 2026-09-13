package com.bidarena.agentaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 权限集合与数据库文本之间的转换。
 *
 * <p>重点是**互逆**：写进库里的字符串必须能被自己读回来。这条性质一旦被破坏
 * （例如加了新权限却只改了一个方向的格式），症状是"某枚 Token 悄无声息地少了一项权限"，
 * 而不是一个能直接定位的报错。
 */
@DisplayName("Agent 权限的序列化")
class AgentScopesTest {

    @Test
    @DisplayName("写出的是排序后的 CSV：同一集合永远得到同一个字符串")
    void wireIsSortedAndStable() {
        assertEquals("auction:bid", AgentScopes.wire(Set.of(AgentScope.BID)));
        assertEquals("auction:bid,auction:read", AgentScopes.wire(EnumSet.allOf(AgentScope.class)));
        assertEquals("auction:read", AgentScopes.wire(Set.of(AgentScope.READ)));
        assertEquals("", AgentScopes.wire(Set.of()));
    }

    @Test
    @DisplayName("写出去再读回来得到同一个集合（每个取值都覆盖）")
    void roundTripForEveryValue() {
        for (AgentScope scope : AgentScope.values()) {
            Set<AgentScope> single = Set.of(scope);
            assertEquals(single, AgentScopes.parseCsv(AgentScopes.wire(single)));
        }
        assertEquals(EnumSet.allOf(AgentScope.class), AgentScopes.parseCsv(AgentScopes.wire(EnumSet.allOf(AgentScope.class))));
        assertEquals(Set.of(), AgentScopes.parseCsv(AgentScopes.wire(Set.of())));
    }

    @Test
    @DisplayName("解析时忽略空段（历史数据里可能留下尾随逗号）")
    void parseIgnoresBlankSegments() {
        assertEquals(EnumSet.allOf(AgentScope.class), AgentScopes.parseCsv("auction:read,,auction:bid,"));
        assertEquals(Set.of(), AgentScopes.parseCsv(""));
        assertEquals(Set.of(), AgentScopes.parseCsv(null));
    }

    @Test
    @DisplayName("未知权限按 400 抛出，不静默丢弃")
    void parseRejectsUnknownValue() {
        BizException error = assertThrows(BizException.class,
                () -> AgentScopes.parseCsv("auction:read,auction:cancel"));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.code());
    }
}
