package com.bidarena.agentaccess.adapter;

import com.bidarena.agentaccess.domain.AgentToken;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import org.noear.solon.core.handle.Context;

/**
 * 读取 {@link AgentAuthFilter} 放进 {@link Context} 的 Agent 身份。
 *
 * <p>与 {@code api.CurrentUser} 是同一个模式的第二份：键名与"取不到怎么办"只写在一处。
 * 刻意不复用 {@code CurrentUser}：那里的 {@code Principal} 是 JWT 用户，这里是 Agent Token；
 * 把两种凭证塞进同一个上下文键，等于允许一段代码用 Agent 的身份去做"普通用户"的操作。
 * 分开存放，两者就不可能混淆。
 */
public final class AgentCaller {

    /** 与 {@link AgentAuthFilter} 共用的属性键。 */
    static final String ATTR = "bidarena.agent";

    private AgentCaller() {}

    /** 返回当前 Agent 身份；没有则抛 401（正常情况下认证过滤器已经放好了）。 */
    public static AgentToken require(Context ctx) {
        AgentToken token = ctx.attr(ATTR);
        if (token == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未认证的 Agent 调用");
        }
        return token;
    }
}
