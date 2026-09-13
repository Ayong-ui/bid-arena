package com.bidarena.identity.adapter;

import com.bidarena.api.ApiTime;
import com.bidarena.api.ApiTrace;
import com.bidarena.api.CurrentUser;
import com.bidarena.identity.application.IdentityService;
import com.bidarena.identity.domain.Principal;
import com.bidarena.shared.ApiResponse;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.ContextUtil;
import org.noear.solon.core.handle.MethodType;

/**
 * 身份相关的 HTTP 入口：登录、当前用户。
 *
 * <p>控制器只做三件事：解析输入、调用应用服务、包成统一封套。
 * 校验、事务、异常翻译都不在这里——那些属于应用层与过滤器。
 *
 * <p>依赖用 {@code @Inject} 字段注入（Solon 的构造器注入需要为每个参数标注类型，
 * 而本项目的装配图已由 {@code bootstrap.Services} 显式给出，这里只是把它接上）。
 */
@Controller
@Mapping("/api/v1")
public class HttpAuthController {

    @Inject
    IdentityService identity;

    /** 登录请求体，与 {@code openapi.yaml} 的 {@code LoginRequest} 一致。 */
    public record LoginRequest(String email, String password) {}

    /** 登录响应数据，与 {@code AuthData} 一致。{@code expiresAt} 用字符串见 {@link ApiTime}。 */
    public record AuthData(String accessToken, String expiresAt, UserView user) {}

    @Mapping(value = "/auth/login", method = MethodType.POST)
    public ApiResponse login(@Body LoginRequest body) {
        if (body == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        IdentityService.LoginResult result = identity.login(body.email(), body.password());
        return ApiResponse.ok(
                new AuthData(result.accessToken(), ApiTime.format(result.expiresAt()), UserView.of(result.user())),
                ApiTrace.current());
    }

    @Mapping(value = "/users/me", method = MethodType.GET)
    public ApiResponse me() {
        Principal me = CurrentUser.require(ContextUtil.current());
        return ApiResponse.ok(UserView.of(identity.requireUser(me.userId())), ApiTrace.current());
    }
}
