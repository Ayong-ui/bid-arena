package com.bidarena.auction.adapter;

import com.bidarena.api.ApiTrace;
import com.bidarena.api.CurrentUser;
import com.bidarena.auction.application.AuctionCommandService;
import com.bidarena.auction.application.AuctionCommandService.CreateAuction;
import com.bidarena.auction.application.AuctionQueryService;
import com.bidarena.shared.ApiResponse;
import com.bidarena.shared.BizException;
import com.bidarena.shared.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Path;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextUtil;
import org.noear.solon.core.handle.MethodType;

/**
 * 管理员 HTTP 入口：创建拍品、开始、取消。
 *
 * <h2>RBAC 放在每个方法的第一行</h2>
 * {@link CurrentUser#requireAdmin} 在进入任何业务调用之前执行。把鉴权写在方法体首行
 * （而不是靠路径前缀匹配某个过滤器）是为了让"这个方法需要管理员"与"这个方法做什么"
 * 在同一个屏幕上可见——把授权规则藏到路由配置里，新增一个 {@code /admin/} 路由时
 * 很容易忘记同步配置。
 *
 * <h2>创建返回 201</h2>
 * 契约要求 201。状态码在控制器里显式设置，而不是靠返回值类型推断。
 */
@Controller
@Mapping("/api/v1")
public class HttpAdminController {

    @Inject
    AuctionCommandService commands;

    @Inject
    AuctionQueryService query;

    /**
     * 创建拍品请求，与契约 {@code CreateAuctionRequest} 一致。
     *
     * <p>用包装类型而不是 {@code long}/{@code int}：字段缺失时 Jackson 给不出 null，
     * 会变成 0，于是"少传了 durationSeconds"会得到"durationSeconds 必须 >= 10"这种
     * 指向错误原因的提示。包装类型让"没传"与"传了非法值"可分辨。
     */
    public record CreateAuctionRequest(
            String title, String description, Long startPrice, Long minIncrement, Integer durationSeconds,
            String startsAt) {}

    /**
     * 解析预告开拍时间。
     *
     * <p>接受带 {@code Z} 的 UTC（前端 {@code toISOString()} 的产物）与带时区偏移的写法
     * （{@code 2026-09-14T10:00:00+08:00}，人手写更自然）。
     * <b>明确拒绝</b>不带时区的 {@code LocalDateTime}：那会引入"按谁的时区解释"的歧义，
     * 在本项目里等于把开拍时间交给浏览器所在时区决定。
     */
    private static Instant parseStartsAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 不是 UTC 写法，继续试带偏移的写法
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "startsAt 必须是 ISO-8601 时间（如 2026-09-14T10:00:00+08:00 或 2026-09-14T02:00:00Z）",
                    Map.of("startsAt", value));
        }
    }

    @Mapping(value = "/admin/auctions", method = MethodType.POST)
    public ApiResponse createAuction(@Body CreateAuctionRequest request) {
        Context ctx = ContextUtil.current();
        CurrentUser.requireAdmin(ctx);
        if (request == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        if (request.startPrice() == null || request.minIncrement() == null || request.durationSeconds() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "startPrice、minIncrement、durationSeconds 均为必填");
        }

        String auctionId = commands.create(new CreateAuction(
                request.title(), request.description(), request.startPrice(),
                request.minIncrement(), request.durationSeconds(), parseStartsAt(request.startsAt())));

        ctx.status(201);
        return ApiResponse.ok(query.snapshot(auctionId), ApiTrace.current());
    }

    @Mapping(value = "/admin/auctions/{auctionId}/start", method = MethodType.POST)
    public ApiResponse startAuction(@Path("auctionId") String auctionId) {
        Context ctx = ContextUtil.current();
        CurrentUser.requireAdmin(ctx);
        commands.start(auctionId);
        return ApiResponse.ok(query.snapshot(auctionId), ApiTrace.current());
    }

    @Mapping(value = "/admin/auctions/{auctionId}/cancel", method = MethodType.POST)
    public ApiResponse cancelAuction(@Path("auctionId") String auctionId) {
        Context ctx = ContextUtil.current();
        CurrentUser.requireAdmin(ctx);
        commands.cancel(auctionId);
        return ApiResponse.ok(query.snapshot(auctionId), ApiTrace.current());
    }
}
