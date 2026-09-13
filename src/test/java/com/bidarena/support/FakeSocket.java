package com.bidarena.support;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.websocket.WebSocket;

/**
 * 测试用的假连接：把 {@code send} 的内容记下来，不做任何网络动作。
 *
 * <h2>为什么不用真 WebSocket</h2>
 * {@code WsEventBroadcaster} 要验证的是"发给谁、发几次、失败了怎么办"，
 * 这些是**选择与计数**的逻辑，与网络无关。用真连接就要处理"帧什么时候到"这种
 * 时间不确定性，测试会变得既慢又偶发。真连接该测的东西（握手、票、快照）
 * 在 {@code WsIntegrationTest} 里有一条完整的路径。
 *
 * <p>{@code send} 默认成功并异步完成；{@link #failNextSend()} 可以让下一次发送
 * 变成"底层抛异常"或"Future 异常完成"（Solon 的实现是后者，这里两种都支持，
 * 因为两种都会出现在真实实现里）。
 */
public class FakeSocket implements WebSocket {

    private final String id;
    private final String url;
    private final MultiMap<String> params = new MultiMap<>();
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final Map<String, Object> attrs = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile RuntimeException sendFailure;
    private volatile boolean failFuture = true;

    public FakeSocket(String id, String url) {
        this.id = id;
        this.url = url;
    }

    /** 让下一次 {@code send} 失败：{@code asException=true} 时直接抛，否则让 Future 异常完成。 */
    public void failNextSend(boolean asException) {
        this.sendFailure = new IllegalStateException("模拟发送失败");
        this.failFuture = !asException;
    }

    public List<String> sent() {
        return new ArrayList<>(sent);
    }

    public boolean isClosed() {
        return closed;
    }

    public void kill() {
        valid.set(false);
    }

    // ---------------------------- 被测逻辑真正用到的部分 ----------------------------

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isValid() {
        return valid.get();
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public String param(String name) {
        return params.get(name);
    }

    @Override
    public void param(String name, String value) {
        params.add(name, value);
    }

    @Override
    public Future<Void> send(String text) {
        RuntimeException failure = sendFailure;
        if (failure != null) {
            sendFailure = null;
            if (!failFuture) {
                throw failure;
            }
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(failure);
            return failed;
        }
        sent.add(text);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        closed = true;
        valid.set(false);
    }

    // ---------------------------- 未被使用的接口 ----------------------------

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public String path() {
        return url;
    }

    @Override
    public void pathNew(String path) {
        throw new UnsupportedOperationException("测试不需要");
    }

    @Override
    public MultiMap<String> paramMap() {
        return params;
    }

    @Override
    public String paramOrDefault(String name, String def) {
        String value = params.get(name);
        return value == null ? def : value;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return null;
    }

    @Override
    public InetSocketAddress localAddress() {
        return null;
    }

    @Override
    public Map<String, Object> attrMap() {
        return attrs;
    }

    @Override
    public boolean attrHas(String name) {
        return attrs.containsKey(name);
    }

    @Override
    public <T> T attr(String name) {
        return (T) attrs.get(name);
    }

    @Override
    public <T> T attrOrDefault(String name, T def) {
        return attrs.containsKey(name) ? (T) attrs.get(name) : def;
    }

    @Override
    public <T> void attr(String name, T value) {
        attrs.put(name, value);
    }

    @Override
    public long getIdleTimeout() {
        return 0;
    }

    @Override
    public void setIdleTimeout(long timeout) {
        // 测试不关心
    }

    @Override
    public Future<Void> send(ByteBuffer buffer) {
        throw new UnsupportedOperationException("测试不需要");
    }
}
