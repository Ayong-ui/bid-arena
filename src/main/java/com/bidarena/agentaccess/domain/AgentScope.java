package com.bidarena.agentaccess.domain;

import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.Map;

/**
 * Agent Token 的权限项。取值与 {@code openapi.yaml} 的
 * {@code CreateAgentTokenRequest.scopes} 枚举逐字一致。
 *
 * <p>只有两项是刻意的：读状态与出价。没有"取消拍卖""查看钱包流水"这类权限——
 * Agent 是竞拍参与者，不是运营工具；给它管理能力就等于绕过了整条管理员授权链。
 *
 * <p>用枚举而不是裸字符串：权限判断是本项目最不能出错的地方之一，
 * 而"权限名打错一个字母"在字符串世界里是合法的，只会静默变成"没有这个权限"。
 */
public enum AgentScope {

    /** 读取拍卖快照与成交结果。 */
    READ("auction:read"),

    /** 提交出价。 */
    BID("auction:bid");

    private final String wire;

    AgentScope(String wire) {
        this.wire = wire;
    }

    /** 契约里的字面量，用于入库与响应。 */
    public String wire() {
        return wire;
    }

    /**
     * 解析契约里的权限字符串。未知取值抛 400 而不是忽略：
     * 静默丢弃一个拼错的权限，会让签发的 Token 少一项能力，而调用方要等到 403 才发现。
     */
    public static AgentScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "scope 不能为空");
        }
        String value = raw.trim();
        for (AgentScope scope : values()) {
            if (scope.wire.equals(value)) {
                return scope;
            }
        }
        throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的权限项",
                Map.of("scope", raw, "allowed", "auction:read,auction:bid"));
    }
}
