-- V3：按场次记录冻结额
--
-- 为什么需要这张表以外的信息：
--   原文的余额口径是「出价校验的是本次需新增冻结的金额，即出价金额减去该用户在本场已冻结金额」。
--   仅靠 wallets.frozen_amount（跨所有场次的总冻结）无法算出「本场已冻结」，
--   因此冻结状态必须按 (auction_id, user_id) 这个粒度保存。
--
-- 为什么放在 auction_participants 而不是新表：
--   该表主键本来就是 (auction_id, user_id)，与所需粒度完全一致，
--   且一行同时表达「该用户在这场里」与「该用户在这场冻结了多少」，语义自然。
--
-- 与 wallets.frozen_amount 的关系（两者必须始终一致）：
--   wallets.frozen_amount = 该用户所有场次 frozen_amount 之和。
--   两者在同一事务内一起更新；校验 SQL 见 docs/TRACEABILITY.md 的不变量映射。
--
-- 锁顺序（见 DECISIONS D-4）：
--   auctions 行  ->  auction_participants 行（按 user_id 升序）
--                ->  wallets 行（按 user_id 升序）
--   三名领先者转移时涉及「新领先者」与「旧领先者」两个用户，
--   必须以固定顺序加锁，否则两场拍卖同时换领先者会互相等对方持锁而死锁。

ALTER TABLE auction_participants
  ADD COLUMN frozen_amount BIGINT NOT NULL DEFAULT 0
    COMMENT '本场该用户的冻结额；所有场次之和必须等于 wallets.frozen_amount' AFTER participant_type,
  ADD CONSTRAINT ck_participants_frozen_nonneg CHECK (frozen_amount >= 0);

-- 结算时需要「本场所有还有冻结的用户」，按场次扫描并筛出非零行
ALTER TABLE auction_participants
  ADD KEY idx_participants_auction_frozen (auction_id, frozen_amount);
