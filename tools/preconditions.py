#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""E2E/压测脚本的公共前置检查：演示账号还有钱吗？

为什么单独抽一个模块（而不是每个脚本各写一遍）：
    这些脚本会在**真实库**里真的花钱。连跑几次之后演示账号（种子各 1000）余额耗尽，
    之后每一条出价都会 `INSUFFICIENT_BALANCE`——脚本于是打出十几条 FAIL、最后还可能以
    一个与本因无关的 WebSocket 超时收场。失败点在离根因很远的地方，很容易被当成"代码坏了"。
    与其让每个脚本各自解释一次，不如把"先看余额"这件事收敛成一处，并且给出**一条命令**的恢复办法。

退出码约定（与各脚本一致）：
    0 全部检查通过 / 1 有检查失败 / **2 前置条件不满足（缺数据，不是缺陷）**
"""
from __future__ import annotations

import os

# 单次全链路模拟的最大花费（含 3 次狙击延时阶段）约 200 上下，留一倍余量；
# 低于这个数就根本跑不完一轮，早失败比跑一半再失败清楚得多。
MIN_AVAILABLE_BALANCE = 400

# 恢复命令里的容器名/账号/库名从环境变量取，默认值就是本仓库 compose 的默认值；
# 改过 docker-compose.yml / .env 的人不必来这里改字符串。
_MYSQL_CONTAINER = os.environ.get("MYSQL_CONTAINER", "bid-arena-mysql-1")
_MYSQL_USER = os.environ.get("MYSQL_USER", "bid_arena")
_MYSQL_DATABASE = os.environ.get("MYSQL_DATABASE", "bid_arena")

RESET_HINT = (
    "这些脚本会在真实库里真的花钱：连跑几次就会把种子的 1000 花完，\n"
    "   之后的出价全是 INSUFFICIENT_BALANCE —— 那不是缺陷，而是数据用完了。\n"
    "   恢复种子状态（仅开发库；不修 schema、不动 Flyway 历史）：\n"
    "     docker exec -i {container} mysql --default-character-set=utf8mb4 \\\n"
    "       -u{user} -p\"$DB_PASSWORD\" {database} < db/reset_demo_data.sql\n"
    "   然后用同样的命令重跑本脚本。"
).format(container=_MYSQL_CONTAINER, user=_MYSQL_USER, database=_MYSQL_DATABASE)


def ensure_demo_balances(wallets):
    """`wallets` 为 `[(标签, 可用余额)]`；任一低于阈值就打印根因与恢复办法并退出（码 2）。

    故意不返回布尔值让调用方决定：脚本里没有"余额不够也继续跑"的合理分支——
    继续跑只会产生一堆与根因无关的失败，把真正的结论埋掉。
    """
    for label, available in wallets:
        print("  [前置] 可用余额 %-16s %s" % (label, available))
    low = [(label, available) for label, available in wallets if available < MIN_AVAILABLE_BALANCE]
    if not low:
        return
    names = "、".join("%s(%s)" % (label, available) for label, available in low)
    print("\n!! 前置条件不满足：%s 的可用余额低于 %d，脚本跑不完一轮。" % (names, MIN_AVAILABLE_BALANCE))
    print("   " + RESET_HINT)
    raise SystemExit(2)
