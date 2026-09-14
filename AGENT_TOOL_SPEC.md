# AGENT_TOOL_SPEC.md

> 本文件说明**评审如何把竞拍 Agent Token 交给一个 Coding Agent，让它查询拍卖、出价并读取结果**。
> 契约（端点、字段、错误码）的权威定义是 [`docs/openapi.yaml`](docs/openapi.yaml) 的 `Agent` 标签；
> 本文件只讲“怎么用”，不重复契约细节。
>
> **状态：P5 已实现并验证（`tools/agent_sim.py` 可一键复现）。** 端点在 `:8090`（用户/管理端点在 `:8080`），
> 契约见 [`docs/openapi.yaml`](docs/openapi.yaml) 的 `Agent` 标签；本节给出评审可照做的操作步骤。
> 本文件**不得出现任何真实 Token**；“尚不可用”一类的历史标注已随 P5 移除。

## 1. 两类 Agent 不要混

- **Coding Agent**：参与开发、测试与排错（如 Codex / Claude Code / Cursor / Copilot）。它不需要也不应持有竞拍 Token。
- **竞拍 Agent**：拍卖的参与者。持有受限 Agent Token，通过公开 API 查询状态并出价；**不能**访问数据库、管理接口或他人私有数据，也**不能**决定余额、最高价、截止时间或赢家（这些由服务端裁决）。

一个 Coding Agent 可以“扮演”竞拍 Agent 去调用 API，但凭据是竞拍 Token，权限边界按竞拍 Agent 执行。

> **先确认你要的是哪条路**：如果用户只是想让 AI 替自己出价、又不打算写程序，**不要**走本文件的 Token，而是在前端“AI 代理”页创建**托管 AI 代理**（选进行中/未开拍场次 + 预算上限，服务端到点自动进场并按最小加价跟价，D-36）。托管代理不预冻结、一人一场一个、对尾段“博弈时间”**无例外**；它与 Token 是**两条并列的路径**，共用同一个出价事务。本文件只讲 Token（给要自己写机器人的人）。

## 2. Token 的形态与边界

Token 有**两条签发路径**（D-34）：

- **用户自助**：`POST /api/v1/me/agent-tokens`（走用户自己的 JWT）。请求体是 [`CreateMyAgentTokenRequest`](docs/openapi.yaml)，
  **没有** `agentUserId` —— 归属由服务层钉死为调用者自己，“替别人签发”在类型上不可表达；前端“我的 AI 代理”页就是这条路。
- **管理员代签**：`POST /api/v1/admin/agent-tokens`（ADMIN），请求体是 [`CreateAgentTokenRequest`](docs/openapi.yaml)，多一个 `agentUserId`。

字段含义与约束：

| 字段 | 含义 | 约束 |
|---|---|---|
| `name` | 便于人识别的名字 | ≤ 80 字符 |
| `agentUserId` | Token 归属的 Agent 用户 | 资金与冻结记在该用户名下；**仅管理员路径有**，自助路径恒为调用者 |
| `auctionIds` | 允许访问的拍卖范围 | **缺席或空 = 空集合 = 默认拒绍**（D-29）；不会退化成“全部允许” |
| `scopes` | `auction:read` / `auction:bid` | 最小权限；自助路径两档都允许（花的是用户自己的钱，时机由 D-32 兜住） |
| `expiresAt` | 过期时间 | 必须晚于当前时刻；过期即失效 |
| `rateLimitPerMinute` | 每分钟请求上限 | 默认 60，上限 6000 |

**数据库只保存 Token 摘要**；明文仅在创建响应里返回一次（`AgentToken.token`），之后无法再取回。
列表接口（含管理员的 `GET /admin/agent-tokens` 总览）**从不返回明文**；前端也只在一次性展示卡里显示它，
并在退出登录时清空缓存。明文不得出现在 Git、日志或录屏中。
吊销：`POST /api/v1/me/agent-tokens/{tokenId}/revoke`（只能销自己的，否则 404，不泄露存在性）或
`POST /api/v1/admin/agent-tokens/{tokenId}/revoke`（ADMIN）。

Agent API 使用**独立凭据与独立端口**（`:8090`），与用户侧的 JWT 不混用，见 `DECISIONS.md` D-9。

## 3. 评审操作步骤（已实现；也可直接跑 `python tools/agent_sim.py`）

> 不想手敲命令的话，[`tools/agent_sim.py`](tools/agent_sim.py) 把下面 1~6 步连同全部失败边界
> 都跑了一遍，并打印“期望 vs 实际”清单；任一条对不上就以非零退出。

1. **登录**（用户侧，`:8080`）拿到 JWT：自己要用就用普通账号；代签就用管理员账号。
2. **签发 Token**：用户自己签走 `POST /api/v1/me/agent-tokens`，管理员代签走 `POST /api/v1/admin/agent-tokens`；
   记录响应里的 `token`（只出现这一次）。不会敲命令就走前端“我的 AI 代理”页。
3. **把 Token 交给 Coding Agent**：只走环境变量 `AUCTION_AGENT_TOKEN`（脚本**不做**交互输入）。
   **不要**把它写进命令历史、脚本或仓库（`CONTRIBUTING.md` §8.3）；没设变量时脚本会明确
   报错并退 2，不静默、不降级。
4. Agent **查询状态**：`GET http://localhost:8090/api/v1/agent/auctions/{auctionId}`。
5. Agent **决策并出价**：`POST .../auctions/{auctionId}/bids`，body 含 `requestId` 与 `amount`，
   并带 `Idempotency-Key` 头；重试必须沿用同一个 `requestId`（服务端幂等）。
6. Agent **读取结果**：`GET .../auctions/{auctionId}/result`（未结算时 404）。

### 只想用自己手上的 Token 跑一遍（不建场、不签发）

```bash
export AUCTION_AGENT_TOKEN=<明文>          # Windows PowerShell: $env:AUCTION_AGENT_TOKEN="<明文>"
python tools/agent_sim.py --agent-only --auction-id <auctionId> [--bid]
```

该模式只用你给的凭据打 `:8090`，做三件事：读状态 →（带 `--bid` 时）出一次价（当前价 + 最小加价）→ 读结果。
它**不**登录管理员、**不**建场、**不**签发 Token，因此复现的是“这枚 Token 的真实权限”，
而不是业务正确性（后者仍由不带 `--agent-only` 的全流程模式与后端集成测试覆盖）。
出价被 403/409/429 拒不会算作失败——那正是你要看的权限边界。
凭据只从环境变量（`AUCTION_AGENT_TOKEN`）与同名参数读，`AUCTION_ID` / `AGENT_API_BASE`
可分别替代 `--auction-id` / `--agent-base`；读取顺序与空值处理见 [`tools/agent_credentials.py`](tools/agent_credentials.py)。
没设变量、也没有 auctionId 时都以退出码 2 停下并告诉你该设什么：

```text
!! 没拿到 Agent Token：请先设好环境变量 AUCTION_AGENT_TOKEN=<明文 Token> 再重跑。
   Windows PowerShell: $env:AUCTION_AGENT_TOKEN="<明文 Token>"
   macOS / Linux / Git Bash: export AUCTION_AGENT_TOKEN="<明文 Token>"
```

> 本文件“契约”与“实现”的一致性由 `AgentApiIntegrationTest`（29 个用例）守住：
> 范围、权限、过期、吊销、限流、端口隔离、幂等重放、错误码、尾段博弈时间拒绝 Agent，
> 以及自助授权的归属/不泄露存在性/总览不含明文，都在真库真端口上断言。

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

## 6. 评审可据此核验的点（P5 验收项，已全部勾选）

- [x] 明文 Token 只返回一次；库里只有摘要（`agent_tokens.token_hash`，明文不落库）。
- [x] 范围（`auctionIds`）、权限（`scopes`）、过期、吊销四者都真的生效（`AgentTokenTest` + `AgentTokenServiceTest` + `AgentApiIntegrationTest`；变异 G1~G4/G6/G7/G12/G13 被杀）。
- [x] 只读 Token 无法出价（403 `FORBIDDEN`）；越权访问他人拍卖被拒。
- [x] 频率限制生效并返回 429（`AgentRateLimiter`；变异 G8/G9/G10 被杀）。
- [x] Agent 出价与真人出价走同一套事务与幂等语义（复用 `BidService`，事务内自动加入，D-30；变异 G11 被杀）。
- [x] 所有断言用脱敏后的 Token 值，报告与日志里搜不到明文（`SeededDemoCredentialsTest` 的思路同样覆盖凭据不落仓库）。
- [x] 用户可以**自助**签发/吊销自己名下的授权；请求体无 `agentUserId`，他人 Token 吊销返回 404，列表与总览都无明文（D-34）。
- [x] Token 只从环境变量 `AUCTION_AGENT_TOKEN`（或同名参数）读，不做交互输入；未设置时不静默失败，
      而是打印 PowerShell/Bash 两种设法并退 2；`--agent-only` 让评审直接用自己的 Token 参与一场已有拍卖
      （`tools/agent_credentials.py`，13 条断言：显式 > 环境、空白/空串视为未设置、模块内不再有 getpass）。

> 以上验收项与 [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md) 的 D1、E1 对应，已在 P5 完成后勾选并登记证据（实现位置 `src/main/java/com/bidarena/agentaccess/`，测试 `src/test/java/com/bidarena/agentaccess/` 与 `AgentApiIntegrationTest`，变异 `tools/agent_mutation_check.py`）。
