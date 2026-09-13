-- V5：尾段"博弈时间"清场 Agent，并为成交主体留下可追溯标识。
--
-- 背景（见 DECISIONS D-32）：
--   拍卖最后 20 秒是留给真人博弈的窗口，竞拍 Agent 在该窗口内一律禁止出价
--   （由 BidService 在事务内用数据库时间判定，返回 HUMAN_ONLY_PERIOD）。
--   为了让运营与本人事后能确认"最后成交的是 AI 还是人"，把主体类型落到三处：
--     bids.actor_type          —— 这一笔到底是谁出的（唯一事实来源）
--     settlements.winner_type  —— 结算时快照，之后不再受后续数据变化影响
--     ledger_entries.actor_type —— 让个人流水与管理员流水能看出主体
--
-- 为什么不用 auction_participants.participant_type：
--   它首次加入后不再更新（join 是 ON DUPLICATE KEY UPDATE user_id = user_id），
--   人先加入、Agent 后用同一账号出价会把主体记成 HUMAN。
--   主体类型必须绑定在"这笔出价 / 这条流水"上。
--
-- 存量回填：V5 之前的所有出价与流水都来自真人前端或真人脚本，回填 HUMAN 是事实。

ALTER TABLE bids
  ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'HUMAN' AFTER user_id,
  ADD CONSTRAINT ck_bids_actor_type CHECK (actor_type IN ('HUMAN', 'AGENT'));

ALTER TABLE settlements
  ADD COLUMN winner_type VARCHAR(16) NULL AFTER winner_id,
  ADD CONSTRAINT ck_settlements_winner_type
    CHECK (winner_type IS NULL OR winner_type IN ('HUMAN', 'AGENT'));

ALTER TABLE ledger_entries
  ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'HUMAN' AFTER amount,
  ADD CONSTRAINT ck_ledger_actor_type CHECK (actor_type IN ('HUMAN', 'AGENT'));

-- 管理员"按场次看流水"的查询：WHERE auction_id = ? ORDER BY id DESC。
-- V2 的 (auction_id, user_id, entry_type) 前缀能过滤场次，但排不了 id 序，因此单加一条。
ALTER TABLE ledger_entries
  ADD KEY idx_ledger_auction_id (auction_id, id);
