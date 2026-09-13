#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""服务器压测：尾段“博弈时间”清场压力 + 持续读写吞吐。

为什么单独一个脚本：
    `auction_sim.py` 关心“流程对不对”，`agent_sim.py` 关心“Agent 边界对不对”，
    两者都用很小的并发。真正会出问题的是**并发下才成立的性质**：
      1. 博弈时间内 Agent 被“系统性”清场——不是偶尔 403，而是**每一条**都被拒，
         无论叠了多少并发、抢在窗口的哪一秒；
      2. 高并发下服务端不冒 5xx、不超时，QPS 与延迟可量化。
    这个脚本把这两件事变成一条命令，跑完打印期望 vs 实际，任一条不符就非零退出。

两个模式：
    --mode game-window（默认）
        创建一场拍卖 → 等人/Agent 就位 → 等剩余时间进入尾段窗口 →
        `--concurrency` 条 Agent 出价**同时**打进 :8090 → 断言 100% 403 `HUMAN_ONLY_PERIOD`，
        紧接着一条真人出价必须被接受；再核对出价记录与管理员流水里没有留下任何 Agent 痕迹。
    --mode throughput
        创建一场长拍卖 → 在 `--seconds` 秒内用 `--concurrency` 个 worker 持续
        读写混合（出价 + 读快照）→ 打印 QPS、P50/P95/P99、状态码/错误码分布；
        出现 5xx 或连接失败即失败。

依赖：只用 Python 标准库。后端需先按 README 启动（用户端口 8080、Agent 端口 8090）。

用法：
    python tools/stress_test.py                                   # 博弈时间清场，50 并发
    python tools/stress_test.py --mode game-window -c 200         # 200 并发
    python tools/stress_test.py --mode throughput -c 20 --seconds 15
    python tools/stress_test.py --base http://192.168.1.10:8080/api/v1 \
                               --agent-base http://192.168.1.10:8090/api/v1
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

# 与 db/migration/V1 的种子数据一致。
ADMIN_EMAIL = "admin@example.com"
ADMIN_PASSWORD = "Admin123456!"
BIDDER_A = ("bidder_a@example.com", "Test123456!")
BIDDER_B = ("bidder_b@example.com", "Test123456!")


# ─────────────────────────────────────────────────────────── 基础设施

class Reporter:
    """把每条检查记下来，最后统一决定退出码（与其它 sim 脚本一致的输出风格）。"""

    def __init__(self):
        self.passed = 0
        self.failed = []

    def check(self, description, actual, expected, detail=""):
        if actual == expected:
            self.passed += 1
            print("  [OK]   %-52s %s" % (description, actual))
        else:
            self.failed.append((description, expected, actual, detail))
            print("  [FAIL] %-52s expected=%s actual=%s" % (description, expected, actual))
            if detail:
                print("         %s" % detail)

    def ok(self, description, condition, detail=""):
        self.check(description, bool(condition), True, detail)

    def summary(self):
        total = self.passed + len(self.failed)
        print("\n---- %d/%d checks passed ----" % (self.passed, total))
        for description, expected, actual, detail in self.failed:
            print("FAILED: %s (expected=%s, actual=%s) %s" % (description, expected, actual, detail))
        return 0 if not self.failed else 1


def http(base, method, path, token=None, body=None, timeout=15):
    """发一条请求，返回 (http_status, 封套 dict)。非 2xx 不当异常：压测就是要统计 403/409。

    连接层失败会抛出，由调用方计入 transport 错误——那才是压测真正的失败信号。
    """
    url = base.rstrip("/") + path
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            envelope = _parse(resp.read())
            return resp.status, envelope, time.perf_counter() - started
    except urllib.error.HTTPError as err:
        return err.code, _parse(err.read()), time.perf_counter() - started


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


def iso_utc(moment):
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def new_request_id(prefix):
    return "%s-%s" % (prefix, uuid.uuid4().hex[:16])


class Api:
    """带基址与 Bearer 凭证的小客户端（用户侧与 Agent 侧各一个）。"""

    def __init__(self, base):
        self.base = base
        self.token = None

    def get(self, path, timeout=15):
        return http(self.base, "GET", path, token=self.token, timeout=timeout)

    def post(self, path, body=None, timeout=15):
        return http(self.base, "POST", path, token=self.token, body=body, timeout=timeout)

    def login(self, email, password):
        _, envelope, _ = http(self.base, "POST", "/auth/login",
                              body={"email": email, "password": password})
        if code_of(envelope) != "OK":
            raise SystemExit("登录失败 %s：%s" % (email, envelope))
        self.token = data_of(envelope)["accessToken"]
        return data_of(envelope)["user"]


def setup_running_auction(admin, title, duration, start_price=100, increment=10):
    _, created, _ = admin.post("/admin/auctions", {
        "title": title, "description": "由 tools/stress_test.py 创建",
        "startPrice": start_price, "minIncrement": increment, "durationSeconds": duration,
    })
    auction_id = data_of(created).get("id")
    if not auction_id:
        raise SystemExit("创建拍卖失败：%s" % created)
    _, started, _ = admin.post("/admin/auctions/%s/start" % auction_id)
    if code_of(started) != "OK":
        raise SystemExit("开始拍卖失败：%s" % started)
    return auction_id, data_of(started)["startPrice"], data_of(started)["minIncrement"]


def issue_agent_token(admin, auction_id, user_id, scopes=("auction:read", "auction:bid"),
                      rate_limit_per_minute=None):
    body = {
        "name": "stress_test", "agentUserId": user_id,
        "auctionIds": [auction_id], "scopes": list(scopes),
        "expiresAt": iso_utc(datetime.now(timezone.utc) + timedelta(hours=1)),
    }
    # 压测要看见“窗口拒了每一条”，而不是“限流先拒了一半”，所以把令牌限流拉满（契约上限 6000）。
    if rate_limit_per_minute is not None:
        body["rateLimitPerMinute"] = rate_limit_per_minute
    _, issued, _ = admin.post("/admin/agent-tokens", body)
    token = data_of(issued).get("token")
    if not token:
        raise SystemExit("签发 Agent Token 失败：%s" % issued)
    return token


def snapshot(api, auction_id, token):
    _, envelope, _ = http(api.base, "GET", "/auctions/%s" % auction_id, token=token)
    return data_of(envelope)


def remaining_seconds(snap):
    """剩余秒数：用快照自带的 serverTime 与 endsAt 相减，不依赖本机时钟。"""
    ends_at = snap.get("endsAt")
    server_time = snap.get("serverTime")
    if not ends_at or not server_time:
        return None
    end = datetime.fromisoformat(ends_at.replace("Z", "+00:00"))
    now = datetime.fromisoformat(server_time.replace("Z", "+00:00"))
    return (end - now).total_seconds()


def wait_until_remaining(api, auction_id, token, threshold, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        snap = snapshot(api, auction_id, token)
        remaining = remaining_seconds(snap)
        if remaining is not None and remaining <= threshold:
            return snap, remaining
        time.sleep(0.25)
    raise SystemExit("等待进入尾段窗口超时（threshold=%ss）" % threshold)


def percentile(values, ratio):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(ratio * (len(ordered) - 1)))
    return ordered[index]


# ─────────────────────────────────────────────────────────── 模式一：博弈时间清场

def mode_game_window(args):
    report = Reporter()
    admin = Api(args.base)
    human = Api(args.base)
    agent = Api(args.agent_base)

    print("== 1. 管理员开拍，真人加入 ==")
    admin.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    bidder_a = human.login(*BIDDER_A)
    auction_id, start_price, increment = setup_running_auction(
        admin, "压测-博弈时间 %s" % datetime.now().strftime("%H:%M:%S"),
        duration=(args.duration or max(10, int(args.enter_at) + 5)))
    status, joined, _ = human.post("/auctions/%s/join" % auction_id)
    report.check("真人加入", code_of(joined), "OK", joined)

    agent_token = issue_agent_token(admin, auction_id, bidder_a["id"], rate_limit_per_minute=6000)
    report.ok("拿到 Agent Token", bool(agent_token))

    print("\n== 2. 等剩余时间进入尾段窗口（≤ %ss 即进入） ==")
    snap, remaining = wait_until_remaining(human, auction_id, human.token, args.enter_at)
    report.ok("已进入尾段窗口（剩余 %.1fs）" % remaining, remaining <= args.enter_at)

    print("\n== 3. %d 条 Agent 出价同时打进 :8090 ==" % args.concurrency)
    agent_requests = [new_request_id("stress-agent") for _ in range(args.concurrency)]
    amounts = [start_price + increment * (i + 1) for i in range(args.concurrency)]

    def agent_bid(index):
        request_id = agent_requests[index]
        status, envelope, elapsed = http(agent.base, "POST", "/agent/auctions/%s/bids" % auction_id,
                                         token=agent_token,
                                         body={"requestId": request_id, "amount": amounts[index]},
                                         timeout=args.timeout)
        return status, code_of(envelope), elapsed, envelope

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(agent_bid, range(args.concurrency)))
    wall = time.perf_counter() - started

    statuses = Counter(r[0] for r in results)
    codes = Counter(r[1] for r in results)
    allowed_codes = {403, 429}  # 403 清场是本条规则；429 是限流，同样表示“没让 Agent 成交”
    http_5xx = sum(1 for r in results if r[0] >= 500)
    transport = sum(1 for r in results if r[0] is None)
    print("  并发=%d 用时=%.2fs 状态=%s 错误码=%s"
          % (args.concurrency, wall, dict(statuses), dict(codes)))

    report.check("Agent 请求 100%% 被拒（无一条成交）", statuses.get(200, 0), 0)
    report.check("拒绝码全部是 HUMAN_ONLY_PERIOD", codes.get("HUMAN_ONLY_PERIOD", 0), args.concurrency, dict(codes))
    report.check("无 5xx", http_5xx + transport, 0)

    print("\n== 4. 窗口内真人出价必须被接受 ==")
    human_amount = start_price + increment
    status, envelope, _ = human.post("/auctions/%s/bids" % auction_id,
                                     {"requestId": new_request_id("stress-human"), "amount": human_amount})
    report.check("真人出价被接受", code_of(envelope), "OK", envelope)

    print("\n== 5. 核对：出价记录与流水里没有 Agent 的痕迹 ==")
    _, bids_page, _ = human.get("/auctions/%s/bids" % auction_id)
    items = data_of(bids_page).get("items") or []
    report.check("出价记录只有真人这一条", len(items), 1)
    if items:
        report.check("成交价是真人报的", items[0].get("amount"), human_amount)

    _, ledger_page, _ = admin.get("/admin/auctions/%s/ledger" % auction_id)
    ledger = data_of(ledger_page).get("items") or []
    report.ok("管理员流水里没有 AGENT 主体",
              all(entry.get("actorType") == "HUMAN" for entry in ledger),
              [e.get("actorType") for e in ledger])
    agent_request_ids = set(agent_requests)
    report.ok("Agent 的 requestId 一条都没落进流水",
              all(entry.get("requestId") not in agent_request_ids for entry in ledger))

    if not args.keep:
        admin.post("/admin/auctions/%s/cancel" % auction_id)
    return report.summary()


# ─────────────────────────────────────────────────────────── 模式二：吞吐与延迟

def mode_throughput(args):
    report = Reporter()
    admin = Api(args.base)
    human = Api(args.base)

    print("== 1. 管理员开拍，真人加入 ==")
    admin.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    human.login(*BIDDER_A)
    auction_id, start_price, increment = setup_running_auction(
        admin, "压测-吞吐 %s" % datetime.now().strftime("%H:%M:%S"), duration=(args.duration or 120))
    human.post("/auctions/%s/join" % auction_id)

    base = args.base
    token = human.token
    lock = threading.Lock()

    # 金额在一个有界区间里循环上升（110…start+20*inc），避免把演示账号的余额耗光：
    # 一旦当前价爬到上界，后续请求自然变成 BID_TOO_LOW——仍然走完整的出价事务（读当前价 + 锁），
    # 正是我们想压的那条热路径；冻结额永远 ≤ 上界，不会因余额不足把压测变成“快速失败”。
    def next_amount():
        with lock:
            next_amount.counter += 1
            return start_price + increment * (1 + (next_amount.counter % args.raise_steps))

    next_amount.counter = 0
    latencies = []
    statuses = Counter()
    codes = Counter()
    lock_stats = threading.Lock()

    deadline = time.monotonic() + args.seconds

    def worker(worker_id):
        local_latency = []
        local_status = Counter()
        local_code = Counter()
        i = 0
        while time.monotonic() < deadline:
            if i % 4 == 3:
                status, envelope, elapsed = http(base, "GET", "/auctions/%s" % auction_id,
                                                 token=token, timeout=args.timeout)
            else:
                status, envelope, elapsed = http(
                    base, "POST", "/auctions/%s/bids" % auction_id, token=token,
                    body={"requestId": new_request_id("stress-tp-%d" % worker_id),
                          "amount": next_amount()},
                    timeout=args.timeout)
            local_latency.append(elapsed)
            local_status[status] += 1
            local_code[code_of(envelope)] += 1
            i += 1
        with lock_stats:
            latencies.extend(local_latency)
            statuses.update(local_status)
            codes.update(local_code)

    print("\n== 2. %d 并发持续 %ss（读写比 3:1） ==" % (args.concurrency, args.seconds))
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        list(pool.map(worker, range(args.concurrency)))
    wall = time.perf_counter() - started

    total = sum(statuses.values())
    qps = total / wall if wall else 0.0
    http_5xx = sum(count for status, count in statuses.items() if status and status >= 500)
    transport = statuses.get(None, 0)

    print("\n== 3. 结果 ==")
    print("  总请求 =%d  用时=%.2fs  QPS=%.1f" % (total, wall, qps))
    print("  状态码 =%s" % dict(statuses))
    print("  错误码 =%s" % dict(codes))
    print("  延迟   P50=%.1fms  P95=%.1fms  P99=%.1fms  max=%.1fms"
          % (percentile(latencies, 0.50) * 1000, percentile(latencies, 0.95) * 1000,
             percentile(latencies, 0.99) * 1000, (max(latencies) if latencies else 0) * 1000))
    print("  均值=%.1fms  样本=%d" % ((statistics.mean(latencies) * 1000 if latencies else 0), len(latencies)))

    report.ok("压出了流量（总请求 > 0）", total > 0)
    report.check("无 5xx / 连接失败", http_5xx + transport, 0, dict(statuses))
    # 只有这几类是可接受的业务拒绝：并发抢价必然产生 BID_TOO_LOW；
    # INSUFFICIENT_BALANCE 作为兜底（有界金额下正常情况下不该出现）。
    business = set(codes) - {None, "OK", "IDEMPOTENCY_REPLAY", "BID_TOO_LOW",
                             "HUMAN_ONLY_PERIOD", "RATE_LIMITED", "INSUFFICIENT_BALANCE"}
    report.ok("没有意料之外的错误码", not business, business)

    if not args.keep:
        admin.post("/admin/auctions/%s/cancel" % auction_id)
    return report.summary()


# ─────────────────────────────────────────────────────────── 入口

def main():
    parser = argparse.ArgumentParser(description="服务器压测：博弈时间清场 + 持续读写吞吐")
    parser.add_argument("--base", default="http://localhost:8080/api/v1", help="用户/管理员 API 基址")
    parser.add_argument("--agent-base", default="http://localhost:8090/api/v1", help="Agent API 基址")
    parser.add_argument("--mode", choices=("game-window", "throughput"), default="game-window")
    parser.add_argument("-c", "--concurrency", type=int, default=50, help="并发度（默认 50）")
    parser.add_argument("--seconds", type=float, default=10, help="throughput 模式持续时间（秒）")
    parser.add_argument("--raise-steps", type=int, default=20,
                        help="throughput 模式出价金额的上升档数（默认 20，用于把冻结额限制在有界区间）")
    parser.add_argument("--duration", type=int, default=0,
                        help="拍卖时长（秒）；0 = 按模式自定（game-window: 进入点+5，throughput: 120）")
    parser.add_argument("--enter-at", type=float, default=15,
                        help="game-window 模式：剩余多少秒时开始打（默认 15，窗口是 20）")
    parser.add_argument("--timeout", type=float, default=15, help="单请求超时（秒）")
    parser.add_argument("--keep", action="store_true", help="结束时不取消拍卖")
    args = parser.parse_args()

    if args.mode == "game-window":
        return mode_game_window(args)
    return mode_throughput(args)


if __name__ == "__main__":
    sys.exit(main())
