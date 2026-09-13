#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""竞拍 Agent 的端到端模拟脚本：签发凭证 -> 读状态 -> 出价 -> 读结果，并逐条验证权限边界。

为什么要有它（而不是只留单元测试）：
    P5 的验收项是“独立的认证凭据与最小权限边界”，而这些边界存在于**传输层**——
    哪个端口、哪种凭证、哪个过滤器先说话。`mvn verify` 里的集成测试已经覆盖了这些，
    但它们跑在测试基座里、用随机端口，评审要“自己动手看一遍”并不方便。
    本脚本把同一批事实变成一条可复制的命令：真实 HTTP、真实 MySQL、真实两个端口，
    跑完打印一张“期望 vs 实际”的清单，任何一条对不上就以非零退出码结束。

它扮演两个角色：
    1) 管理员：登录、创建并开始一场拍卖、签发/吊销 Agent Token；
    2) 竞拍 Agent：只持有受限 Token，通过 :8090 读状态、出价、读结果。
    “真人”角色（bidder_b）由脚本顺带扮演，用来证明 Agent 与真人落在同一条出价路径上。

依赖：只用 Python 标准库。后端需先按 README 启动（:8080 + :8090）。

用法：
    python tools/agent_sim.py                 # 默认 http://localhost:8080 / :8090
    python tools/agent_sim.py --base http://192.168.1.10:8080/api/v1 \
                              --agent-base http://192.168.1.10:8090/api/v1
    python tools/agent_sim.py --keep          # 跑完不取消拍卖（便于在前端里继续观察）
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

# 与 db/migration/V1、V3 的种子数据一致（README 的演示账号）。
ADMIN_EMAIL = "admin@example.com"
ADMIN_PASSWORD = "Admin123456!"
BIDDER_A_EMAIL = "bidder_a@example.com"
BIDDER_PASSWORD = "Test123456!"


class CheckFailed(AssertionError):
    """一条断言没通过。携带期望/实际，便于最后汇总。"""


class Reporter:
    """把每条检查的结论按“通过/失败”记下来，最后统一决定退出码。"""

    def __init__(self):
        self.passed = 0
        self.failed = []

    def check(self, description, actual, expected, detail=""):
        if actual == expected:
            self.passed += 1
            print("  [OK]   %-46s %s" % (description, actual))
        else:
            self.failed.append((description, expected, actual, detail))
            print("  [FAIL] %-46s expected=%s actual=%s" % (description, expected, actual))
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


def http(base, method, path, token=None, body=None, headers=None, timeout=15):
    """发一条请求，返回 (http_status, 封套 dict)。

    非 2xx 不当异常抛出：本脚本要断言的恰恰是 401/403/409/429，
    而契约保证“任何响应都可解析”。读取失败才抛。
    """
    url = base.rstrip("/") + path
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for name, value in (headers or {}).items():
        req.add_header(name, value)

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
    """带基址与凭证的小客户端。用户侧与 Agent 侧各一个实例。"""

    def __init__(self, base):
        self.base = base
        self.token = None

    def get(self, path):
        return http(self.base, "GET", path, token=self.token)

    def post(self, path, body=None, headers=None):
        return http(self.base, "POST", path, token=self.token, body=body, headers=headers)

    def login(self, email, password):
        status, envelope = http(self.base, "POST", "/auth/login",
                                body={"email": email, "password": password})
        if status != 200:
            raise SystemExit("登录失败 %s：%s" % (email, envelope))
        self.token = data_of(envelope)["accessToken"]
        return data_of(envelope)["user"]


def iso_utc(moment):
    """契约要 ISO-8601 时刻（如 2026-12-31T00:00:00Z）。"""
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def new_request_id(prefix):
    return "%s-%s" % (prefix, uuid.uuid4().hex[:16])


def issue_read_only(admin, auction_id, user_id, name="agent-sim 只读"):
    """签发一枚只能读这一场的 Token，返回明文。

    抽成函数是因为它要签很多次（边界检查 + 收尾读结果），而“顺手复用上面那枚”
    恰好是错的：full_token 在 6.6 已被吊销，再用它去读结果只会得到 401 封套，
    于是“取消后状态”那条断言会变成假失败（本脚本第一版就踩了这个坑）。
    """
    _, envelope = admin.post("/admin/agent-tokens", {
        "name": name, "agentUserId": user_id,
        "auctionIds": [auction_id], "scopes": ["auction:read"],
        "expiresAt": iso_utc(datetime.now(timezone.utc) + timedelta(hours=1)),
    })
    return data_of(envelope).get("token")


def main():
    parser = argparse.ArgumentParser(description="竞拍 Agent 端到端模拟")
    parser.add_argument("--base", default="http://localhost:8080/api/v1",
                        help="用户/管理员 API 基址")
    parser.add_argument("--agent-base", default="http://localhost:8090/api/v1",
                        help="Agent API 基址（独立端口）")
    parser.add_argument("--duration", type=int, default=300, help="拍卖时长（秒），最小 10")
    parser.add_argument("--keep", action="store_true", help="结束时不取消拍卖")
    parser.add_argument("--skip-boundary", action="store_true",
                        help="跳过越权/过期/吊销/限流等边界检查（只跑一遍正向流程）")
    args = parser.parse_args()

    admin = Api(args.base)
    human = Api(args.base)
    agent = Api(args.agent_base)  # 注意：Agent 用独立端口，凭证完全独立

    report = Reporter()
    auction_id = None

    print("== 1. 管理员登录并创建一场 RUNNING 拍卖 ==")
    admin_user = admin.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    report.check("管理员角色", admin_user["role"], "ADMIN")

    title = "Agent 模拟 %s" % datetime.now().strftime("%H:%M:%S")
    status, envelope = admin.post("/admin/auctions", {
        "title": title, "description": "由 tools/agent_sim.py 创建",
        "startPrice": 100, "minIncrement": 10, "durationSeconds": args.duration,
    })
    report.check("创建拍卖返回 201", status, 201, envelope)
    auction_id = data_of(envelope).get("id")
    report.ok("拿到 auctionId", bool(auction_id), envelope)

    status, envelope = admin.post("/admin/auctions/%s/start" % auction_id)
    report.check("开始拍卖", code_of(envelope), "OK", envelope)
    start_price = data_of(envelope)["startPrice"]
    increment = data_of(envelope)["minIncrement"]
    report.check("起始价", start_price, 100)

    print("\n== 2. 签发两种 Agent Token（最小权限） ==")
    bidder_a = human.login(BIDDER_A_EMAIL, BIDDER_PASSWORD)
    human.login("bidder_b@example.com", BIDDER_PASSWORD)

    # 读 + 出价，范围仅这一场。这是“正常能力”的 Token。
    status, envelope = admin.post("/admin/agent-tokens", {
        "name": "agent-sim 读+出价", "agentUserId": bidder_a["id"],
        "auctionIds": [auction_id], "scopes": ["auction:read", "auction:bid"],
        "expiresAt": iso_utc(datetime.now(timezone.utc) + timedelta(hours=1)),
    })
    report.check("签发读+出价 Token 返回 201", status, 201, envelope)
    full_token = data_of(envelope).get("token")
    full_token_id = data_of(envelope).get("tokenId")
    report.ok("明文 Token 只在创建响应出现", bool(full_token) and bool(full_token_id), envelope)

    print("\n== 3. Agent 通过 :8090 读状态（权威快照） ==")
    agent.token = full_token
    status, envelope = agent.get("/agent/auctions/%s" % auction_id)
    report.check("Agent 读快照", code_of(envelope), "OK", envelope)
    snapshot = data_of(envelope)
    report.check("快照里的当前价 = 起始价", snapshot.get("currentPrice"), start_price)
    report.ok("快照带 serverTime（客户端据此算倒计时）", bool(snapshot.get("serverTime")))

    print("\n== 4. Agent 出价 -> 真人出价 -> Agent 再出价 ==")
    agent_amount = start_price + increment
    request_id = new_request_id("agent")
    status, envelope = agent.post("/agent/auctions/%s/bids" % auction_id,
                                  {"requestId": request_id, "amount": agent_amount},
                                  headers={"Idempotency-Key": request_id})
    report.check("Agent 出价被接受", code_of(envelope), "OK", envelope)
    report.check("领先者是 Token 所属用户", data_of(envelope).get("leader"), bidder_a["id"])
    report.check("成交价", data_of(envelope).get("price"), agent_amount)

    # 重放同一个 requestId：必须返回首次结果（IDEMPOTENCY_REPLAY），且不再冻结。
    status, envelope = agent.post("/agent/auctions/%s/bids" % auction_id,
                                  {"requestId": request_id, "amount": agent_amount})
    report.check("同一 requestId 重放", code_of(envelope), "IDEMPOTENCY_REPLAY", envelope)

    human_amount = agent_amount + increment
    status, envelope = human.post("/auctions/%s/join" % auction_id)
    report.check("真人加入拍卖间", code_of(envelope) in ("OK", "CONFLICT"), True, envelope)
    status, envelope = human.post("/auctions/%s/bids" % auction_id,
                                  {"requestId": new_request_id("human"), "amount": human_amount})
    report.check("真人加价超越 Agent", code_of(envelope), "OK", envelope)

    agent_amount = human_amount + increment
    status, envelope = agent.post("/agent/auctions/%s/bids" % auction_id,
                                  {"requestId": new_request_id("agent"), "amount": agent_amount})
    report.check("Agent 再次出价夺回领先", code_of(envelope), "OK", envelope)
    report.check("当前价 = Agent 出价", data_of(envelope).get("price"), agent_amount)

    status, envelope = agent.get("/agent/auctions/%s" % auction_id)
    report.check("Agent 读到的最新价与服务端一致", data_of(envelope).get("currentPrice"), agent_amount)
    report.check("Agent 读到的最新领先者", data_of(envelope).get("leader"), bidder_a["id"])

    print("\n== 5. 结算前读结果应为 404；取消后能读到 CANCELLED ==")
    status, envelope = agent.get("/agent/auctions/%s/result" % auction_id)
    report.check("未结算时读结果", status, 404, envelope)

    if args.skip_boundary:
        print("\n（--skip-boundary：跳过最小权限边界检查）")
    else:
        boundary_checks(report, admin, agent, auction_id, bidder_a["id"], full_token_id, full_token)

    if not args.keep:
        status, envelope = admin.post("/admin/auctions/%s/cancel" % auction_id)
        report.check("管理员取消拍卖（收尾）", code_of(envelope), "OK", envelope)
        # full_token 已在边界检查里被吊销，这里必须换一枚新的只读 Token 才能读结果。
        agent.token = issue_read_only(admin, auction_id, bidder_a["id"], "agent-sim 收尾读结果")
        status, envelope = agent.get("/agent/auctions/%s/result" % auction_id)
        report.check("取消后 Agent 读到 CANCELLED", data_of(envelope).get("status"), "CANCELLED")
        report.ok("取消的拍卖没有赢家",
                  data_of(envelope).get("winner") in (None, ""),
                  data_of(envelope))
    else:
        print("\n（--keep：保留拍卖 %s 供前端继续观察）" % auction_id)

    return report.summary()


def boundary_checks(report, admin, agent, auction_id, agent_user_id, full_token_id, full_token):
    """最小权限边界的逐条验证。全部通过才说明“独立凭据 + 最小权限”真的成立。"""
    print("\n== 6. 最小权限边界 ==")

    # 6.1 只读 Token 不能出价。
    read_only = issue_read_only(admin, auction_id, agent_user_id)
    agent.token = read_only
    status, envelope = agent.get("/agent/auctions/%s" % auction_id)
    report.check("只读 Token 可以读", code_of(envelope), "OK", envelope)
    status, envelope = agent.post("/agent/auctions/%s/bids" % auction_id,
                                  {"requestId": new_request_id("ro"), "amount": 99999})
    report.check("只读 Token 出价被拒 403", status, 403, envelope)
    report.check("错误码 FORBIDDEN", code_of(envelope), "FORBIDDEN", envelope)

    # 6.2 范围之外的拍卖。
    status, envelope = admin.post("/admin/auctions", {
        "title": "边界用另一场", "startPrice": 100, "minIncrement": 10, "durationSeconds": 120,
    })
    other_id = data_of(envelope)["id"]
    admin.post("/admin/auctions/%s/start" % other_id)
    agent.token = full_token  # 这枚只授权了 auction_id，不含 other_id
    status, envelope = agent.get("/agent/auctions/%s" % other_id)
    report.check("范围外的拍卖被拒 403", status, 403, envelope)
    status, envelope = agent.get("/agent/auctions/%s" % auction_id)
    report.check("范围内的拍卖仍可读", code_of(envelope), "OK", envelope)

    # 6.3 两种凭证互不通用。
    status, envelope = http(agent.base, "GET", "/agent/auctions/%s" % auction_id,
                            token=admin.token)  # 用户 JWT 打到 Agent 接口
    report.check("用户 JWT 过不了 Agent 接口", status, 401, envelope)
    status, envelope = http(admin.base, "GET", "/auctions/%s" % auction_id,
                            token=full_token)  # Agent Token 打到用户接口
    report.check("Agent Token 过不了用户接口", status, 401, envelope)

    # 6.4 过期即失效（签发 +2 秒，等到过期）。
    _, envelope = admin.post("/admin/agent-tokens", {
        "name": "agent-sim 短命", "agentUserId": agent_user_id,
        "auctionIds": [auction_id], "scopes": ["auction:read"],
        "expiresAt": iso_utc(datetime.now(timezone.utc) + timedelta(seconds=2)),
    })
    short_lived = data_of(envelope).get("token")
    agent.token = short_lived
    status, envelope = agent.get("/agent/auctions/%s" % auction_id)
    report.check("短命 Token 过期前可用", code_of(envelope), "OK", envelope)
    time.sleep(2.2)
    status, envelope = agent.get("/agent/auctions/%s" % auction_id)
    report.check("过期后 401", status, 401, envelope)
    report.check("错误码 UNAUTHENTICATED", code_of(envelope), "UNAUTHENTICATED", envelope)

    # 6.5 频率上限：limit=2，第三次即 429。
    _, envelope = admin.post("/admin/agent-tokens", {
        "name": "agent-sim 限流", "agentUserId": agent_user_id,
        "auctionIds": [auction_id], "scopes": ["auction:read"],
        "expiresAt": iso_utc(datetime.now(timezone.utc) + timedelta(hours=1)),
        "rateLimitPerMinute": 2,
    })
    limited = data_of(envelope).get("token")
    agent.token = limited
    first = agent.get("/agent/auctions/%s" % auction_id)
    second = agent.get("/agent/auctions/%s" % auction_id)
    third = agent.get("/agent/auctions/%s" % auction_id)
    report.check("限流 Token 第 1 次通过", code_of(first[1]), "OK", first[1])
    report.check("限流 Token 第 2 次通过", code_of(second[1]), "OK", second[1])
    report.check("限流 Token 第 3 次 429", third[0], 429, third[1])
    report.check("错误码 RATE_LIMITED", code_of(third[1]), "RATE_LIMITED", third[1])

    # 6.6 吊销：立即失效，且重复吊销幂等。
    agent.token = full_token
    status, envelope = admin.post("/admin/agent-tokens/%s/revoke" % full_token_id)
    report.check("吊销返回 OK", code_of(envelope), "OK", envelope)
    report.ok("吊销响应不回显明文", not data_of(envelope).get("token"), envelope)
    status, envelope = agent.get("/agent/auctions/%s" % auction_id)
    report.check("吊销后 401", status, 401, envelope)
    status, envelope = admin.post("/admin/agent-tokens/%s/revoke" % full_token_id)
    report.check("重复吊销幂等（仍 200）", code_of(envelope), "OK", envelope)
    status, envelope = admin.post("/admin/agent-tokens/agt_does_not_exist/revoke")
    report.check("未知 tokenId 吊销 404", status, 404, envelope)

    # 6.7 Agent 端口不暴露别的接口（健康检查也没有）。
    status, envelope = http(agent.base, "GET", "/health")
    report.check("Agent 端口上没有 /health（404 封套）", status, 404, envelope)


if __name__ == "__main__":
    sys.exit(main())
