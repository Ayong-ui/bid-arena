-- V6：拍卖排期（预告到点自动开拍）与用户自助的托管 AI 代理
--
-- 目标 MySQL 8.4（utf8mb4 / utf8mb4_0900_ai_ci）。
--
-- 设计要点：
--   1. auctions.starts_at 是"预告开拍时间"，可空：
--      为空 = 旧行为（只有管理员手动 START 才会开拍）；不为空 = 到点由 AuctionStartScheduler 自动 START。
--      它不参与出价、延时、结算的任何判定，纯粹是排期，所以新增列不需要回填，也不需要改任何已有查询。
--   2. agent_proxies 一行 = 一个用户对一场拍卖的一名托管代理。
--      唯一键 (owner_user_id, auction_id) 表达"同一场同一人只有一个代理位"；
--      撤销后重建会**重置同一行**而不是新增行（见 AgentProxyService.create 的注释）。
--      这样唯一键不会因为"撤销过又重开"而被迫放宽成部分索引（MySQL 没有 partial unique index）。
--   3. 代理只记录"意图"（预算、状态、计数）。钱仍然只走 bids / ledger 这一条路径：
--      代理出价调用的是与真人完全相同的 BidService.placeBid，因此 actor_type=AGENT、
--      博弈时间拒绝（HUMAN_ONLY_PERIOD）、冻结与释放语义自动一致，不存在第二套资金逻辑。
--      这正是不给代理单独建一张"委托资金"表的原因——那会立刻产生两套对账口径。
--   4. budget_reached_at 是"已达预算"的提醒标记，只在首次触顶时写入一次；
--      它存在数据库而不是内存，使服务重启后不会重复提醒、也不会忘记提醒。

-- ---------------------------------------------------------------------------
-- 1. 拍卖排期
-- ---------------------------------------------------------------------------
ALTER TABLE auctions
  ADD COLUMN starts_at TIMESTAMP(6) NULL COMMENT '预告开拍时间；NULL = 仅管理员手动开始' AFTER ends_at;

-- 开拍扫描器的核心查询是「status=DRAFT 且 starts_at <= now」，与结算扫描器同构。
ALTER TABLE auctions
  ADD KEY idx_auctions_status_starts (status, starts_at);

-- ---------------------------------------------------------------------------
-- 2. 托管 AI 代理
-- ---------------------------------------------------------------------------
CREATE TABLE agent_proxies (
  id                VARCHAR(64)  NOT NULL,
  owner_user_id     VARCHAR(64)  NOT NULL COMMENT '代理归属用户；出价与冻结都记在该用户名下',
  auction_id        VARCHAR(64)  NOT NULL,
  budget_limit      BIGINT       NOT NULL COMMENT '硬预算上限（整数积分）；跟价金额永不超过它',
  status            VARCHAR(24)  NOT NULL COMMENT 'PENDING / BIDDING / BUDGET_REACHED / FINISHED / REVOKED',
  bid_count         INT          NOT NULL DEFAULT 0 COMMENT '成功出价次数，用于在界面上解释"AI 做了什么"',
  last_bid_amount   BIGINT       NULL COMMENT '最后一次成功出价的金额',
  budget_reached_at TIMESTAMP(6) NULL COMMENT '首次触顶时刻；一次性提醒的幂等依据',
  won               BOOLEAN      NULL COMMENT '结算结果快照：是否赢家；未结束为 NULL',
  final_price       BIGINT       NULL COMMENT '结算结果快照：成交价；未结束为 NULL',
  created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  revoked_at        TIMESTAMP(6) NULL,
  PRIMARY KEY (id),
  -- 一场一人一个代理位。撤销后重开会重置这一行，因此这里不需要部分索引。
  UNIQUE KEY uk_proxy_owner_auction (owner_user_id, auction_id),
  -- 调度器每轮按「状态 + 拍卖」取待办代理。
  KEY idx_proxy_status_created (status, created_at),
  -- 「我的 AI 代理」按时间倒序分页。
  KEY idx_proxy_owner_created (owner_user_id, created_at),
  CONSTRAINT ck_proxy_budget_positive  CHECK (budget_limit > 0),
  CONSTRAINT ck_proxy_bid_count_nonneg CHECK (bid_count >= 0),
  CONSTRAINT ck_proxy_status CHECK (status IN ('PENDING', 'BIDDING', 'BUDGET_REACHED', 'FINISHED', 'REVOKED')),
  CONSTRAINT fk_proxy_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户托管的自动跟价 AI 代理（一场一人一个）';
