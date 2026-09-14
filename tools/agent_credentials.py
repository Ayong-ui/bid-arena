#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""竞拍 Agent 凭据（被原文称作 API key）的获取顺序：显式参数 > 环境变量 > 交互输入。

为什么单独抽一个模块：
    原文（`全栈评测-拍卖间-原文.md`）要求评审"通过环境变量 AUCTION_AGENT_TOKEN"
    把 Token 交给竞拍 Agent，并明确"不得写入命令历史或仓库"。可环境变量不总是设好了：
    评审手上有 Token、直接跑脚本时，如果脚本只会读环境变量，人就只能先把它
    `export`/`$env:` 进 shell——那恰恰是原文想避免的（容易进命令历史与录屏）。

    所以统一成三级来源：**命令行参数（脚本内部用）→ 环境变量 → 交互式粘贴**。
    交互输入走 `getpass`：不回显、不进 shell 历史；本模块在任何打印里都不出现明文。

    三级都拿不到时**返回 None**（不抛异常、不打印 Token），由调用方决定是提示用户，
    还是回退到自己签发——`agent_sim.py` 的全流程模式就是后者，所以"环境变量没配"
    不会把原有的一键复现路径弄坏。
"""
from __future__ import annotations

import getpass
import os
import sys

# AUCTION_AGENT_TOKEN 是原文点名的变量名，保持不动；
# 另外两个是给"只带一枚 Token 参与一场已存在拍卖"的轻量模式用的。
AGENT_TOKEN_ENV = "AUCTION_AGENT_TOKEN"
AUCTION_ID_ENV = "AUCTION_ID"
AGENT_BASE_ENV = "AGENT_API_BASE"

DEFAULT_AGENT_BASE = "http://localhost:8090/api/v1"


def _clean(value):
    """去前后空白；空字符串一律当"没有"，避免 `AUCTION_AGENT_TOKEN= ` 被当成有效凭据。"""
    if value is None:
        return None
    value = value.strip()
    return value or None


def _interactive(stream=None):
    """能不能对用户提问。stdin 是管道/重定向时不能问，否则脚本会静默卡在等输入。"""
    stream = stream if stream is not None else sys.stdin
    try:
        return bool(stream is not None and stream.isatty())
    except (AttributeError, ValueError):
        return False


def resolve_agent_token(explicit=None, prompt=True, stream=None):
    """取 Agent Token：显式参数 → 环境变量 → 交互输入；都拿不到返回 None。

    `prompt=False`，或 stdin 不是终端（CI / 管道 / 重定向）时跳过交互、直接返回 None。
    返回值是明文，调用方**不得**把它打进日志或断言详情里（跑失败时脚本只打印状态码与 code）。
    """
    found = _clean(explicit) or _clean(os.environ.get(AGENT_TOKEN_ENV))
    if found:
        return found
    if not prompt or not _interactive(stream):
        return None
    try:
        typed = getpass.getpass(
            "未检测到环境变量 %s。请粘贴 Agent Token（输入不回显；直接回车跳过）: "
            % AGENT_TOKEN_ENV)
    except (EOFError, KeyboardInterrupt):
        return None
    return _clean(typed)


def resolve_auction_id(explicit=None, prompt=True, stream=None):
    """取要参与的 auctionId：显式参数 → 环境变量 AUCTION_ID → 交互输入。

    auctionId 不是秘密，交互时用普通 `input` 即可（它还可能出现在 URL / 视频里）。
    """
    found = _clean(explicit) or _clean(os.environ.get(AUCTION_ID_ENV))
    if found:
        return found
    if not prompt or not _interactive(stream):
        return None
    try:
        typed = input(
            "未检测到 %s。请输入要参与的 auctionId（直接回车跳过）: " % AUCTION_ID_ENV)
    except (EOFError, KeyboardInterrupt):
        return None
    return _clean(typed)


def resolve_agent_base(explicit=None):
    """Agent API 基址：显式参数 → 环境变量 AGENT_API_BASE → 本机默认。"""
    return _clean(explicit) or _clean(os.environ.get(AGENT_BASE_ENV)) or DEFAULT_AGENT_BASE
