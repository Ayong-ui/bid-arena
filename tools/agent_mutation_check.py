#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""P5（Agent API / 凭证）变异验证：把真实缺陷注入源码，确认测试确实会红。

为什么需要它：`mvn test` 全绿只说明"现在没红"，不说明"这些测试真的在守东西"。
P5 守的是认证凭据的边界（最小权限、过期、吊销、限流、独立端口），
这类规则一旦被改坏，用户界面看不出任何异常，只有变异测试能证明断言在场。

做法与 `tools/arch_mutation_check.py`、`frontend/tools/mutation_check.py` 一致：
备份 → 改一处源码 → 跑指定的测试类 → 还原，输出 ASCII 标记
`KILLED` / `SURVIVED`（Windows 控制台是 GBK，打不出 ✅/❌）。

判定标准有两条，缺一不可：测试进程退出码非 0，**且**预期的那个测试类自己出现了
失败——否则一条"让别的用例变红"的变异会冒充成功。

用法（需要先导出 BID_ARENA_TEST_DB_URL / USER / PASSWORD，见 CONTRIBUTING）：
    python tools/agent_mutation_check.py
"""
from __future__ import annotations

import io
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENT = "src/main/java/com/bidarena/agentaccess/"
DOMAIN = AGENT + "domain/"
APP = AGENT + "application/"
ADAPTER = AGENT + "adapter/"

# (编号, 说明, 文件, 原文, 变异后, 期望变红的测试类)
MUTATIONS = [
    ("G1", "吊销后仍然可用（吊销形同虚设）",
     DOMAIN + "AgentToken.java",
     "        return !revoked() && !expiredAt(now);",
     "        return true;",
     "AgentTokenTest"),
    ("G2", "过期边界放宽成「必须已经过了才算过期」",
     DOMAIN + "AgentToken.java",
     "        return !now.isBefore(expiresAt);",
     "        return now.isAfter(expiresAt);",
     "AgentTokenTest"),
    ("G3", "不检查拍卖范围（任何 Token 都能访问任何拍卖）",
     DOMAIN + "AgentToken.java",
     "        return auctionId != null && auctionIds.contains(auctionId);",
     "        return true;",
     "AgentTokenTest"),
    ("G4", "不检查权限项（只读 Token 也能出价）",
     DOMAIN + "AgentToken.java",
     "        return scopes.contains(scope);",
     "        return true;",
     "AgentTokenTest"),
    ("G5", "库里存明文 Token（拖库即可用）",
     APP + "AgentTokenService.java",
     "        tokens.insert(tokenId, name, sha256Hex(plaintext), agentUserId, scopes, auctionIds, rateLimit,",
     "        tokens.insert(tokenId, name, plaintext, agentUserId, scopes, auctionIds, rateLimit,",
     "AgentTokenServiceTest"),
    ("G6", "认证改成按明文查库（与「只存摘要」的存储约定脱节）",
     APP + "AgentTokenService.java",
     "        AgentToken token = tokens.findByHash(sha256Hex(presented.trim()));",
     "        AgentToken token = tokens.findByHash(presented.trim());",
     "AgentTokenServiceTest"),
    ("G7", "认证不看吊销与过期",
     APP + "AgentTokenService.java",
     "        if (!token.activeAt(clock.instant())) {\n"
     "            throw new BizException(ErrorCode.UNAUTHENTICATED, \"Agent Token 已过期或被吊销\");\n"
     "        }\n"
     "        return token;",
     "        return token;",
     "AgentTokenServiceTest"),
    ("G8", "限流不生效（上限被忽略）",
     APP + "AgentRateLimiter.java",
     "            if (current.count() >= limitPerMinute) {",
     "            if (false) {",
     "AgentRateLimiterTest"),
    ("G9", "被拒的请求也占用配额（窗口尾部的额度被提前吃掉）",
     APP + "AgentRateLimiter.java",
     "                allowed[0] = false;\n                return current;",
     "                allowed[0] = false;\n                return new Window(current.startMillis(), current.count() + 1);",
     "AgentRateLimiterTest"),
    ("G10", "认证过就放行，不消耗频率配额",
     ADAPTER + "AgentAuthFilter.java",
     "            tokens.checkRateLimit(token);",
     "            // 变异：不检查频率",
     "AgentApiIntegrationTest"),
    ("G11", "Agent 出价不再自动加入拍卖间（会被 NOT_JOINED 挡下）",
     "src/main/java/com/bidarena/auction/application/BidService.java",
     "        if (autoJoinAs != null && !auctions.isParticipant(conn, auctionId, userId)) {",
     "        if (false && !auctions.isParticipant(conn, auctionId, userId)) {",
     "AgentApiIntegrationTest"),
    ("G12", "授权不检查拍卖范围（越权访问别的拍卖）",
     APP + "AgentTokenService.java",
     "        if (!token.covers(auctionId)) {",
     "        if (false) {",
     "AgentApiIntegrationTest"),
    ("G13", "授权不检查权限项（只读权限可以出价）",
     APP + "AgentTokenService.java",
     "        if (!token.allows(required)) {",
     "        if (false) {",
     "AgentApiIntegrationTest"),
    ("G14", "8090 端口不再隔离，普通接口也能从这里进（端口边界失效）",
     "src/main/java/com/bidarena/bootstrap/AgentApiPlugin.java",
     "            if (ctx.path().startsWith(ApiPaths.AGENT_PREFIX)) {\n"
     "                app.tryHandle(ctx);\n"
     "                return;\n"
     "            }",
     "            if (true) {\n"
     "                app.tryHandle(ctx);\n"
     "                return;\n"
     "            }",
     "AgentApiIntegrationTest"),
]

# 每条变异都先 clean：还原源码后 mtime 会退回到备份时刻，而此刻 target/classes
# 里那个"变异后的" class 反而更新，增量编译会跳过重编，把上一条变异留在 classpath 上。
# 那会让后续变异在"已经被污染的基线"上跑，KILLED 结论不可信。clean 换来确定性。
CMD = "mvn -o clean test -Dtest={classes} -DfailIfNoSpecifiedTests=false"


def read_source(path):
    """按字节读源码，并把换行统一成 LF 交给调用方做文本替换。

    为什么不用 text 模式：Windows 上 Python 默认会把写出时的 LF 翻译成 CRLF，
    于是"变异 → 还原"这一圈会让整个文件的行尾悄悄变一遍（中途崩一次还会把它留在盘上）。
    变异脚本必须只改它想改的那一行。返回值里的换行会被原样写回。
    """
    raw = io.open(path, "rb").read().decode("utf-8")
    crlf = "\r\n" in raw
    return (raw.replace("\r\n", "\n") if crlf else raw), ("\r\n" if crlf else "\n")


def write_source(path, text, newline):
    data = text.replace("\n", "\r\n") if newline == "\r\n" else text
    io.open(path, "wb").write(data.encode("utf-8"))


def surefire_report(test_class):
    """按类名找到 surefire 报告；测试类分布在子包里，所以按文件名找。"""
    for root, _dirs, files in os.walk(os.path.join(ROOT, "target", "surefire-reports")):
        for name in files:
            if name.endswith("." + test_class + ".txt"):
                return os.path.join(root, name)
    return None


def mutated_report(test_class):
    """预期测试类是否真的出现了失败/错误（而不是被别处的红连坐）。"""
    path = surefire_report(test_class)
    if path is None:
        return False
    text = io.open(path, encoding="utf-8", errors="replace").read()
    return bool(re.search(r"Failures: [1-9]|Errors: [1-9]", text))


def report_exists(test_class):
    return surefire_report(test_class) is not None


def run_mutation(cmd):
    """跑一次变异，返回 (退出码, 输出)。输出只取尾部，避免整段 Maven 日志淹没结论。"""
    proc = subprocess.run(cmd, shell=True, capture_output=True, text=True, errors="replace")
    tail = "\n".join((proc.stdout + proc.stderr).splitlines()[-12:])
    return proc.returncode, tail


def assertNoLeftovers():
    """跑之前先确认上一轮没有留下残局。

    上一次被 Ctrl-C / 超时打断时，源码可能还带着变异、并留着一个 `.bak`。
    这时继续跑会拿"已经变异的文件"当原文备份，越修越乱，而且结论全是假的。
    """
    leftovers = []
    for root, dirs, files in os.walk(os.path.join(ROOT, "src")):
        dirs[:] = [d for d in dirs if d != "target"]
        leftovers += [os.path.join(root, f) for f in files if f.endswith(".bak")]
    assert not leftovers, "存在未清理的备份，请先还原：%s" % leftovers


def main():
    os.chdir(ROOT)
    assertNoLeftovers()
    verdicts = []
    for label, description, rel, original, mutated, test_class in MUTATIONS:
        path = os.path.join(ROOT, rel.replace("/", os.sep))
        assert os.path.exists(path), path
        text, newline = read_source(path)
        assert original in text, "%s：待变异片段不在文件里 -> %s" % (label, rel)
        stale = surefire_report(test_class)
        if stale:
            os.remove(stale)
        shutil.copy(path, path + ".bak")
        try:
            killed = False
            for attempt in (1, 2):
                write_source(path, text.replace(original, mutated, 1), newline)
                rc, tail = run_mutation(CMD.format(classes=test_class))
                if rc != 0 and mutated_report(test_class):
                    killed = True
                    break
                if report_exists(test_class):
                    break  # 报告在、但没有失败：这是真存活，不是环境噪声
                # 报告不存在：这一轮是废的（clean 被文件锁挡住 / fork 没跑起来），重试一次。
                print("  ... %s 第 %d 轮无有效报告（rc=%d），重试一次" % (label, attempt, rc))
                print("      " + tail.replace("\n", "\n      "))
            verdicts.append((label, killed))
            print("%s %-4s %-8s %s" % ("KILLED  " if killed else "SURVIVED", label,
                                       "rc=%d" % rc, description))
        finally:
            shutil.move(path + ".bak", path)
            # 还原后把 mtime 拨到现在：否则"变异后的 class"比"还原后的源码"新，
            # Maven 增量编译会跳过重编，上一条变异的 class 会留在 classpath 上毒害后续结论。
            os.utime(path, None)
    print("---- total: %d/%d KILLED" % (sum(1 for _, k in verdicts if k), len(verdicts)))
    return 0 if all(k for _, k in verdicts) else 1


sys.exit(main())
