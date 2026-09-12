# Bid Arena 领域设计

## 边界

| 模块 | 责任 | 不负责 |
|---|---|---|
| auth | 登录、会话、角色 | 钱包变更 |
| auction | 拍卖状态、参与关系、快照 | 直接扣款 |
| bid-command | 接收、排序、裁决出价 | 页面展示 |
| wallet/ledger | 可用余额、冻结、释放、扣款、流水 | 判断谁赢 |
| settlement | 到期/取消后的唯一资金结果 | 接收新出价 |
| agent | Token、权限、限流和代理身份 | 绕过竞价服务 |
| realtime | 提交后事件、快照恢复 | 作为事实来源 |

## 聚合与不变量

`Auction` 是竞价聚合根。每场拍卖拥有唯一 `seq`，价格和领先者只能由出价事务同时更新。`Bid` 是不可变事实记录，`BidRequest` 是幂等入口记录，`Settlement` 是每场拍卖最多一条的结算事实。

- `current_price >= start_price`
- `leader_id is null` 或存在于参与者集合
- `ends_at` 只有 `RUNNING` 状态存在
- `available_balance = total_balance - frozen_amount >= 0`
- 同一用户在同一拍卖的冻结额等于其本场最高出价
- `settlements.auction_id` 唯一
- 所有金额为整数积分，ID 在 API 中序列化为字符串

## 状态与命令

```text
DRAFT --start--> RUNNING --deadline--> SETTLING --success--> FINISHED
  |                  |
  +-----cancel-------+------------------------------> CANCELLED
```

`START`、`CANCEL`、`BID`、`SETTLE` 都是有明确操作者和幂等键的命令。只有 `RUNNING` 且服务端收到时间严格早于 `ends_at` 时，`BID` 才可能成功。

## 读模型

列表使用摘要，详情使用权威快照；出价历史分页返回公开字段。领先者默认匿名化，只有本人和管理员可以看到需要授权的用户信息。快照中的 `serverTime` 和 `seq` 用于前端校准倒计时和恢复事件缺口。
