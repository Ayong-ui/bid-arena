# 前后端联调文档

## 1. 联调约定

默认地址：前端 `http://localhost:5173`，用户/管理员 API `http://localhost:8080`，竞拍 Agent API `http://localhost:8081`，API 前缀 `/api/v1`，WebSocket `ws://localhost:8080/ws/auctions/{auctionId}`。金额为整数积分，时间统一 ISO-8601 UTC。除登录外均需 `Authorization: Bearer <token>`。

开发环境优先由 Vite 把 `/api` 和 `/ws` 代理到后端，以避免开发期 CORS 差异；直连后端时只允许 `CORS_ORIGINS` 白名单。浏览器 WebSocket 不支持自定义 Authorization 请求头，因此先以登录 Token 调用 `POST /auth/ws-tickets` 获取 30 秒有效、一次性 ticket，再连接 `/ws/auctions/{auctionId}?ticket=...`。ticket 不写日志、不持久化到 localStorage。

统一成功响应：

```json
{"code":"OK","message":"ok","data":{},"requestId":"req-123"}
```

统一失败响应：

```json
{"code":"BID_TOO_LOW","message":"amount must be at least 120","data":{"currentPrice":110,"minIncrement":10},"requestId":"req-123"}
```

前端按 `code` 展示文案，禁止依赖 `message` 做分支。推荐错误码：`UNAUTHORIZED`、`FORBIDDEN`、`NOT_FOUND`、`INVALID_STATE`、`NOT_JOINED`、`BID_TOO_LOW`、`INSUFFICIENT_BALANCE`、`BID_LATE`、`IDEMPOTENT_REPLAY`、`RATE_LIMITED`。

HTTP 状态约定：参数错误 `400`，未认证 `401`，无权限 `403`，资源不存在 `404`，状态/出价规则冲突 `409`，限流 `429`，未知服务端错误 `500`。业务拒绝不得用 `200` 伪装成功。

## 2. 登录与基础接口

| 方法 | 路径 | 请求/响应要点 |
|---|---|---|
| POST | `/auth/login` | `{email,password}` -> `{accessToken,expiresAt,user}` |
| POST | `/auth/ws-tickets` | Bearer Token -> 短效、一次性 `{ticket,expiresAt}` |
| GET | `/users/me` | 当前用户、角色、状态 |
| GET | `/wallets/me` | `totalBalance,frozenAmount,availableBalance,ledger[]` |
| GET | `/auctions` | 支持 `status,page,size`；返回列表摘要 |
| GET | `/auctions/{id}` | 权威快照：状态、价格、匿名领先者、`endsAt`、`extensionCount`、参与人数、`seq`、`serverTime` |
| GET | `/auctions/{id}/bids` | 分页出价；仅公开允许字段 |
| POST | `/auctions/{id}/join` | 无请求体；幂等返回参与关系 |

管理员：`POST /admin/auctions`（`title,description,startPrice,minIncrement,durationSeconds`）、`POST /admin/auctions/{id}/start`、`POST /admin/auctions/{id}/cancel`、`POST /admin/agent-tokens`。

## 2.1 竞拍 Agent API（独立受限端口 8081）

Agent 使用独立后端监听端口 `8081`，基础地址为 `http://localhost:8081/api/v1/agent`。生产环境可由网关映射为 `https://agent-api.example.com/api/v1/agent`，但必须保持与用户/管理员 API（8080）独立的路由、限流和审计策略，不应直接暴露数据库或管理员端口。所有请求必须携带创建时签发的 `AUCTION_AGENT_TOKEN`：

```http
Authorization: Bearer <agent-token>
```

Agent Token 绑定 `userId`、允许的 `auctionIds`（或范围）、权限 `auction:read`/`auction:bid`、过期时间、吊销状态和限流值。Agent 只能代表绑定用户在授权拍卖中读取快照、读取自己的出价结果和提交出价，不能调用 `/admin/*`、代替其他用户或读取钱包隐私。

| 方法 | Agent 路径 | 说明 |
|---|---|---|
| GET | `http://localhost:8081/api/v1/agent/auctions/{id}` | 返回权威拍卖快照，需 `auction:read` |
| GET | `http://localhost:8081/api/v1/agent/auctions/{id}/result` | 返回结束状态、赢家是否为绑定用户、成交价，需 `auction:read` |
| POST | `http://localhost:8081/api/v1/agent/auctions/{id}/bids` | `{requestId,amount}` 出价，需 `auction:bid`，遵循幂等和最低加价规则 |

Agent 出价沿用第 3 节的 `Idempotency-Key`、错误码和 `409/429` 状态约定。典型响应：

```json
{"code":"OK","message":"ok","data":{"accepted":true,"idempotent":false,"price":320,"seq":18,"serverTime":"2026-09-11T08:00:00Z"},"requestId":"req-123"}
```

建议网关层对 Agent 路由单独配置限流、审计和 IP 白名单；Token 只返回一次明文，服务端仅保存摘要并支持立即吊销。

核心快照建议固定为以下 TypeScript 契约，OpenAPI 生成类型后应替换手写定义：

```ts
interface AuctionSnapshot {
  id: string
  title: string
  status: 'DRAFT' | 'RUNNING' | 'SETTLING' | 'FINISHED' | 'CANCELLED'
  startPrice: number
  minIncrement: number
  currentPrice: number
  leader: string | null
  endsAt: string | null
  extensionCount: number
  participantCount: number
  seq: number
  serverTime: string
}
```

## 3. 出价联调

请求：

```http
POST /api/v1/auctions/{id}/bids
Idempotency-Key: user-generated-request-id
Content-Type: application/json

{"requestId":"8b3...","amount":120}
```

成功响应 `data` 至少包含 `accepted,idempotent,price,leader,extensions,seq,serverTime`。同一用户重试相同 `requestId` 必须返回第一次结果，不能产生新的冻结、流水或 `seq`。金额输入框使用整数，提交期间禁用重复点击；收到 `BID_LATE` 等业务拒绝时保留服务端当前价。

`Idempotency-Key` 与请求体 `requestId` 必须相同；不一致返回 `400 INVALID_REQUEST_ID`。网络超时后前端必须复用原 requestId 重试，只有用户发起下一次新出价时才生成新 ID。

## 4. WebSocket 协议

连接后服务端发送 `AUCTION_SNAPSHOT`。事件格式：

```json
{"type":"BID_ACCEPTED","auctionId":"a1","seq":14,"serverTime":"2026-09-11T08:00:00Z","payload":{"price":120,"leader":"u***","endsAt":"...","extensionCount":1}}
```

实现事件：`AUCTION_SNAPSHOT`、`PARTICIPANT_JOINED`、`BID_ACCEPTED`、`BID_REJECTED`（仅本人）、`AUCTION_EXTENDED`、`AUCTION_FINISHED`、`CONNECTION_STATE`。前端保存 `lastSeq`；收到 `seq > lastSeq + 1` 时暂停本地合并，重新 GET 快照，成功后再恢复。`serverTime` 用于校准倒计时，连接断开显示“重连中”并禁用假实时状态。

快照恢复期间缓存新事件；快照返回后丢弃 `seq <= snapshot.seq` 的事件，再按 seq 连续应用剩余事件。若仍有缺口则再次拉取快照，避免旧 HTTP 响应覆盖较新的 WebSocket 状态。

## 5. 联调顺序与验收

1. `docker compose up --build`，确认迁移和种子日志成功。
2. 管理员登录，创建或开始草稿拍卖；浏览器 Network 校验 `RUNNING` 与 `endsAt`。
3. 两个用户分别登录、加入同一拍卖，确认 WebSocket 快照 `seq` 一致。
4. 用户 A 出价 110，用户 B 低于 120 被拒绝；A 重试同一 `requestId` 返回 `idempotent=true`。
5. 在截止前 5 秒出价，确认 `endsAt` 延长 10 秒且最多三次。
6. 断开网络再恢复，确认快照补齐而非使用旧本地价格；等待自动结算，核对赢家、钱包和流水。
7. 使用 `AUCTION_AGENT_TOKEN` 调用 Agent API，验证拍卖范围、权限、过期、吊销和限流。

## 6. 环境变量与问题定位

前端：`VITE_API_BASE_URL`、`VITE_WS_BASE_URL`。后端：`DB_URL`、`DB_USER`、`DB_PASSWORD`、`JWT_SECRET`、`CORS_ORIGINS`。跨域失败先看后端 CORS 和浏览器预检；401 检查 Token 过期与时钟；seq 缺口看 WebSocket 日志并重新拉快照；余额不一致以 MySQL `wallets` 与 `ledger_entries` 为准。不得把 Token、密码或完整 Authorization 写入日志。
