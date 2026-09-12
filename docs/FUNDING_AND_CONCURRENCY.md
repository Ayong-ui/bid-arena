# 资金与并发设计

## 钱包口径

钱包保存 `total_balance` 与 `frozen_amount`，可用余额实时计算为 `total_balance - frozen_amount`。冻结不是转入平台账户，而是用户钱包内的受限额度，因此跑腿项目的 escrow 语义不能直接复用。

一次用户加价到 `new_amount` 时，本场已冻结 `old_amount`，只增加 `max(0, new_amount - old_amount)`。成为新领先者后，旧领先者本场冻结全部释放。冻结、释放、出价记录、流水和拍卖价格必须在同一数据库事务中完成。

## 锁顺序

```text
auction 行 -> 相关 wallet 行（按 userId 升序）-> bid_request/bid
```

所有入口遵守同一顺序，死锁时有限重试；不得先锁钱包再锁拍卖。事务提交后才发布 WebSocket/Outbox 事件，广播失败不能回滚资金事务。

## 幂等

客户端同时发送 `Idempotency-Key` 请求头和 body `requestId`，两者必须相同。数据库唯一键为 `(auction_id, user_id, request_id)`。重试命中已完成记录时返回第一次的业务结果，不增加价格、冻结、流水或 `seq`；处理中记录可返回 `409 IDEMPOTENCY_REPLAY`。

## 结算与取消

扫描器用条件更新或行锁把到期 `RUNNING` 抢占为 `SETTLING`。结算事务锁拍卖和相关钱包：赢家本场冻结转扣款，其他参与者冻结释放，写唯一 settlement 和流水，最后改为 `FINISHED`。重启扫描 `RUNNING/SETTLING`，重复触发命中唯一 settlement 后安全返回。

取消只允许 `DRAFT/RUNNING`，释放本场全部冻结并写流水，状态改为 `CANCELLED`；取消和结算互斥。

## 必测边界

- 余额不足、未加入、低于最低加价、非 RUNNING、服务端已截止均拒绝且不产生资金变化。
- 同一用户连续加价只冻结差额。
- 两个用户并发出价后，最终领先者、价格、冻结和流水必须能由数据库解释。
- 事务任一步失败时不允许价格已更新而资金未更新。
