#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""前端变异验证：把真实缺陷注入源码，确认测试确实会红。

为什么需要它：`npm test` 全绿只说明“现在没红”，不说明“这些测试真的在守东西”。
把一条真实会发生的缺陷注入进去，如果测试还是绿的，那条测试就是摆设。

做法与 `tools/arch_mutation_check.py` 一致：备份 → 改一处源码 → 跑测试 → 还原，
输出 ASCII 标记 `KILLED` / `SURVIVED`（Windows 控制台是 GBK，打不出 ✅/❌）。
这里比对的是“测试进程退出码非 0 且失败出现在预期的测试文件里”，
因为一条只让别的用例变红的变异并不能证明这条断言有效。

用法：cd frontend && python tools/mutation_check.py
"""
from __future__ import annotations

import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = os.path.join("src", "api")

# (编号, 说明, 相对路径, 原文, 变异后, 期望变红的测试文件)
MUTATIONS = [
    (
        "F1",
        "把重放当成失败（成功码只剩 OK）",
        os.path.join(API, "client.ts"),
        "  return code === 'OK' || code === 'IDEMPOTENCY_REPLAY'",
        "  return code === 'OK'",
        os.path.join("src", "api", "client.test.ts"),
    ),
    (
        "F2",
        "不发送 Authorization 头（令牌形同不存在）",
        os.path.join(API, "client.ts"),
        "    if (token) headers['Authorization'] = `Bearer ${token}`",
        "    if (token && false) headers['Authorization'] = `Bearer ${token}`",
        os.path.join("src", "api", "client.test.ts"),
    ),
    (
        "F3",
        "出价不带 Idempotency-Key 头（重试会重复下单）",
        os.path.join(API, "endpoints.ts"),
        "        headers: { 'Idempotency-Key': body.requestId },",
        "        headers: {},",
        os.path.join("src", "api", "client.test.ts"),
    ),
    (
        "F4",
        "不看业务码，只按 HTTP 状态判断成败",
        os.path.join(API, "client.ts"),
        "    if (!isSuccessCode(envelope.code as ApiCode)) {",
        "    if (!response.ok) {",
        os.path.join("src", "api", "client.test.ts"),
    ),
    (
        "F5",
        "非契约响应被当成正常数据（代理返回 HTML 时假装成功）",
        os.path.join(API, "client.ts"),
        "    if (!envelope) {",
        "    if (!envelope && false) {",
        os.path.join("src", "api", "client.test.ts"),
    ),
    (
        "F6",
        "请求超时不再中止（按钮可能永远禁用）",
        os.path.join(API, "client.ts"),
        "    const timeout = AbortSignal.timeout(requestOptions.timeoutMs ?? timeoutMs)",
        "    const timeout = new AbortController().signal",
        os.path.join("src", "api", "client.test.ts"),
    ),
    (
        "F7",
        "BID_TOO_LOW 不再提示最低加价（用户只能看到一句通用文案）",
        os.path.join(API, "client.ts"),
        "    if (this.code === 'BID_TOO_LOW') {",
        "    if (false) {",
        os.path.join("src", "api", "client.test.ts"),
    ),
    (
        "F8",
        "令牌失效时不通知调用方清理会话（用户会卡在“登录过期”循环里）",
        os.path.join(API, "client.ts"),
        "      if (envelope.code === 'UNAUTHENTICATED' && token) options.onUnauthenticated?.()",
        "      if (false) options.onUnauthenticated?.()",
        os.path.join("src", "api", "client.test.ts"),
    ),
    (
        "F9",
        "匿名标识改为取前 8 字节（与服务端算法不再一致）",
        os.path.join("src", "anonymous.ts"),
        "  const firstFourBytes = new Uint8Array(digest).subarray(0, 4)",
        "  const firstFourBytes = new Uint8Array(digest).subarray(0, 8)",
        os.path.join("src", "anonymous.test.ts"),
    ),
    (
        "F10",
        "会话过期不再判定（过期令牌当有效，请求必然 401）",
        os.path.join(API, "session.ts"),
        "  return expiresAt - now <= EXPIRY_SKEW_MS",
        "  return false",
        os.path.join("src", "anonymous.test.ts"),
    ),
]


def run_test(test_file: str) -> tuple[int, str]:
    """跑单个测试文件，返回退出码与输出（避免整库跑带来几十秒的等待）。"""
    proc = subprocess.run(
        ["npx.cmd" if os.name == "nt" else "npx", "vitest", "run", test_file],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        shell=(os.name == "nt"),
    )
    return proc.returncode, proc.stdout.decode("utf-8", "replace")


def main() -> int:
    if not os.path.isdir(os.path.join(ROOT, "node_modules")):
        print("缺少 node_modules，先执行 npm install")
        return 2

    # 先确认基线是绿的：基线本来就红的话，后面所有 KILLED 都没有意义。
    print("基线检查：npm test ...")
    baseline = subprocess.run(
        ["npx.cmd" if os.name == "nt" else "npx", "vitest", "run"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        shell=(os.name == "nt"),
    )
    if baseline.returncode != 0:
        sys.stdout.write(baseline.stdout.decode("utf-8", "replace"))
        print("基线不是绿的，先修好再谈变异验证")
        return 1
    print("基线绿。开始注入变异。\n")

    survived: list[str] = []
    for label, description, relpath, original, mutated, expected_file in MUTATIONS:
        path = os.path.join(ROOT, relpath)
        backup = path + ".bak"
        shutil.copyfile(path, backup)
        try:
            text = io.open(path, encoding="utf-8").read()
            if text.count(original) != 1:
                print("SKIP %s 锚点不唯一（%d 处）：%s" % (label, text.count(original), relpath))
                survived.append(label + "(锚点失效)")
                continue
            io.open(path, "w", encoding="utf-8", newline="").write(text.replace(original, mutated, 1))
            code, output = run_test(expected_file)
            killed = code != 0 and "FAIL" in output
            print(
                "%s %s rc=%d %s" % ("KILLED  " if killed else "SURVIVED", label, code, description)
            )
            if not killed:
                survived.append(label)
        finally:
            shutil.move(backup, path)

    print("")
    if survived:
        print("有变异存活：%s" % ", ".join(survived))
        return 1
    print("全部 %d 个变异都被杀掉。" % len(MUTATIONS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
