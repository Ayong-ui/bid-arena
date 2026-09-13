# AGENT_TOOL_SPEC.md

> 本文件说明**评审如何把竞拍 Agent Token 交给一个 Coding Agent，让它查询拍卖、出价并读取结果**。
> 契约（端点、字段、错误码）的权威定义是 [`docs/openapi.yaml`](docs/openapi.yaml) 的 `Agent` 标签；
> 本文件只讲“怎么用”，不重复契约细节。
>
> **状态：契约已定稿，实现属 P5，当前尚未落地。** 下文的请求/响应结构取自 `openapi.yaml`，
> 在 P5 实现并通过集成测试前，不要把它当作“已验证可用”。本文件**不得出现任何真实 Token**。

## 1. 两类 Agent 不要混

- **Coding Agent**：参与开发、测试与排错（如 Codex / Claude Code / Cursor / Copilot）。它不需要也不应持有竞拍 Token。
- **竞拍 Agent**：拍卖的参与者。持有受限 Agent Token，通过公开 API 查询状态并出价；**不能**访问数据库、管理接口或他人私有数据，也**不能**决定余额、最高价、截止时间或赢家（这些由服务端裁决）。

一个 Coding Agent 可以“扮演”竞拍 Agent 去调用 API，但凭据是竞拍 Token，权限边界按竞拍 Agent 执行。

## 2. Token 的形态与边界

Token 由**管理员**签发（`POST /api/v1/admin/agent-tokens`，走 8080 的用户侧 JWT 鉴权），字段见
[`CreateAgentTokenRequest`](docs/openapi.yaml)：

| 字段 | 含义 | 约束 |
|---|---|---|
| `name` | 便于人识别的名字 | ≤ 80 字符 |
| `agentUserId` | Token 归属的 Agent 用户 | 资金与冻结记在该用户名下 |
| `auctionIds` | 允许访问的拍卖范围 | 缺席或空表示由实现决定（P5 需明确并测试） |
| `scopes` | `auction:read` / `auction:bid` | 最小权限；只读 Agent 不应拿到 `auction:bid` |
| `expiresAt` | 过期时间 | 过期即失效 |

**数据库只保存 Token 摘要**；明文仅在创建响应里返回一次（`AgentToken.token`），之后无法再取回。
明文不得出现在 Git、前端包、日志或录屏中。吊销走 `POST /api/v1/admin/agent-tokens/{tokenId}/revoke`。

Agent API 使用**独立凭据与独立端口**（`:8090`），与用户侧的 JWT 不混用，见 `DECISIONS.md` D-9。

## 3. 评审操作步骤（P5 实现后照此执行）

1. **管理员登录**（用户侧，`:8080`）拿到 JWT。
2. **签发 Token**：`POST /api/v1/admin/agent-tokens`，记录响应里的 `token`（只出现这一次）。
3. **把 Token 交给 Coding Agent**：通过环境变量传入，例如 `AUCTION_AGENT_TOKEN`，
   **不要**写进命令历史、脚本或仓库（`CONTRIBUTING.md` §8.3）。
4. Agent **查询状态**：`GET http://localhost:8090/api/v1/agent/auctions/{auctionId}`。
5. Agent **决策并出价**：`POST .../auctions/{auctionId}/bids`，body 含 `requestId` 与 `amount`，
   并带 `Idempotency-Key` 头；重试必须沿用同一个 `requestId`（服务端幂等）。
6. Agent **读取结果**：`GET .../auctions/{auctionId}/result`（未结算时 404）。

## 4. 给 Coding Agent 的提示词模板

> 下面是一份可复制的模板。把 `{{...}}` 换成真实值；**Token 只从环境变量读取**，不要粘贴到对话里。

```text
你是一个竞拍 Agent。通过公开 API 参与拍卖，规则由服务端裁决，你只负责查询与出价。

环境变量 AUCTION_AGENT_TOKEN 是你的凭据（不要打印它）。
Auction API 基址：{{AGENT_API_BASE，例如 http://localhost:8090/api/v1}}
Auction ID：{{AUCTION_ID}}
你的预算上限：{{MAX_SPEND}} 积分；每次加价不超过 {{MAX_INCREMENT}}。

步骤：
1. GET  {{AGENT_API_BASE}}/agent/auctions/{{AUCTION_ID}}  读取当前价、最小加价、剩余时间、seq。
2. 若“当前价 + 最小加价”在你的预算内，且剩余时间充裕，则出价：
   POST {{AGENT_API_BASE}}/agent/auctions/{{AUCTION_ID}}/bids
   Headers: Authorization: Bearer $AUCTION_AGENT_TOKEN, Idempotency-Key: <一个新 UUID>,
            Content-Type: application/json
   Body:    {"requestId": "<与 Idempotency-Key 同一个值>", "amount": <整数>}
3. 网络失败要重试时，必须沿用同一个 requestId。
4. 若返回 409（如 BID_TOO_LOW / NOT_JOINED / AUCTION_FINISHED），不要盲目重试，按 code 决定。
5. 结束后 GET .../result 读取赢家与成交价，并报告你实际提交的 requestId 与最终结论。
```

约束：`amount` 必须是整数积分；加价不得低于当前价 + `minIncrement`；超出 Token 的 `auctionIds`
范围或缺少 `auction:bid` 权限会被 403；超过频率限制会得到 429。

## 5. 失败与边界（契约）

| 情况 | 期望 | 契约位置 |
|---|---|---|
| Token 缺失 / 伪造 / 过期 / 已吊销 | 401 | `Agent` 端点 `Unauthorized` |
| Token 不含该拍卖，或缺少 `auction:bid` | 403 | `Forbidden` |
| 未加入、加价不足、已结束、重复 `requestId` | 409（封套内 `code` 为准） | `Conflict` |
| 超过配置的请求频率 | 429 | `RateLimited` |
| 拍卖不存在 / 未结算的结果 | 404 | `NotFound` |

## 6. 评审可据此核验的点（P5 的验收项）

- [ ] 明文 Token 只返回一次；库里只有摘要（`agent_tokens`）。
- [ ] 范围（`auctionIds`）、权限（`scopes`）、过期、吊销四者都真的生效。
- [ ] 只读 Token 无法出价；越权访问他人拍卖被拒。
- [ ] 频率限制生效并返回 429。
- [ ] Agent 出价与真人出价走同一套事务与幂等语义（复用业务服务，不新开一条写入路径）。
- [ ] 所有断言用脱敏后的 Token 值，报告与日志里搜不到明文。

> 以上验收项与 [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md) 的 D1、E1 对应；P5 完成后在此勾选并登记证据。
