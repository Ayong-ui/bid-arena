package com.bidarena.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手工拼 HTTP/1.1 的最小客户端，<b>只用来验证传输层行为</b>。
 *
 * <h2>为什么不能用 {@code ApiTestHarness.call}</h2>
 * 那一层走 Solon 自带的 {@code HttpUtils}，它自己维护连接池，测试无法指定
 * "这条请求必须复用上一条请求的 TCP 连接"。而本项目有一类故障只在这种复用下才出现
 * （提前拒绝 + 未读完的请求体 → 连接错位，见 {@code DEBUG_LOG} DBG-23）：
 * 用连接池去撞它是概率性的，同一段代码时红时绿，比不写这个测试更糟。
 *
 * <p>拿一个裸 socket 手工发两条请求，就把它变成了确定性行为：写什么、读什么、
 * 是否复用同一条连接，全部由测试说了算。
 *
 * <p>只实现本项目用得到的最小面：请求行 + 头 + 定长体，
 * 响应支持 {@code Content-Length} 与 {@code Transfer-Encoding: chunked} 两种长度表达。
 * 不做重定向、压缩、100-continue——它们是生产客户端的事，不是被测行为。
 */
public final class RawHttp implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public RawHttp(String host, int port, int soTimeoutMillis) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(soTimeoutMillis);
        // 关掉 Nagle：请求要尽快发出去，才能稳定复现"响应先于请求体到达"的时序。
        this.socket.setTcpNoDelay(true);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /**
     * 发一条请求。请求行、头、体**一次写出**，不做多余的 flush 时机拆分。
     *
     * <p>{@code headers} 按 {@code "Name: value"} 成对给出；{@code Host}、{@code Content-Length}
     * 由本方法补齐——它们是协议要求，不是测试想表达的内容。
     */
    public void send(String method, String path, String jsonBody, String... headers) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        boolean hasHost = false;
        boolean hasLength = false;
        for (String header : headers) {
            head.append(header).append("\r\n");
            String lower = header.toLowerCase();
            hasHost |= lower.startsWith("host:");
            hasLength |= lower.startsWith("content-length:");
        }
        if (!hasHost) {
            head.append("Host: localhost\r\n");
        }
        byte[] body = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
        if (jsonBody != null && !hasLength) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: keep-alive\r\n\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** 读一条完整响应，并保留下一条（连接可继续复用）。 */
    public Response read() throws IOException {
        String head = readUntilHeaderEnd();
        String[] lines = head.split("\r\n");
        int status;
        try {
            status = Integer.parseInt(lines[0].split(" ")[1]);
        } catch (RuntimeException notAStatusLine) {
            // 报文头不像响应：直接把原文贴进异常，否则又要靠猜。
            throw new IOException("响应头无法解析，原始内容是：[" + head + "]", notAStatusLine);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(),
                        lines[i].substring(colon + 1).trim());
            }
        }
        byte[] body = headers.containsKey("transfer-encoding")
                ? readChunkedBody()
                : readFixedBody(Long.parseLong(headers.getOrDefault("content-length", "0")));
        return new Response(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    /** 读报文头：逐行读到空行（空行就是头的结束），返回不含结尾空行的各行。 */
    private String readUntilHeaderEnd() throws IOException {
        StringBuilder head = new StringBuilder();
        while (true) {
            String line = readLine();
            if (line.isEmpty()) {
                return head.toString();
            }
            if (head.length() > 0) {
                head.append("\r\n");
            }
            head.append(line);
        }
    }

    /** 读一行（含行尾 CRLF，返回时去掉）。连接提前结束时抛错并带上已读内容。 */
    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("连接在读完整行之前就结束了（已读 " + buffer.size() + " 字节："
                        + buffer.toString(StandardCharsets.ISO_8859_1) + "）");
            }
            if (b == '\n') {
                String line = buffer.toString(StandardCharsets.ISO_8859_1);
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            buffer.write(b);
        }
    }

    private byte[] readFixedBody(long length) throws IOException {
        byte[] body = new byte[(int) length];
        int offset = 0;
        while (offset < body.length) {
            int read = in.read(body, offset, body.length - offset);
            if (read < 0) {
                throw new IOException("连接在读完响应体之前就结束了（已读 " + offset + "/" + length + "）");
            }
            offset += read;
        }
        return body;
    }

    /** 分块编码：逐块读长度行、数据、以及块尾的 CRLF，直到长度为 0 的块。 */
    private byte[] readChunkedBody() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            // 块长度行可能带扩展（如 "7c;name=x"），本项目用不到，按协议忽略扩展部分。
            int size = Integer.parseInt(readLine().split(";")[0].trim(), 16);
            if (size == 0) {
                readLine(); // 末块之后的空行（本项目没有 trailer 头）
                return body.toByteArray();
            }
            body.write(readFixedBody(size));
            readLine(); // 块数据后的 CRLF
        }
    }

    /** 直接关闭 socket，不复用。 */
    @Override
    public void close() throws IOException {
        socket.close();
    }

    /** 一次响应的快照。{@code rawBody} 是响应体原文（本项目接口返回 JSON）。 */
    public record Response(int status, Map<String, String> headers, String rawBody) {

        public boolean headerContains(String name, String text) {
            String value = headers.get(name.toLowerCase());
            return value != null && value.toLowerCase().contains(text.toLowerCase());
        }
    }
}
