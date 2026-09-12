# 命令流与实时事件

## 第一版处理路径

```text
HTTP/Agent -> 校验身份与 requestId -> BidCommandService
          -> 锁 auction / wallet -> MySQL 事务提交
          -> outbox/事件发布 -> WebSocket 广播快照或事件
```

MySQL 是唯一事实来源，队列不是裁判，也不能直接把“最大金额”推给前端。第一阶段用行锁完成正确闭环；第二阶段可按 `auctionId` 将命令分区到单实例内存队列或 RocketMQ 顺序消息，以降低锁竞争。消费者重试、重复消息和进程重启都必须依靠数据库幂等恢复。

`START`、`BID`、`CANCEL`、`SETTLE` 最终应进入同一拍卖命令流，避免到期任务与最后一笔出价出现顺序歧义。命令记录服务端 `received_at`，截止判断使用该时间而不是客户端时间。

## 事件协议

提交成功后广播：

```json
{
  "type": "BID_ACCEPTED",
  "auctionId": "a1",
  "seq": 14,
  "serverTime": "2026-09-11T08:00:00Z",
  "payload": {"price": 120, "leader": "u***", "endsAt": "2026-09-11T08:00:10Z", "extensionCount": 1}
}
```

客户端只接受连续 `seq`。发现缺口、重连或事件版本落后时，暂停合并并 GET 权威快照；快照返回后丢弃 `seq <= snapshot.seq` 的旧事件，再按序应用剩余事件。

## 可观测性

每个命令记录 `requestId`、`auctionId`、`userId`、`receivedAt`、处理结果和 `seq`。日志不得包含密码、Token 或完整 Authorization。队列引入后必须监控积压、处理延迟、重复率和死信，不得以丢弃消息换取实时性。
