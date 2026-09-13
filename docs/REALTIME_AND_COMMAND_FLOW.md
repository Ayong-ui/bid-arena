# 命令流与实时事件

> 本文件是 **WebSocket 事件、`seq` 语义与快照恢复的权威出处**。
> 业务规则与数值见 [`全栈评测-拍卖间-原文.md`](../全栈评测-拍卖间-原文.md)；HTTP 接口见 [`openapi.yaml`](openapi.yaml)。
> 若事件结构与正文冲突，以本文件为准。

## 1. 命令处理路径

```
HTTP / Agent
  → 校验身份与 requestId
  → 命令服务（锁 auction → wallet 按 user_id 升序 → 幂等记录）
  → MySQL 事务提交
  → 事件发布（提交后）
  → WebSocket 广播
```

MySQL 是唯一事实来源；**队列不是裁判**，不能把“最大金额”直接推给前端当结果。

**WebSocket 不是命令入口**：所有状态变更命令都走 HTTP / Agent API。WS 只承载服务端 → 客户端的通知，
客户端向 WS 发送的任何内容都被忽略（见 §6）。

## 2. 命令流一致性

- `START`、`BID`、`CANCEL`、`SETTLE` 最终进入同一拍卖命令流，避免到期任务与最后一笔出价出现顺序歧义。
- 命令记录服务端接收时间；任何时间判定使用该时间，不使用客户端时间。
- 第一阶段可用行锁完成正确闭环；若后续引入按拍卖分区或顺序消息，仍须以数据库幂等恢复，不能依赖队列可达性。

## 3. 事件信封（权威）

每个事件都是如下结构的 JSON 文本帧：

```json
{
  "type": "BID_ACCEPTED",
  "auctionId": "auc_7f3a91c2d0e5",
  "seq": 14,
  "serverTime": "2026-09-11T08:00:00Z",
  "payload": { }
}
```

| 字段 | 约束 |
|---|---|
| `type` | 见 §4 |
| `auctionId` | 事件所属拍卖；握手失败且路径里也没有合法拍卖 ID 时为 `""` |
| `seq` | **单场单调递增**，见下方“`seq` 的归属” |
| `serverTime` | 服务端时间，ISO-8601 UTC（取数据库时间，D-5） |
| `payload` | 该事件类型的增量字段；无字段时是空对象（**不是** `null`） |

### `seq` 的归属（一次命令 = 一个版本号）

`auctions.seq` 是**该场拍卖已提交状态变更的版本号**：下列每一次成功提交都恰好 `+1`。

| 命令 | 是否 +1 | 说明 |
|---|---|---|
| `POST /auctions/{id}/join` | 是 | 参与人数变了，客户端错过它也算一次可检测的版本变化 |
| `POST /admin/auctions/{id}/start` | 是 | 状态 `DRAFT → RUNNING`、截止时间确定 |
| `POST /auctions/{id}/bids` | 是 | 价格、领先者、冻结资金、可能的延时 |
| 到期结算 / 取消 | 是 | 终局状态与资金归位 |
| 被拒绝的出价 | **否** | 没有状态变更；事件携带**当前** `seq`，便于客户端对齐基线 |

**同一次提交产生的多个事件共享同一个 `seq`**（例如“最后 5 秒内的出价”会同时发出
`BID_ACCEPTED` 与 `AUCTION_EXTENDED`，两者 `seq` 相同）。因此客户端的缺口判定是
“`seq` 是否连续”，**不是**“收到了几条消息”；同一 `(auctionId, seq, type)` 重复到达时按幂等丢弃。

### 并发下不保证广播顺序与 `seq` 顺序一致

两个并发命令可能“先提交 `seq=6` 的命令先广播、后提交 `seq=5` 的命令后广播”：提交与广播之间隔着一次
线程调度。这是**允许**的，且不需要服务端额外排序——客户端按 §6 的规则处理（发现缺口就拉快照，
快照之后丢弃 `seq <= snapshot.seq` 的事件）。

## 4. 事件类型（权威）

| 事件 | 广播范围 | payload 关键字段 |
|---|---|---|
| `AUCTION_SNAPSHOT` | 参与者 | `id`/`title`/`status`/`startPrice`/`minIncrement`/`currentPrice`/`leader`/`endsAt`/`extensionCount`/`participantCount`/`seq`/`finalGameWindowSeconds`/`serverTime`（与 HTTP `AuctionSnapshot` 同构，缺 `description`；`finalGameWindowSeconds` 是尾段“博弈时间”窗口，前端只用于提示，见 D-32） |
| `PARTICIPANT_JOINED` | 参与者 | `participant`（匿名标识）、`participantCount` |
| `BID_ACCEPTED` | 参与者 | `price`、`leader`（匿名）、`endsAt`、`extensionCount` |
| `BID_REJECTED` | **仅请求者** | `code`、`reason`、`minimum`、`currentPrice`、`amount` |
| `AUCTION_EXTENDED` | 参与者 | `endsAt`、`extensionCount` |
| `AUCTION_FINISHED` | 参与者 | `winner`（匿名）、`finalPrice`、`status`（`FINISHED`/`CANCELLED`）、`reason` |
| `CONNECTION_STATE` | **本人** | `connected`、`snapshotSeq`、`code`（握手失败时的原因码，见 §5） |

### 匿名标识

事件里的 `leader` / `winner` / `participant` 是**匿名标识**，不是 `user_id`：
`anon-` + `SHA-256(user_id)` 的前 8 位十六进制（小写）。例如 `user_id = usr_bidder_b` → `anon-…`。

- 为什么：事件是广播给一场拍卖的**所有参与者**的，直接带 `user_id` 等于把用户标识体系泄露给所有同场用户，
  且这份 payload 会被前端缓存、写进日志、录进回放。
- 为什么仍然“本人可识别”：算法公开且确定，客户端对自己的 `user_id` 算一次就能比对出
  `leader === myAnonId`，从而高亮“我当前领先”。因此**不需要**为每个连接生成不同 payload（那会破坏“一次广播”）。
- HTTP 快照（`GET /auctions/{id}`）按 `openapi.yaml` 继续返回 `user_id`：它是已鉴权的单播读，
  与 WS 广播的威胁模型不同。前端展示统一用匿名标识。

## 5. WebSocket 接入

### 地址与端口

```
ws://<host>:<wsPort>/ws/auctions/{auctionId}?ticket=<ticket>
```

- `wsPort` **不等于** HTTP 端口：Solon 的 WebSocket 插件是独立监听器，默认监听 `server.port + 10000`。
  本项目把它显式化为 `WS_PORT`（见 `.env.example`），并由 `POST /auth/ws-tickets` 在响应里告知客户端
  （`wsPort` / `wsPath`）——前端不得自行推导端口，否则“改了端口就静默连不上”会变成前端缺陷。
- 客户端先 `POST /auth/ws-tickets` 取一次性 `ticket`，再连接。

### 为什么用 ticket 而不是 `Authorization` 头

浏览器的 `WebSocket` 构造器**不能设置请求头**，所以要么把令牌放进 URL，要么先换一张短票。
放进 URL 的令牌会进入反向代理访问日志、浏览器历史与录屏，且有效期到 `JWT_TTL`（默认 8 小时）；
换成 ticket 后：**一次性**、TTL 默认 60 秒、用后即废，泄露的窗口与价值都被压到最小。
（非浏览器客户端同理——服务端的 WS 插件只把握手 URI 暴露给业务代码，不暴露请求头，所以本方案对两类客户端一致。）

### 握手与授权

1. 核销 `ticket`（不存在 / 已用过 / 已过期 → 失败，原因码 `UNAUTHENTICATED`）；
2. 拍卖必须存在（否则 `NOT_FOUND`）；
3. `BIDDER` 必须是该场参与记录里的成员（否则 `NOT_JOINED`）；`ADMIN` 可订阅任意拍卖（只读观察）。

握手失败时服务端**先发一帧 `CONNECTION_STATE`**（`payload.connected = false`、`payload.code = 原因码`、
`seq = 0`）再关闭连接：客户端因此能区分“票过期了”和“网络断了”，而不是面对一个没有理由的断开。
失败帧不包含任何拍卖数据。

### 连接建立后的两帧

按顺序发送（都是单播给这条连接）：

1. `AUCTION_SNAPSHOT`——权威快照，客户端以此为基线（`lastSeq = payload.seq`）；
2. `CONNECTION_STATE`——`connected = true`、`snapshotSeq` = 上面那个 `seq`。

### 可见性边界

事件只会发给**该场拍卖参与记录里的用户**（以及订阅了该场的 `ADMIN`）。
因此 `BID_REJECTED` 只需再按 `user_id` 过滤一次，就满足“仅请求者可见”。

## 6. 序号与快照恢复（权威）

1. 客户端保存 `lastSeq`（初始为 `CONNECTION_STATE.snapshotSeq`）。
2. 收到 `seq > lastSeq + 1`（缺口）、连接重建、或事件版本落后时：**暂停本地合并**，立即 `GET /api/v1/auctions/{id}` 拉取权威快照。
3. 快照返回后，丢弃 `seq <= snapshot.seq` 的缓存事件，再按 `seq` 连续应用剩余事件。
4. 若仍有缺口则再次拉取快照，避免旧响应覆盖较新的实时状态。
5. 客户端不得用本地倒计时推断状态；`serverTime` 只用于校准展示。

“断线后重新获取快照”是**同一条路径**：重连后先收到 `AUCTION_SNAPSHOT`（§5），
不需要另一套“补发历史事件”的机制——服务端不存事件回溯缓冲，权威状态永远在数据库里。

### 客户端向 WS 发送消息

一律忽略（不解析、不执行、不回复业务语义）。重新同步走 HTTP 快照接口；
这样“命令入口只有一个”，不会出现“WS 能改状态但绕过了 RBAC/幂等”的第二条路径。
协议层的 `ping`/`pong` 由 Solon 的 WS 实现自行处理，不属于业务消息。

## 7. 广播失败边界

- 事件在**事务提交后**发布；广播失败**不得回滚**已提交事务。
  实现上：命令服务在提交后调用发布端口，并用 `try/catch` 兜住一切异常（发布端口内部也各自兜一层），
  因此“通知”永远不会把一次成功的出价变成失败。
- 广播异常记日志并计数（`WsEventBroadcaster` 的 `sent`/`failed`/`rejected` 计数，见 §8），
  发送失败的连接在下一次广播时被清理；客户端依靠下一次快照恢复，而不是依赖消息必达。
- 任何“通知”都不构成资金或赢家事实。

## 8. 可观测性

- 每个命令记录：`requestId`、`auctionId`、`userId`、服务端接收时间、处理结果、`seq`。
- WS 侧计数：已发送事件数、发送失败数、被拒绝的握手数、当前订阅连接数（`WsEventBroadcaster.stats()`），
  并记 `WARN` 日志（含 `auctionId`、事件类型、失败原因）。
- 日志脱敏密码、Token 与完整 Authorization；**日志里不出现 ticket**（ticket 只在签发响应里出现一次）。
- 若引入队列，必须监控积压、处理延迟、重复率与死信，且不得以丢弃消息换取实时性。
