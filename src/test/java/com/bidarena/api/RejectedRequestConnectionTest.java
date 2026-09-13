package com.bidarena.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.bidarena.support.ApiTestHarness;
import com.bidarena.support.RawHttp;
import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 传输层保证：**提前拒绝一个带请求体的请求之后，这条连接仍然是同步的**。
 *
 * <h2>被测的到底是什么</h2>
 * 服务端会在读请求体之前就回写 401/403（鉴权失败）。此时请求体还躺在内核缓冲区里。
 * 如果没人把它读掉，HTTP/1.1 的 keep-alive 连接就错位了：下一次请求会被解析成
 * "上一段的请求体 + 本次请求行"。{@code DEBUG_LOG} DBG-23 记录了这个现象：
 * 一次针对管理员接口的 401 之后，同一个连接上的登录请求收到了
 * {@code 缺少 Bearer 令牌}——因为服务端把它看成了
 * {@code method={"name":"…"}POST}、{@code path=/api/v1/auth/login}。
 *
 * <h2>为什么手工拼 HTTP</h2>
 * 要复现它，必须让"401 的请求带体"与"下一条请求复用同一条 TCP 连接"同时成立。
 * 走 {@code HttpUtils} 连接池时后者由池自己决定，测试会变成概率性的。
 * 用 {@link RawHttp} 手工发两条请求，就把这件事变成确定性的：
 * 要么第二条拿到 200，要么这条连接已经不可用——两者都说明边界是清楚的。
 */
@DisplayName("提前拒绝后的连接复用")
class RejectedRequestConnectionTest extends ApiTestHarness {

    @Test
    @DisplayName("鉴权失败的带体请求之后，同一条连接上的下一个请求仍被正确解析")
    void rejectedBodyDoesNotDesyncConnection() throws IOException {
        try (RawHttp http = new RawHttp("localhost", port, 5000)) {
            // 第一条：带一个非空 JSON 体的管理员请求，凭证是伪造的 → 服务端在进入控制器前就拒绝。
            http.send("POST", "/api/v1/admin/auctions", "{\"title\":\"连接复用\",\"startPrice\":100}",
                    "Authorization: Bearer 这不是一个有效的令牌");
            RawHttp.Response rejected = http.read();
            assertEquals(401, rejected.status(), rejected.rawBody());
            assertTrue(rejected.rawBody().contains("UNAUTHENTICATED"), rejected.rawBody());

            // 第二条：同一条连接、同样的写法，只把路径换成公开的登录。
            // 若服务端没读掉上一条的请求体，这里会拿到 401（请求行被污染），而不是令牌。
            http.send("POST", "/api/v1/auth/login",
                    "{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}");
            RawHttp.Response login = readOrFail(http, "同一条 keep-alive 连接上的第二次请求");

            assertEquals(200, login.status(),
                    "提前拒绝后连接被污染了：第二条请求被解析成了别的东西 -> " + login.rawBody());
            assertTrue(login.rawBody().contains("accessToken"), login.rawBody());
        }
    }

    @Test
    @DisplayName("连着拒绝多条带体请求，连接依然可用（不是只对第一条生效）")
    void repeatedRejectionsKeepConnectionUsable() throws IOException {
        try (RawHttp http = new RawHttp("localhost", port, 5000)) {
            for (int i = 1; i <= 3; i++) {
                http.send("POST", "/api/v1/auctions/auc_不存在/bids",
                        "{\"requestId\":\"raw-" + i + "\",\"amount\":110}");
                RawHttp.Response rejected = http.read();
                assertEquals(401, rejected.status(), rejected.rawBody());
            }

            http.send("POST", "/api/v1/auth/login",
                    "{\"email\":\"" + BIDDER_A_EMAIL + "\",\"password\":\"" + BIDDER_PASSWORD + "\"}");
            RawHttp.Response login = readOrFail(http, "连续三条被拒之后的登录");

            assertEquals(200, login.status(), login.rawBody());
            assertTrue(login.rawBody().contains("accessToken"), login.rawBody());
        }
    }

    /**
     * 读取响应；若连接已经不可用（服务端选择了关闭），用例失败并说明是哪一步。
     *
     * <p>把"连接没了"翻译成人话：这样失败信息指向被测行为（提前拒绝破坏了连接），
     * 而不是扔一个 {@link SocketTimeoutException} 让读日志的人自己去猜。
     */
    private static RawHttp.Response readOrFail(RawHttp http, String step) throws IOException {
        try {
            return http.read();
        } catch (IOException e) {
            fail(step + "没有拿到完整响应，连接在提前拒绝之后已不可复用：" + e);
            throw e;
        }
    }
}
