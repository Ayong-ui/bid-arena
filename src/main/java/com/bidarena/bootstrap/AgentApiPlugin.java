package com.bidarena.bootstrap;

import com.bidarena.api.ApiPaths;
import com.bidarena.api.ApiWriter;
import com.bidarena.shared.ErrorCode;
import java.util.Map;
import org.noear.solon.Solon;
import org.noear.solon.SolonApp;
import org.noear.solon.boot.prop.impl.HttpServerProps;
import org.noear.solon.boot.smarthttp.SmHttpServerComb;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;
import org.noear.solon.core.SignalSim;
import org.noear.solon.core.SignalType;
import org.noear.solon.core.handle.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在独立端口（默认 8090）上再开一个 HTTP 监听器，只暴露 Agent API。
 *
 * <h2>为什么值得为它多开一个端口</h2>
 * 原文只要求"独立的认证凭据与最小权限边界"。把 Agent API 与用户/管理接口放在同一个端口上
 * 也能满足字面要求，但那样"边界"只是路由表里的一段前缀——任何人只要在浏览器里把
 * {@code /api/v1/agent/...} 换成 {@code /api/v1/admin/...}，就来到了同一个端口上的另一片区域。
 * 独立端口把这个边界变成部署事实：<b>8090 上根本不存在管理接口</b>（见
 * {@link #onlyAgentApi}），Agent 即使拿到了一个身份也不得不回到 8080，而 8080 要的是 JWT。
 * 代价是多一个监听器与两个环境变量，换来的是"防线不止一层"。
 *
 * <h2>为什么用 Solon 的 Plugin 而不是在 main 里手写启停</h2>
 * {@code Plugin} 的 {@code start/stop} 由框架在正确的时机调用，且
 * {@code Solon.stopBlock()} 会遍历插件调用其 {@code stop()}。
 * 手写的话要么忘记关端口（测试 JVM 里留下一个悬空监听，下次跑测试端口被占），
 * 要么自己再注册一个关闭钩子，与框架的停止流程并排跑——两条停止路径迟早会打架。
 *
 * <h2>为什么不用 {@code server.port} 配置</h2>
 * Solon 3.0.1 的 smarthttp 插件只读取单一 {@code server.port}，没有"多端口"配置项
 * （{@code SolonProps} 里不存在这样的键）。{@code Solon.start} 又是进程级单例
 * （第二次调用直接返回已有实例，见 {@code DEBUG_LOG} DBG-22），因此"再启动一个应用"
 * 这条路是封死的。这里复用的是框架自己的 {@code SmHttpServerComb}——与主监听器同一个类，
 * 只是换了一个 Handler 与端口。
 *
 * <p>端口为 {@code <= 0} 时整体禁用（便于"我只想跑前端 + 主 API"的场景）。
 */
public final class AgentApiPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(AgentApiPlugin.class);

    private final int port;
    private final String host;

    private SmHttpServerComb server;

    public AgentApiPlugin(int port, String host) {
        this.port = port;
        this.host = host;
    }

    @Override
    public void start(AppContext context) throws Throwable {
        if (port <= 0) {
            log.info("Agent API 未启用（AGENT_SERVER_PORT={}，设为正数可开启）", port);
            return;
        }

        SolonApp app = Solon.app();
        SmHttpServerComb comb = new SmHttpServerComb();
        comb.setCoreThreads(HttpServerProps.getInstance().getCoreThreads());
        comb.setExecutor(HttpServerProps.getInstance().getBioExecutor("agent-http-"));
        comb.enableWebSocket(false);
        comb.setHandler(onlyAgentApi(app));

        comb.start(host, port);
        this.server = comb;

        // 注册 Signal：它不参与启停（handler 已经是同一套 pipeline），
        // 只让 app.signals() 如实反映"这个进程在监听哪些端口"，运维排查时不必靠猜。
        app.signalAdd(new SignalSim("agent-http", host, port, "http", SignalType.HTTP));
        log.info("Agent API 监听 {}:{}（仅暴露 {}", host, port, ApiPaths.AGENT_PREFIX + "**）");
    }

    @Override
    public void stop() throws Throwable {
        if (server != null) {
            server.stop();
            server = null;
            log.info("Agent API 已停止（原端口 {}）", port);
        }
    }

    /**
     * 端口隔离的实现：只有 Agent 前缀的请求才进入应用主 pipeline，其余一律 404 封套。
     *
     * <p>用 404 而不是 403：这个端口上<b>没有</b>那些资源，而不是"有但不许你看"。
     * 403 会暗示"路径存在、只是权限不足"，反而给探测者提供了信息。
     *
     * <p>注意返回的是 {@code 404 NOT_FOUND} 封套而不是容器默认错误页——
     * 本项目的约定是"任何响应都可解析"（见 {@code docs/openapi.yaml} 开头的说明），
     * 一个 HTML 错误页会让 Agent 的通用解包逻辑直接把 404 报成"服务异常"。
     */
    static Handler onlyAgentApi(SolonApp app) {
        return ctx -> {
            if (ctx.path().startsWith(ApiPaths.AGENT_PREFIX)) {
                app.tryHandle(ctx);
                return;
            }
            ApiWriter.failure(ctx, ErrorCode.NOT_FOUND,
                    "Agent 端口只提供 " + ApiPaths.AGENT_PREFIX + "** 接口",
                    Map.of("path", ctx.path()));
        };
    }
}
