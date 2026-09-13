package com.bidarena.api;

/**
 * HTTP 路径常量：跨包共享的那几条前缀放在这里，避免同一段字符串在多个包里各写一遍。
 *
 * <p>为什么需要它：{@link AuthFilter} 必须"跳过 Agent 路径"，而 Agent 路径的真实定义
 * 在 {@code agentaccess.adapter} 的控制器注解上。若两边各写字面量，某天控制器把前缀
 * 从 {@code /api/v1/agent} 改成 {@code /api/v1/agents}，过滤器就会开始把 Agent 请求
 * 当普通用户请求处理——结果是 Agent 的每一次调用都收到 401，而且看不出原因。
 * 共享常量让"改一处、全处生效"，代价是控制器注解里仍需写字面量（注解参数要求常量），
 * 因此这条常量与注解之间有一行注释互相指认。
 */
public final class ApiPaths {

    /** Agent API 路径前缀（{@code HttpAgentController} 的类级 {@code @Mapping} 与之一致）。 */
    public static final String AGENT_PREFIX = "/api/v1/agent/";

    private ApiPaths() {}
}
