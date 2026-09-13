package com.bidarena.agentaccess.domain;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限集合与它在数据库里的表示（逗号分隔）之间的转换。
 *
 * <p>放在 domain 而不是 persistence：这两个方向的转换必须互逆，而"互逆"是一个
 * 关于权限的规则，不是关于 MySQL 的规则。若把它写在仓储里，日后有人给
 * {@code AgentScope} 加一个新枚举值、顺手改了其中一版 CSV 格式，另一版就会
 * 悄悄读不出新值——而这种不一致只有在某枚 Token 突然失去权限时才会暴露。
 */
public final class AgentScopes {

    private AgentScopes() {}

    /** 集合 → CSV。排序后输出，使同一集合永远得到同一个字符串（便于比对与断言）。 */
    public static String wire(Set<AgentScope> scopes) {
        return scopes.stream().map(AgentScope::wire).sorted().collect(Collectors.joining(","));
    }

    /**
     * CSV → 集合。空段忽略；非法值抛 {@code VALIDATION_FAILED}。
     *
     * <p>调用方需要区分"调用方传错了"与"库里的值坏了"：前者是 400，后者是 500。
     * 因此这里只负责如实抛出，由读库的那一层决定怎么翻译。
     */
    public static Set<AgentScope> parseCsv(String csv) {
        Set<AgentScope> scopes = EnumSet.noneOf(AgentScope.class);
        if (csv == null) {
            return scopes;
        }
        for (String part : csv.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            scopes.add(AgentScope.parse(part));
        }
        return scopes;
    }
}
