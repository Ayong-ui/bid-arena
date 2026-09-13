package com.bidarena.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.bidarena.Application;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.noear.solon.Solon;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.test.HttpTester;

/**
 * 集成测试的公共基座：**整个 JVM 只启动一次**真实服务，并给出打真包的 HTTP 工具。
 *
 * <h2>为什么需要一个基座类（而不是每个测试类各起一次）</h2>
 * 服务**整个测试 JVM 只起一次**：Solon 是进程级单例，{@code Solon.cfg()} 在第一次启动后
 * 就是缓存过的只读配置，第二次启动会继续绑到旧端口上（{@code System.setProperty("server.port")}
 * 已经不起作用）。于是“停了再起”不是重启，而是“起在一个测试进程并不监听的端口上”。
 * HTTP 与 WebSocket 两组用例需要的是同一个端口、同一张对象图，因此只起一次最自然。
 *
 * <h2>什么时候停</h2>
 * 资源的释放交给 JUnit 的**根上下文存储**（{@code ExtensionContext.Store.CloseableResource}）：
 * 根上下文在整轮测试结束时关闭，那时才停服。既避免“每个类起停一次”，又保证
 * fork 不会因为服务线程还活着而卡住——这正是不能用“永远不停服”的原因。
 *
 * <h2>为什么停服务要用 {@code stopBlock}</h2>
 * {@code Solon.stop()} 会把停止流程丢到新线程里，而那条线程最后调 {@code System.exit(0)}，
 * 在 surefire 的 fork 里等于杀掉测试 JVM（DEBUG_LOG DBG-8）。{@code stopBlock(false, 0)}
 * 走同一条停止路径，但不阻塞、不退出。
 *
 * <h2>为什么每个测试类结束后都要清系统属性</h2>
 * 带凭证的配置是通过系统属性传进去的，而 surefire 会把 fork 的系统属性快照写进
 * {@code target/surefire-reports/*.xml}；fork 被复用，后面的测试类报告里也会带上它们，
 * 库口令就这样进了构建产物（DEBUG_LOG DBG-12）。服务此时已经把配置读进对象图，
 * 清掉属性不影响已启动的实例，因此“每个类收尾都清”既安全又彻底。
 */
public abstract class ApiTestHarness extends HttpTester {

    protected static final ObjectMapper JSON = new ObjectMapper();

    protected static final String ADMIN_EMAIL = "admin@example.com";
    protected static final String ADMIN_PASSWORD = "Admin123456!";
    protected static final String BIDDER_A_EMAIL = "bidder_a@example.com";
    protected static final String BIDDER_B_EMAIL = "bidder_b@example.com";
    protected static final String BIDDER_PASSWORD = "Test123456!";

    protected static final String ADMIN_ID = "usr_admin";
    protected static final String BIDDER_A_ID = "usr_bidder_a";
    protected static final String BIDDER_B_ID = "usr_bidder_b";

    /**
     * 起服务／停服务的扩展入口。
     *
     * <p>为什么用 {@code @RegisterExtension} 而不是在 {@code @BeforeAll} 里做：
     * 要把“整轮测试结束时停服”挂到**根上下文**上，就必须拿到 {@link ExtensionContext}；
     * 而 JUnit 不为 {@code @BeforeAll} 方法的参数注入它（试过，报
     * {@code No ParameterResolver registered}）。扩展的回调天然带上下文，于是用扩展。
     *
     * <p>静态字段会被子类继承，因此 HTTP 与 WS 两组用例共享同一个扩展实例。
     */
    @RegisterExtension
    public static final TestServerExtension SERVER_EXTENSION = new TestServerExtension();

    private static final Object lock = new Object();
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ApiTestHarness.class);
    private static final String SERVER_KEY = "bid-arena-test-server";

    protected static DataSource ds;
    protected static int port;
    protected static int wsPort;
    /** Agent API 的独立监听端口（P5）。与 {@code port} 分开是刻意的：它上面只存在 Agent 接口。 */
    protected static int agentPort;

    static {
        // JDK 的 HttpURLConnection 默认把 Origin、Access-Control-Request-Method/Headers
        // 列为"受限头"并**静默丢弃**。不打开这个开关，预检请求会变成
        // "没有 Origin、也没有 Request-Method"的普通 OPTIONS，服务端于是按普通请求 405 拒绝，
        // 测试会以为是 CORS 代码写错了（DEBUG_LOG DBG-9）。
        // 必须在 HttpURLConnection 类初始化之前设置，所以放在静态块而不是 @BeforeAll。
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    // ---------------------------------------------------------------- 生命周期

    @BeforeAll
    static void verifyServerPort() {
        // 扩展（@RegisterExtension 的 beforeAll）先于 @BeforeAll 方法运行，所以这里能直接断言。
        assertEquals(port, Solon.cfg().serverPort(), "服务没有按测试指定的端口启动，后续请求会打到别处");
    }

    @AfterAll
    static void clearCredentials() {
        // 见类注释 DBG-12：不把带凭证的系统属性留给后续测试类的报告。
        System.clearProperty("DB_URL");
        System.clearProperty("DB_USER");
        System.clearProperty("DB_PASSWORD");
        System.clearProperty("JWT_SECRET");
        System.clearProperty("AGENT_SERVER_PORT");
    }

    /**
     * 把“本 JVM 的服务”存进根上下文：构造即启动，根上下文关闭时（整轮测试结束）停服。
     *
     * <p>存进去的动作必须幂等：每个测试类的 {@code beforeAll} 都会跑一次，
     * 而 {@code getOrComputeIfAbsent} 保证只创建一次。
     */
    public static final class TestServerExtension implements BeforeAllCallback {

        @Override
        public void beforeAll(ExtensionContext context) {
            synchronized (lock) {
                context.getRoot().getStore(NAMESPACE)
                        .getOrComputeIfAbsent(SERVER_KEY, key -> new ServerResource(), ServerResource.class);
            }
        }
    }

    /**
     * “一个测试 JVM 一个服务”的持有者：构造即启动，整轮测试结束时（根上下文关闭）停服。
     *
     * <p>它必须是 {@link ExtensionContext.Store.CloseableResource}，而不是靠 {@code @AfterAll}：
     * 后者的执行时机是“每个类结束”，于是又变成每个类起停一次了。
     */
    private static final class ServerResource implements ExtensionContext.Store.CloseableResource {

        private ServerResource() {
            try {
                startServerOnce();
            } catch (Exception e) {
                throw new IllegalStateException("测试服务启动失败", e);
            }
        }

        @Override
        public void close() {
            Solon.stopBlock(false, 0);
        }
    }

    private static void startServerOnce() throws Exception {
        ds = TestDatabase.dataSource();
        // 这些配置平时来自 .env，测试里用系统属性给出：Env 的读取顺序是系统属性优先，
        // 因此不必为了跑测试去改环境变量（改环境变量会影响同机上的其它进程）。
        System.setProperty("DB_URL", require("BID_ARENA_TEST_DB_URL"));
        System.setProperty("DB_USER", require("BID_ARENA_TEST_DB_USER"));
        System.setProperty("DB_PASSWORD", require("BID_ARENA_TEST_DB_PASSWORD"));
        System.setProperty("JWT_SECRET", "bidarena-http-test-secret-0123456789abcdef");
        System.setProperty("SETTLE_SCAN_INTERVAL_MS", "3600000");
        System.setProperty("SETTLE_BATCH_SIZE", "50");
        // 两个后台调度器也要调成"几乎不会再触发"（D-35/D-36）：它们会与用例的手工
        // startDueScheduled()/tick() 抢跑——用例刚建好代理，后台那一轮就可能先出价，
        // 断言会变成随机结果。间隔设成 1 小时，整个测试轮次里只有启动时那一次空轮。
        System.setProperty("AUCTION_START_SCAN_INTERVAL_MS", "3600000");
        System.setProperty("AGENT_PROXY_TICK_INTERVAL_MS", "3600000");
        System.setProperty("CORS_ORIGINS", "http://localhost:5173");

        // 端口用随机空闲端口，避免与开发机上跑着的实例撞车。
        // 注意这里设的是 server.port 而不是 SERVER_PORT：app.yml 里的
        // ${SERVER_PORT:8080} 那种占位符只认**环境变量**，测试里改不动；
        // 而 SolonProps 会把系统属性里与 app.yml 同名的键盖上去（见 SolonProps.loadInit），
        // 所以 server.port 是测试唯一能可靠改到端口的方式。（DEBUG_LOG DBG-10）
        port = freePort();
        System.setProperty("server.port", String.valueOf(port));
        // WebSocket 端口显式指定：默认是 HTTP 端口 + 10000，测试里要能确定地连上去。
        wsPort = freePort();
        System.setProperty("server.websocket.port", String.valueOf(wsPort));
        // Agent 端口同样用随机空闲端口。注意这里读的是环境变量名 AGENT_SERVER_PORT
        // （Application 里用 Env 读它），而 Env 的优先级是「系统属性 > 环境变量」，
        // 所以用系统属性设进去同样生效，不必去改测试机的环境变量。
        agentPort = freePort();
        System.setProperty("AGENT_SERVER_PORT", String.valueOf(agentPort));

        Application.main(new String[0]);
        awaitHealthy();
        awaitAgentHealthy();
    }

    /**
     * 每个用例前把库复位成三个演示账号。
     *
     * <p>用"整库清理 + 重建"而不是逐个用例自己收拾：集成测试里一次出价会同时改
     * 钱包、流水、冻结、出价、参与记录，逐个清理迟早会漏掉一张表，
     * 而漏掉的症状是**别的用例**莫名其妙地失败。
     */
    @BeforeEach
    void seedDemoUsers() {
        TestDatabase.wipe();
        Fixtures.userWithPassword(ds, ADMIN_ID, ADMIN_EMAIL, ADMIN_PASSWORD, "ADMIN", 1000);
        Fixtures.userWithPassword(ds, BIDDER_A_ID, BIDDER_A_EMAIL, BIDDER_PASSWORD, "BIDDER", 1000);
        Fixtures.userWithPassword(ds, BIDDER_B_ID, BIDDER_B_EMAIL, BIDDER_PASSWORD, "BIDDER", 1000);
    }

    // ---------------------------------------------------------------- HTTP 工具

    /** 一次响应的最小快照：状态码、解析后的 JSON、原始文本（断言失败时打印它）、响应头。 */
    protected record Resp(int code, JsonNode body, String raw, Map<String, String> headers) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }

        public String data(String pointer) {
            return body.at("/data/" + pointer).asText();
        }

        public long dataLong(String pointer) {
            return body.at("/data/" + pointer).asLong();
        }
    }

    protected Resp call(String method, String url, String token, String jsonBody, String... headers) {
        return execute(path(url), url, method, token, jsonBody, headers);
    }

    /**
     * 打到 <b>Agent 端口</b>（默认只提供 {@code /api/v1/agent/**}）的请求。
     *
     * <p>与 {@link #call} 分开而不是给它加一个端参：Agent 端口的边界本身就是被测对象
     * （“同一个路径在 8080 上存在、在 8090 上不存在”），把两者写在同一个方法里
     * 会让每一处调用都要问一遍“这次用的是哪个端口”。
     */
    protected Resp agentCall(String method, String url, String agentToken, String jsonBody, String... headers) {
        return execute(HttpUtils.http("http://localhost:" + agentPort + url), url, method, agentToken, jsonBody,
                headers);
    }

    private static Resp execute(HttpUtils http, String url, String method, String token, String jsonBody,
            String... headers) {
        if (token != null) {
            http = http.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            http = http.header(headers[i], headers[i + 1]);
        }
        if (jsonBody != null) {
            http = http.bodyJson(jsonBody);
        }
        try (HttpResponse resp = http.exec(method)) {
            String raw = resp.bodyAsString();
            Map<String, String> map = new HashMap<>();
            for (String name : resp.headerNames()) {
                map.put(name.toLowerCase(), resp.header(name));
            }
            JsonNode parsed = raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
            return new Resp(resp.code(), parsed, raw, map);
        } catch (IOException e) {
            throw new UncheckedIOException("请求失败 " + method + " " + url + "：" + e.getMessage(), e);
        }
    }

    protected static void assertCode(String expected, Resp resp) {
        assertEquals(expected, resp.body().path("code").asText(),
                "业务码不符，实际响应：" + resp.raw());
    }

    protected String login(String email, String password) {
        Resp resp = call("POST", "/api/v1/auth/login", null, json("email", email, "password", password));
        assertEquals(200, resp.code(), resp.raw());
        return resp.body().at("/data/accessToken").asText();
    }

    protected String adminToken() {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    protected String bidderToken() {
        return login(BIDDER_A_EMAIL, BIDDER_PASSWORD);
    }

    protected String bidderTokenB() {
        return login(BIDDER_B_EMAIL, BIDDER_PASSWORD);
    }

    protected String createAuction(String title, long startPrice, long minIncrement, int durationSeconds) {
        Resp resp = call("POST", "/api/v1/admin/auctions", adminToken(),
                createAuctionJson(title, startPrice, minIncrement, durationSeconds));
        assertEquals(201, resp.code(), resp.raw());
        String id = resp.body().at("/data/id").asText();
        assertFalse(id.isEmpty(), resp.raw());
        return id;
    }

    protected String createRunningAuction(String title, long startPrice, long minIncrement, int durationSeconds) {
        String id = createAuction(title, startPrice, minIncrement, durationSeconds);
        Resp started = call("POST", "/api/v1/admin/auctions/" + id + "/start", adminToken(), null);
        assertEquals(200, started.code(), started.raw());
        assertEquals("RUNNING", started.body().at("/data/status").asText());
        return id;
    }

    protected void join(String auctionId, String token) {
        Resp resp = call("POST", "/api/v1/auctions/" + auctionId + "/join", token, null);
        assertEquals(200, resp.code(), resp.raw());
    }

    /** 出价（返回响应，由用例自己断言成败：失败也是被测行为）。 */
    protected Resp placeBid(String auctionId, String token, String requestId, long amount) {
        return call("POST", "/api/v1/auctions/" + auctionId + "/bids", token,
                json("requestId", requestId, "amount", amount));
    }

    /** 取当前快照（含 {@code seq}）。 */
    protected Resp snapshot(String auctionId, String token) {
        return call("GET", "/api/v1/auctions/" + auctionId, token, null);
    }

    // ---------------------------------------------------------------- WebSocket 工具

    /** {@code POST /auth/ws-tickets} 的结果：票 + 服务端告诉客户端的连接坐标。 */
    protected record WsTicket(String ticket, String expiresAt, String wsPath, int wsPort) {

        /** 拼出可直接连接的地址。{@code ticket} 是 base64url，不需要再编码。 */
        public String url(String host, String auctionId) {
            return "ws://" + host + ":" + wsPort + "/ws/auctions/" + auctionId + "?ticket=" + ticket;
        }
    }

    protected WsTicket wsTicket(String token) {
        Resp resp = call("POST", "/api/v1/auth/ws-tickets", token, null);
        assertEquals(200, resp.code(), resp.raw());
        assertEquals("OK", resp.body().path("code").asText(), resp.raw());
        return new WsTicket(resp.data("ticket"), resp.data("expiresAt"), resp.data("wsPath"), (int) resp.dataLong("wsPort"));
    }

    // ---------------------------------------------------------------- 静态辅助

    protected static String createAuctionJson(String title, long startPrice, long minIncrement, int durationSeconds) {
        return "{\"title\":\"" + title + "\",\"description\":\"测试描述\",\"startPrice\":" + startPrice
                + ",\"minIncrement\":" + minIncrement + ",\"durationSeconds\":" + durationSeconds + "}";
    }

    /**
     * 带预告开拍时间的创建请求（D-35）。
     *
     * <p>单独一个方法而不是给上面的方法加参数：绝大多数用例不排期，
     * 加一个 {@code null} 参数会让每一处调用都要回答"这次排期了吗"。
     */
    protected static String createScheduledAuctionJson(
            String title, long startPrice, long minIncrement, int durationSeconds, String startsAt) {
        return "{\"title\":\"" + title + "\",\"description\":\"测试描述\",\"startPrice\":" + startPrice
                + ",\"minIncrement\":" + minIncrement + ",\"durationSeconds\":" + durationSeconds
                + ",\"startsAt\":\"" + startsAt + "\"}";
    }

    /**
     * 拼 JSON。字符串值自动加引号，数字/布尔原样写入。
     *
     * <p>刻意不接受"预拼好的 JSON 片段"：早先的版本把字符串值当成裸值拼进去，
     * 生成 {@code "password":Admin123456!} 这种非法 JSON，而服务端只会回一个
     * 500，排查成本比在这里多写两行高得多。
     */
    protected static String json(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keyValues[i]).append("\":");
            Object value = keyValues[i + 1];
            if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(value).append('"');
            }
        }
        return sb.append('}').toString();
    }

    protected static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            fail("缺少环境变量 " + name + "，本测试需要真实 MySQL（见 README 的一键验证节）");
        }
        return value;
    }

    /**
     * 取一个空闲端口，但**刻意避开高位端口**。
     *
     * <p>Windows 的临时端口段一直在 49152–65535，{@code new ServerSocket(0)} 抽到的端口
     * 有机会落在 55536 以上；一旦将来有人去掉显式的 WS 端口配置、回退到
     * "HTTP 端口 + 10000"的默认行为，就会超过 65535 而直接起不来。
     * 固定在一个较低的区间内探测，两边都能绑上。
     */
    protected static int freePort() {
        for (int attempt = 0; attempt < 50; attempt++) {
            int candidate = 40000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9000);
            try (ServerSocket probe = new ServerSocket(candidate)) {
                return probe.getLocalPort();
            } catch (IOException busy) {
                // 被占用就换一个，50 次足够。
            }
        }
        throw new IllegalStateException("在 40000-49000 区间内找不到空闲端口");
    }

    private static void awaitHealthy() {
        for (int attempt = 0; attempt < 100; attempt++) {
            try (HttpResponse resp = HttpUtils.http("http://localhost:" + port + "/api/v1/health").exec("GET")) {
                if (resp.code() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // 端口还没起来是正常的，继续等。
            }
            pause(100);
        }
        throw new IllegalStateException("服务在 10 秒内没有在端口 " + port + " 上就绪");
    }

    /**
     * 等 Agent 端口就绪。
     *
     * <p>拿一个无凭证的 Agent 请求当探针：只要监听器起来了，就一定会得到一个 401 封套；
     * 连接被拒则说明它根本没起来（Plugin 没注册、端口被占、或启动顺序变了），
     * 而这时的报错信息必须是“Agent 端口没起来”，而不是某个用例里莫名其妙的
     * {@code ConnectException}。
     */
    private static void awaitAgentHealthy() {
        String probe = "http://localhost:" + agentPort + "/api/v1/agent/auctions/probe";
        for (int attempt = 0; attempt < 100; attempt++) {
            try (HttpResponse resp = HttpUtils.http(probe).exec("GET")) {
                if (resp.code() == 401) {
                    return;
                }
            } catch (Exception ignored) {
                // 同上。
            }
            pause(100);
        }
        throw new IllegalStateException("Agent API 在 10 秒内没有在端口 " + agentPort + " 上就绪");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待服务启动时被中断", e);
        }
    }
}
