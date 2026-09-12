# Bid Arena 设计方案

## 1. 目标与当前边界

Bid Arena 是一个多人实时、限时拍卖系统。MySQL 是唯一事实来源，Solon 后端是唯一业务裁判，Vue 只负责展示和发起意图，竞拍 Agent 只能通过受限 API 参与。

当前仓库已验证 `AuctionEngine` 的状态机、冻结/释放、幂等、狙击延时和唯一结算；尚未实现 HTTP、鉴权、MySQL Repository、WebSocket 和 Vue。本文是下一阶段实现的目标架构，不把尚未存在的模块误认为已完成。

## 2. 组件与职责

```text
Vue 3 + Pinia  ──HTTP/JSON──>  Solon API  ──事务/行锁──> MySQL 8
       │                            │
       └────WebSocket 事件<─────────┘
竞拍 Agent ──Token API──> Agent API :8081 ──┘
```

- **前端**：路由、表单校验、倒计时展示、Pinia 快照/事件合并、断线重连；不计算赢家、余额或最终截止时间。
- **Solon API**：认证、RBAC、请求校验、领域服务、统一错误响应、WebSocket 广播、结算扫描任务。
- **Agent API（独立端口 :8081）**：仅暴露 `/api/v1/agent/**`，使用 Agent Token 鉴权和独立限流；不得访问管理员路由、任意用户钱包或数据库。
- **领域层**：保留 `AuctionEngine` 规则，改为由事务服务提供持久化状态；规则层不依赖 HTTP 或 Vue。
- **MySQL**：用户、钱包、流水、拍卖、参与者、出价、幂等请求、成交和 Agent Token 的权威数据。
- **WebSocket**：只做提交后通知，广播失败不回滚已提交事务；客户端可用快照恢复。

## 3. 状态机与事务

状态流转：`DRAFT -> RUNNING -> SETTLING -> FINISHED`；`DRAFT/RUNNING -> CANCELLED`。只有 `RUNNING` 且服务端接收时间严格早于 `ends_at` 才能出价。

### 原子出价

一个 MySQL 事务内按固定顺序执行：拍卖行锁 -> 参与者/本场冻结 -> 相关钱包按 `user_id` 排序 -> 幂等记录检查。校验状态、截止时间、加入关系、金额下限和新增冻结余额后，释放旧领先者冻结，冻结新领先者差额，写资金流水、`bids` 和 `bid_requests`，更新价格/领先者/`ends_at`/`extension_count`/`seq`，最后提交。唯一键 `(auction_id,user_id,request_id)` 保证重复请求只处理一次；死锁按有限次数重试。

### 唯一结算

扫描器查找到期 `RUNNING` 记录，以条件更新或行锁抢占为 `SETTLING`。事务内锁拍卖和钱包，赢家冻结转扣款，其他冻结释放，插入唯一 `settlements` 和流水，再改 `FINISHED`。启动恢复继续处理遗留 `RUNNING/SETTLING`；重复触发命中唯一成交记录，不重复扣款。

## 4. 资金模型

金额使用整数积分。`available_balance = total_balance - frozen_amount`，任何更新都保证非负。出价只冻结 `max(0, amount - user_bid_frozen_in_auction)`；成为新领先者时释放上一领先者本场冻结。失败请求不写业务变化；每次冻结、释放、扣款均写 `ledger_entries`，可按 `request_id` 追溯。

## 5. 实时一致性与恢复

每场拍卖维护单调递增 `seq`。所有确认事件包含 `auctionId/seq/serverTime/type`。客户端若发现序号缺口、连接重建或事件版本落后，立即 `GET /api/v1/auctions/{id}` 获取权威快照，并以快照的 `seq` 为新基线；不使用本地倒计时推断状态。事件在事务提交后发布，广播异常记录日志并依靠下一次快照恢复。

## 6. 安全与可观测性

登录返回短期 Bearer Token；管理员接口要求 `ADMIN`。Agent Token 只存 SHA-256 摘要，绑定用户、拍卖范围、权限、过期时间、吊销时间和限流值，明文仅创建响应返回一次。日志脱敏 Token、密码和钱包隐私；请求携带 trace/request id，关键事务记录 auction、user、request、seq 和结果码。

## 7. 部署与演进

Docker Compose 启动 MySQL、后端和前端，Flyway（或等价迁移器）自动迁移并种子演示账号/草稿拍卖。Redis 暂不作为事实来源；若未来用于广播或限流，故障时均回退 MySQL，不能依赖 Redis 恢复余额或赢家。

推荐按以下顺序实现，保证每一阶段均可独立验证：

1. 补齐用户、钱包、流水和本场冻结表迁移，实现 MySQL Repository 与事务型领域服务。
2. 接入登录、RBAC、统一响应和拍卖 HTTP API，使用集成测试锁定接口契约。
3. 实现事务提交后的领域事件与 WebSocket，补 seq 缺口/快照恢复测试。
4. 建立 Vue 3 + TypeScript + Pinia 前端，先接 HTTP 快照，再接实时事件。
5. 加入 Agent Token、模拟脚本、Compose 和端到端验收。

## 8. 验证重点

单测覆盖规则；Testcontainers MySQL 覆盖并发出价、幂等、结算和重启恢复；Vue 测试覆盖 Pinia 快照、seq 缺口和重连；模拟脚本覆盖 20 人、狙击延时、余额不足和重复 requestId。验收以数据库余额、流水、成交记录与公开 API 快照一致为准。
