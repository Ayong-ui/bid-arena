#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""竞拍全链路模拟：并发同/邻价、requestId 重试、拒绝场景、最后五秒狙击、断线快照、结束核对。

为什么要有它（而不是只留后端集成测试）：
    这些事实大多**只在真实并发下才成立**：20 条请求同时挤进同一条出价事务时，
    到底有几条被接受、冻结有没有被重复加、幂等键重放会不会多冻结一次。
    集成测试已经在测试基座里断言过它们，但那是随机端口 + 测试库；
    评审要“自己动手复现一遍”并不方便。本脚本把同一批事实变成一条命令：
    真实 HTTP、真实 MySQL、真实 WebSocket，跑完打印“期望 vs 实际”，任一条不符就非零退出。

它做八件事：
    1. 准备：管理员创建并开拍，两个竞拍者加入；
    2. 20 条**同价**并发出价：必须恰好 1 条被接受，其余全部 BID_TOO_LOW；
    3. 20 条**邻价**（120/130）并发出价：最终价必为最高价，且同一价位只会成交一笔；
       （注意：邻价并发**允许**成交两笔——某笔 120 先成交，随后某笔 130 合法抬价；
       契约只保证“同一价位至多一笔”和“最终价 = 最高报价”，不保证“全局只接受一笔”。）
    4. 同一用户用同一 requestId 并发重试 20 次：恰好 1 次真实写入，其余为重放，
       冻结只加一次。幂等键是 **(拍卖, 用户, requestId)**——换个用户复用同一串不算重放。
    5. 拒绝场景：低于最小加价 / 金额非法 / 缺 requestId / 未加入；
    6. 最后五秒狙击：截止前 5 秒内出价触发 +10 秒延时，最多 3 次；
    7. 实时通道：连上先收到权威快照，提交后收到 BID_ACCEPTED（seq 前进、领先者为匿名值），
       断开后用新票重连能重新对齐到最新价；
    8. 结束核对：到期结算后，结果里的赢家/成交价与钱包余额、冻结释放三者一致。

局限（如实声明，不假装覆盖）：
    公开 API **没有注册用户的端点**，种子数据只有 3 个演示账号，因此“20 个不同用户并发”
    无法只靠 HTTP 复现。本脚本用“20 条并发出价请求（跨可用账号、唯一 requestId）”等价模拟
    并发压力；真正“20 个不同 user_id 的并发”由后端 `BidConcurrencyTest` 覆盖。

依赖：只用 Python 标准库（含一个最小 RFC 6455 客户端，见 WsClient）。后端需先按 README 启动。

用法：
    python tools/auction_sim.py                 # 全部八个阶段
    python tools/auction_sim.py --quick         # 跳过最慢的狙击阶段（约省 35 秒）
    python tools/auction_sim.py --base http://192.168.1.10:8080/api/v1
    python tools/auction_sim.py --keep          # 结束时不取消拍卖，便于在前端继续观察
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

# 与 db/migration/V1、V3 的种子数据一致（README 的演示账号）。
ADMIN_EMAIL = "admin@example.com"
ADMIN_PASSWORD = "Admin123456!"
BIDDER_A = ("bidder_a@example.com", "Test123456!")
BIDDER_B = ("bidder_b@example.com", "Test123456!")

# 出价事务的规则常量（对应 BidService.EXTENSION_*），模拟要把它们当契约来断言。
EXTENSION_WINDOW_SECONDS = 5
EXTENSION_SECONDS = 10
MAX_EXTENSIONS = 3


class Reporter:
    """把每条检查的结论记下来，最后统一决定退出码。"""

    def __init__(self):
        self.passed = 0
        self.failed = []

    def check(self, description, actual, expected, detail=""):
        if actual == expected:
            self.passed += 1
            print("  [OK]   %-44s %s" % (description, actual))
        else:
            self.failed.append((description, expected, actual, detail))
            print("  [FAIL] %-44s expected=%s actual=%s" % (description, expected, actual))
            if detail:
                print("         %s" % _brief(detail))

    def ok(self, description, condition, detail=""):
        self.check(description, bool(condition), True, detail)

    def summary(self):
        total = self.passed + len(self.failed)
        print("\n---- %d/%d checks passed ----" % (self.passed, total))
        for description, expected, actual, detail in self.failed:
            print("FAILED: %s (expected=%s, actual=%s) %s" % (description, expected, actual, _brief(detail)))
        return 0 if not self.failed else 1


def _brief(value, limit=300):
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, default=str)
    return text if len(text) <= limit else text[:limit] + "..."


def http(base, method, path, token=None, body=None, timeout=15):
    """发一条请求，返回 (http_status, 封套 dict)。

    非 2xx 不当异常抛出：本脚本要断言的恰恰是 409/400 这类拒绝，而契约保证它们也可解析。
    """
    url = base.rstrip("/") + path
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, _parse(resp.read())
    except urllib.error.HTTPError as err:
        return err.code, _parse(err.read())


def _parse(raw):
    text = raw.decode("utf-8", "replace")
    if not text:
        return {}
    try:
        return json.loads(text)
    except ValueError:
        return {"raw": text}


def data_of(envelope):
    return envelope.get("data") or {}


def code_of(envelope):
    return envelope.get("code")


class Api:
    """带基址与 JWT 的小客户端（本脚本只跑用户侧，Agent 侧见 agent_sim.py）。"""

    def __init__(self, base):
        self.base = base
        self.token = None
        self.user = None

    def get(self, path):
        return http(self.base, "GET", path, token=self.token)

    def post(self, path, body=None):
        return http(self.base, "POST", path, token=self.token, body=body)

    def login(self, credentials):
        email, password = credentials
        status, envelope = http(self.base, "POST", "/auth/login",
                               body={"email": email, "password": password})
        if status != 200:
            raise SystemExit("登录失败 %s：%s" % (email, envelope))
        self.token = data_of(envelope)["accessToken"]
        self.user = data_of(envelope)["user"]
        return self.user

    def wallet(self):
        _, envelope = self.get("/wallets/me")
        return data_of(envelope)


def new_request_id(prefix):
    return "%s-%s" % (prefix, uuid.uuid4().hex[:16])


def parse_epoch(text):
    """ISO-8601 时刻转 epoch 秒。两个时刻都取自服务端，相减不受本机时钟影响。"""
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


class WsClient:
    """最小 RFC 6455 客户端：只为验证“先给快照 / 提交有事件 / 重连能对齐”。

    不引第三方库（`websocket-client`）是刻意的：这个脚本要能在任何装了 Python 的机器上直接跑，
    而它需要的只是“读文本帧”这一件事。代价是要自己处理续帧与 ping/pong——见 recv_text。
    """

    def __init__(self, host, port, path, timeout=8.0):
        self.host = host
        self.port = port
        self.path = path
        self.timeout = timeout
        self.sock = None
        self._buf = b""
        self._frag = b""
        self._frag_op = None

    def connect(self):
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            "GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n"
            % (self.path, self.host, self.port, key)
        )
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.settimeout(self.timeout)
        self.sock.sendall(request.encode("ascii"))
        head = self._read_until(b"\r\n\r\n")
        status = head.split(b"\r\n", 1)[0].decode("latin1")
        if " 101 " not in status:
            raise RuntimeError("WebSocket 握手失败：%s" % status)
        return self

    def _read_until(self, marker):
        while marker not in self._buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("连接在握手阶段被关闭")
            self._buf += chunk
        head, _, self._buf = self._buf.partition(marker)
        return head

    def _read_exact(self, count):
        while len(self._buf) < count:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("连接已关闭")
            self._buf += chunk
        out, self._buf = self._buf[:count], self._buf[count:]
        return out

    def _frame(self):
        first, second = self._read_exact(2)
        fin = bool(first & 0x80)
        opcode = first & 0x0F
        length = second & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._read_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read_exact(8))[0]
        if second & 0x80:  # 服务端理应不发掩码帧；发了也照解，免得把问题掩盖成“读不到”
            mask = self._read_exact(4)
            data = bytes(byte ^ mask[i % 4] for i, byte in enumerate(self._read_exact(length)))
        else:
            data = self._read_exact(length)
        return fin, opcode, data

    def recv_text(self):
        """读到一条完整文本消息；自动拼接续帧、忽略/回应 ping。"""
        while True:
            fin, opcode, data = self._frame()
            if opcode == 0x8:
                raise RuntimeError("服务端关闭了连接")
            if opcode == 0x9:
                self._send_frame(0xA, data)
                continue
            if opcode == 0xA:
                continue
            if opcode in (0x1, 0x2):
                self._frag_op, self._frag = opcode, data
            elif opcode == 0x0:
                self._frag += data
            else:
                continue
            if fin:
                if self._frag_op == 0x1:
                    return self._frag.decode("utf-8")
                # 二进制帧：本项目的实时通道只发文本，忽略即可。
                self._frag_op, self._frag = None, b""

    def recv_json(self):
        return json.loads(self.recv_text())

    def _send_frame(self, opcode, payload=b""):
        header = bytes([0x80 | opcode])
        length = len(payload)
        if length < 126:
            header += bytes([0x80 | length])
        elif length < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", length)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", length)
        mask = os.urandom(4)
        masked = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def close(self):
        try:
            if self.sock is not None:
                self._send_frame(0x8, b"")
        except OSError:
            pass
        finally:
            if self.sock is not None:
                try:
                    self.sock.close()
                except OSError:
                    pass
                self.sock = None


def next_event(ws, wanted, max_frames=30):
    """读到第一个指定类型的事件，跳过其它事件（例如 CONNECTION_STATE）。"""
    for _ in range(max_frames):
        event = ws.recv_json()
        if event.get("type") == wanted:
            return event
    raise RuntimeError("没有在 %d 帧内等到事件 %s" % (max_frames, wanted))


# ---------------------------------------------------------------------------
# 阶段
# ---------------------------------------------------------------------------

def create_and_start(admin, title, start_price, increment, duration):
    status, envelope = admin.post("/admin/auctions", {
        "title": "%s %s" % (title, datetime.now().strftime("%H:%M:%S")),
        "description": "由 tools/auction_sim.py 创建",
        "startPrice": start_price, "minIncrement": increment, "durationSeconds": duration,
    })
    auction_id = data_of(envelope).get("id")
    _, envelope = admin.post("/admin/auctions/%s/start" % auction_id)
    return data_of(envelope)


def snapshot_of(admin, auction_id):
    _, envelope = admin.get("/auctions/%s" % auction_id)
    return data_of(envelope)


def run_concurrent(jobs, workers=20):
    """并发执行 jobs（每项是一个 () -> (status, envelope) 的调用）。"""
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(lambda job: job(), jobs))


def count_codes(results):
    return dict(Counter(code_of(envelope) for _, envelope in results))


def bid_job(api, auction_id, amount, request_id):
    return lambda: api.post("/auctions/%s/bids" % auction_id,
                            {"requestId": request_id, "amount": amount})


def concurrency_phase(report, admin, a, b):
    print("\n== 1. 准备：创建并开拍 ==")
    auction = create_and_start(admin, "并发模拟", 100, 10, 60)
    auction_id = auction["id"]
    report.check("起始价", auction["startPrice"], 100)

    # 未加入就必须出价：验证“先加入再出价”的前置条件（必须在 join 之前测）。
    status, envelope = b.post("/auctions/%s/bids" % auction_id,
                              {"requestId": new_request_id("nj"), "amount": 110})
    report.check("未加入即出价 -> 409", status, 409, envelope)
    report.check("错误码 NOT_JOINED", code_of(envelope), "NOT_JOINED", envelope)

    a.post("/auctions/%s/join" % auction_id)
    b.post("/auctions/%s/join" % auction_id)
    wallet_a0 = a.wallet()
    wallet_b0 = b.wallet()

    print("\n== 2. 20 条同价（110）并发出价：恰好 1 条被接受 ==")
    results = run_concurrent([bid_job(a if i % 2 == 0 else b, auction_id, 110, new_request_id("same"))
                              for i in range(20)])
    codes = count_codes(results)
    report.check("恰好 1 条被接受", codes.get("OK", 0), 1, codes)
    report.check("其余 19 条 BID_TOO_LOW", codes.get("BID_TOO_LOW", 0), 19, codes)
    report.check("并发后当前价 = 成交价 110", snapshot_of(admin, auction_id)["currentPrice"], 110)

    print("\n== 3. 20 条邻价（120/130）并发出价：最终价必为最高价 ==")
    results = run_concurrent([bid_job(a if i % 2 == 0 else b, auction_id,
                                      120 if i % 2 == 0 else 130, new_request_id("adj"))
                              for i in range(20)])
    codes = count_codes(results)
    accepted = [data_of(envelope).get("price") for _, envelope in results if code_of(envelope) == "OK"]
    # 邻价并发**可能**接受两笔：某笔 120 先成交（价 120），随后某笔 130 是合法抬价。
    # 但 130 成交之后，120 与其余 130 都低于“130 + 最小加价”，全部被拒。
    report.check("被接受笔数在 1~2 之间", 1 <= len(accepted) <= 2, True, codes)
    report.check("被接受的最高价 = 130", max(accepted) if accepted else None, 130, codes)
    report.check("恰好 1 条 130 被接受（同价位不重复成交）", accepted.count(130), 1, codes)
    report.check("其余全部 BID_TOO_LOW", codes.get("BID_TOO_LOW", 0), 20 - len(accepted), codes)
    report.check("最终价 = 130", snapshot_of(admin, auction_id)["currentPrice"], 130)

    print("\n== 4. 同一用户同一 requestId 并发重试 20 次：只写入一次、只冻结一次 ==")
    duplicate = new_request_id("dup")
    # 幂等键是 (auction_id, user_id, requestId)：必须用**同一个用户**才会互相去重。
    results = run_concurrent([bid_job(a, auction_id, 140, duplicate) for _ in range(20)])
    codes = count_codes(results)
    report.check("恰好 1 条首次接受", codes.get("OK", 0), 1, codes)
    report.check("其余 19 条为幂等重放（不再产生写入）", codes.get("IDEMPOTENCY_REPLAY", 0), 19, codes)
    # 顺序再发一次：这一轮没有并发，结果必须是确定性的重放。
    _, envelope = a.post("/auctions/%s/bids" % auction_id, {"requestId": duplicate, "amount": 140})
    report.check("顺序重试 -> 幂等重放", code_of(envelope), "IDEMPOTENCY_REPLAY", envelope)
    report.check("重放标记 idempotent=true", data_of(envelope).get("idempotent"), True, envelope)
    report.check("重放返回首次成交价 140", data_of(envelope).get("price"), 140, envelope)

    # 资金不变量：整段并发之后，唯一领先者的冻结增量 = 成交价，另一人 = 0。
    # 用“增量”而不是“绝对值”，这样即便库里有别的历史冻结也不会误判。
    final = snapshot_of(admin, auction_id)
    leader = final["leader"]
    wallet_a1, wallet_b1 = a.wallet(), b.wallet()
    if leader == a.user["id"]:
        winner_delta = wallet_a1["frozenAmount"] - wallet_a0["frozenAmount"]
        loser_delta = wallet_b1["frozenAmount"] - wallet_b0["frozenAmount"]
    else:
        winner_delta = wallet_b1["frozenAmount"] - wallet_b0["frozenAmount"]
        loser_delta = wallet_a1["frozenAmount"] - wallet_a0["frozenAmount"]
    report.check("赢家冻结增量 = 成交价（未被重复冻结）", winner_delta, 140)
    report.check("输家冻结增量 = 0（未残留冻结）", loser_delta, 0)
    report.ok("唯一领先者", bool(leader), final)

    # 幂等键含 user_id：换个用户复用同一串 requestId，不算重放，而是一次全新出价。
    _, envelope = b.post("/auctions/%s/bids" % auction_id, {"requestId": duplicate, "amount": 150})
    report.check("另一用户复用同一 requestId 视为新出价（键含 user_id）",
                 code_of(envelope), "OK", envelope)

    print("\n== 5. 拒绝场景 ==")
    status, envelope = a.post("/auctions/%s/bids" % auction_id,
                              {"requestId": new_request_id("low"), "amount": 140})
    report.check("低于“当前价 + 最小加价” -> 409", status, 409, envelope)
    report.check("错误码 BID_TOO_LOW", code_of(envelope), "BID_TOO_LOW", envelope)

    status, envelope = a.post("/auctions/%s/bids" % auction_id,
                              {"requestId": new_request_id("zero"), "amount": 0})
    report.check("金额为 0 -> 400", status, 400, envelope)
    report.check("错误码 VALIDATION_FAILED", code_of(envelope), "VALIDATION_FAILED", envelope)

    status, envelope = a.post("/auctions/%s/bids" % auction_id, {"amount": 150})
    report.check("缺 requestId -> 400", status, 400, envelope)

    return auction_id


def wait_until_within_window(admin, auction_id, timeout=25):
    """轮询到“距截止 <= 5 秒”。两个时刻都取自服务端快照，不读本机时钟。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        snap = snapshot_of(admin, auction_id)
        remaining = parse_epoch(snap["endsAt"]) - parse_epoch(snap["serverTime"])
        if remaining <= EXTENSION_WINDOW_SECONDS:
            return snap
        time.sleep(max(0.2, min(remaining - (EXTENSION_WINDOW_SECONDS - 0.5), 1.0)))
    raise RuntimeError("等待进入狙击窗口超时")


def sniping_phase(report, admin, a, b):
    print("\n== 6. 最后五秒狙击：每次 +10 秒，最多 %d 次 ==" % MAX_EXTENSIONS)
    auction = create_and_start(admin, "狙击模拟", 100, 10, 10)
    auction_id = auction["id"]
    a.post("/auctions/%s/join" % auction_id)
    b.post("/auctions/%s/join" % auction_id)

    price = 100
    expected = 0
    for attempt in range(1, MAX_EXTENSIONS + 2):
        before = wait_until_within_window(admin, auction_id)
        price += 10
        api = a if attempt % 2 == 1 else b
        status, envelope = api.post("/auctions/%s/bids" % auction_id,
                                    {"requestId": new_request_id("snipe"), "amount": price})
        report.check("第 %d 次狙击被接受" % attempt, code_of(envelope), "OK", envelope)
        expected = min(expected + 1, MAX_EXTENSIONS)
        report.check("延时次数 = %d" % expected, data_of(envelope).get("extensions"), expected, envelope)
        after = snapshot_of(admin, auction_id)
        if attempt <= MAX_EXTENSIONS:
            report.check("截止时间被推后 +%d 秒" % EXTENSION_SECONDS,
                         round(parse_epoch(after["endsAt"]) - parse_epoch(before["endsAt"])), EXTENSION_SECONDS,
                         {"before": before["endsAt"], "after": after["endsAt"]})
        else:
            # 已达上限：延时次数不再增长，截止时间**必须**原地不动（否则就能无限薊羊毛）。
            report.check("达到上限后截止时间不再推后",
                         parse_epoch(after["endsAt"]) == parse_epoch(before["endsAt"]), True,
                         {"before": before["endsAt"], "after": after["endsAt"]})
    return auction_id


def websocket_phase(report, admin, a, b):
    print("\n== 7. 实时通道：先快照、提交有事件、断开重连能对齐 ==")
    auction = create_and_start(admin, "实时模拟", 100, 10, 60)
    auction_id = auction["id"]
    a.post("/auctions/%s/join" % auction_id)
    b.post("/auctions/%s/join" % auction_id)

    host = urllib.parse.urlsplit(a.base).hostname
    _, envelope = a.post("/auth/ws-tickets")
    ticket = data_of(envelope)
    path = "%s?ticket=%s" % (ticket["wsPath"].replace("{auctionId}", auction_id), ticket["ticket"])

    ws = WsClient(host, ticket["wsPort"], path).connect()
    try:
        first = next_event(ws, "AUCTION_SNAPSHOT")
        report.check("连接后第一帧是权威快照", first["type"], "AUCTION_SNAPSHOT")
        report.check("快照价 = 起始价", first["payload"]["currentPrice"], 100)
        seq_before = first["seq"]

        status, envelope = b.post("/auctions/%s/bids" % auction_id,
                                  {"requestId": new_request_id("ws"), "amount": 110})
        report.check("（对端）出价被接受", code_of(envelope), "OK", envelope)

        event = next_event(ws, "BID_ACCEPTED")
        report.check("收到 BID_ACCEPTED 且 seq 前进", event["seq"] > seq_before, True, event)
        report.check("事件价 = 成交价", event["payload"]["price"], 110)
        report.ok("事件里的领先者是匿名值（不是原始 user_id）",
                  str(event["payload"].get("leader", "")).startswith("anon-"), event["payload"])
    finally:
        ws.close()

    # 断线重连：用一张新票重新连上，第一帧快照必须已经包含刚才那笔出价。
    t2 = data_of(a.post("/auth/ws-tickets")[1])
    path2 = "%s?ticket=%s" % (t2["wsPath"].replace("{auctionId}", auction_id), t2["ticket"])
    ws2 = WsClient(host, t2["wsPort"], path2).connect()
    try:
        snap = next_event(ws2, "AUCTION_SNAPSHOT")
        report.check("重连后的快照包含最新价（断线恢复）", snap["payload"]["currentPrice"], 110)
        report.ok("重连快照的 seq 不小于断开前", snap["seq"] >= seq_before, snap)
    finally:
        ws2.close()
    return auction_id


def settlement_phase(report, admin, a):
    print("\n== 8. 结束核对：到期结算后结果与钱包一致 ==")
    auction = create_and_start(admin, "结算模拟", 100, 10, 10)
    auction_id = auction["id"]
    a.post("/auctions/%s/join" % auction_id)

    status, envelope = a.post("/auctions/%s/bids" % auction_id,
                              {"requestId": new_request_id("settle"), "amount": 110})
    report.check("出价被接受且不触发延时（距截止 > 5 秒）",
                 data_of(envelope).get("extensions"), 0, envelope)
    wallet_before = a.wallet()

    result = poll_result(a, auction_id, timeout=25)
    report.check("状态 FINISHED", result["status"], "FINISHED", result)
    report.check("原因 TIMEOUT", result["reason"], "TIMEOUT", result)
    report.check("赢家 = bidder_a", result["winner"], a.user["id"], result)
    report.check("成交价 = 110", result["finalPrice"], 110, result)

    wallet_after = a.wallet()
    report.check("总余额减少 = 成交价",
                 wallet_before["totalBalance"] - wallet_after["totalBalance"], 110)
    report.check("冻结释放 = 成交价",
                 wallet_before["frozenAmount"] - wallet_after["frozenAmount"], 110)
    return auction_id


def poll_result(api, auction_id, timeout=25):
    deadline = time.time() + timeout
    while time.time() < deadline:
        status, envelope = api.get("/auctions/%s/result" % auction_id)
        if status == 200:
            return data_of(envelope)
        time.sleep(0.4)
    raise RuntimeError("等待结算超时（%s 秒内未拿到结果）" % timeout)


def main():
    parser = argparse.ArgumentParser(description="竞拍全链路模拟")
    parser.add_argument("--base", default="http://localhost:8080/api/v1", help="用户/管理员 API 基址")
    parser.add_argument("--quick", action="store_true", help="跳过最慢的狙击阶段（约省 35 秒）")
    parser.add_argument("--keep", action="store_true", help="结束时不取消拍卖，便于观察")
    args = parser.parse_args()

    admin = Api(args.base)
    admin.login((ADMIN_EMAIL, ADMIN_PASSWORD))
    a = Api(args.base)
    a.login(BIDDER_A)
    b = Api(args.base)
    b.login(BIDDER_B)

    report = Reporter()
    created = []
    created.append(concurrency_phase(report, admin, a, b))
    if not args.quick:
        created.append(sniping_phase(report, admin, a, b))
    created.append(websocket_phase(report, admin, a, b))
    created.append(settlement_phase(report, admin, a))

    if args.keep:
        print("\n（--keep：保留拍卖 %s 供观察）" % ", ".join(created))
    else:
        for auction_id in created:
            status, envelope = admin.post("/admin/auctions/%s/cancel" % auction_id)
            # 已结算的拍卖取消会返回冲突，这是预期的收尾噪声，不算失败。
            if code_of(envelope) not in ("OK", "INVALID_STATE", "CONFLICT"):
                report.check("收尾取消 %s" % auction_id, code_of(envelope), "OK", envelope)
        print("\n（已取消本脚本创建的拍卖）")

    return report.summary()


if __name__ == "__main__":
    sys.exit(main())
