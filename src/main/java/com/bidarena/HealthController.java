package com.bidarena;

import com.bidarena.api.ApiTime;
import com.bidarena.api.ApiTrace;
import com.bidarena.shared.ApiResponse;
import java.time.Instant;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.MethodType;

/**
 * 健康检查。公开访问（见 {@code AuthFilter} 白名单）。
 *
 * <p>返回统一封套而不是裸的 {@code {"status":"UP"}}：一个例外就会让客户端的统一解包逻辑
 * 需要为它写特例。探活方（Compose healthcheck / 负载均衡）需要的是 HTTP 200，
 * 而不是某个特定 JSON 形状。
 */
@Controller
@Mapping("/api/v1")
public class HealthController {

    @Mapping(value = "/health", method = MethodType.GET)
    public ApiResponse health() {
        return ApiResponse.ok(
                Map.of("status", "UP", "service", "bid-arena", "time", ApiTime.format(Instant.now())),
                ApiTrace.current());
    }
}
