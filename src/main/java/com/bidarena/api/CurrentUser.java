package com.bidarena.api;

import com.bidarena.identity.domain.Principal;
import com.bidarena.identity.domain.UserRole;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.util.Map;
import org.noear.solon.core.handle.Context;

/**
 * 读取 {@link AuthFilter} 放进 {@link Context} 的当前身份，并做角色判断。
 *
 * <p>把"从哪取身份"和"角色够不够"收敛到一处：控制器里散落
 * {@code ctx.attr("bidarena.principal")} 这种字面量，改一次键名就是一个漏洞。
 */
public final class CurrentUser {

    /** 与 {@code AuthFilter} 共用的属性键。 */
    static final String ATTR = "bidarena.principal";

    private CurrentUser() {}

    /** 返回当前身份；没有则抛 401。正常情况下 {@code AuthFilter} 已经放好了。 */
    public static Principal require(Context ctx) {
        Principal principal = ctx.attr(ATTR);
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未认证");
        }
        return principal;
    }

    /** 要求管理员角色；普通用户抛 403。 */
    public static Principal requireAdmin(Context ctx) {
        Principal principal = require(ctx);
        if (principal.role() != UserRole.ADMIN) {
            throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限",
                    Map.of("role", principal.role().name()));
        }
        return principal;
    }
}
