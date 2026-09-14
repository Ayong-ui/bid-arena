#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""竞拍 Agent 凭据（被原文称作 API key）的获取顺序：显式参数 > 环境变量。

为什么单独抽一个模块：
    原文（`全栈评测-拍卖间-原文.md`）要求评审"通过环境变量 AUCTION_AGENT_TOKEN"
    把 Token 交给竞拍 Agent，并明确"不得写入命令历史或仓库"。所以这里**只读变量，
    不做交互输入**：让一枚长效凭据经键盘/剪贴板进 shell，反而更容易落进命令历史、
    录屏与终端回滚缓冲——那正是原文要避免的。缺了就是缺了，由调用方把"怎么配"
    讲清楚并以非零退出码停下，不拿交互当兜底。

    拿不到时统一**返回 None**（不抛异常、不打印明文），由调用方决定退出码。
"""
from __future__ import annotations

import os

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


def resolve_agent_token(explicit=None):
    """取 Agent Token：显式参数 → 环境变量 `AUCTION_AGENT_TOKEN`；都没有返回 None。

    返回值是明文，调用方**不得**把它打进日志或断言详情里（跑失败时脚本只打印状态码与 code）。
    """
    return _clean(explicit) or _clean(os.environ.get(AGENT_TOKEN_ENV))


def resolve_auction_id(explicit=None):
    """取要参与的 auctionId：显式参数 → 环境变量 `AUCTION_ID`；都没有返回 None。"""
    return _clean(explicit) or _clean(os.environ.get(AUCTION_ID_ENV))


def resolve_agent_base(explicit=None):
    """Agent API 基址：显式参数 → 环境变量 `AGENT_API_BASE` → 本机默认。"""
    return _clean(explicit) or _clean(os.environ.get(AGENT_BASE_ENV)) or DEFAULT_AGENT_BASE
