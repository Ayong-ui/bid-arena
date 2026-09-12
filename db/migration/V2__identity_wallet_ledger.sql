-- V2：身份、钱包、资金流水，以及 V1 缺失的拍品展示/时长列与索引
--
-- 目标 MySQL 8.4（utf8mb4 / utf8mb4_0900_ai_ci）。
-- 金额一律为 BIGINT 整数积分，绝不使用浮点。
-- 字段取值以 docs/openapi.yaml 为准（Layer-1 契约），本文件是表结构的唯一权威。
--
-- 设计要点：
--   1. 不变量 INV-1（可用额非负）在数据库层兜底：CHECK (frozen_amount <= total_balance)。
--      available = total_balance - frozen_amount，因此该约束等价于 available >= 0。
--      应用层校验只是为了给出可读错误，真正的最后防线在这里。
--   2. 钱包不做乐观锁版本号：并发控制统一由「拍卖行 → 钱包行（按 user_id 升序）」的
--      悲观行锁承担（见 DECISIONS D-4、docs/FUNDING_AND_CONCURRENCY.md §3）。
--      理由是版本号在重试时仍需重读全部校验条件，不如行锁直接。
--   3. 流水只追加（append-only），且记录变更后的两个余额快照，
--      使任意一次余额变化都能被单行解释。

-- ---------------------------------------------------------------------------
-- 1. 用户（identity 上下文）
-- ---------------------------------------------------------------------------
CREATE TABLE users (
  id            VARCHAR(64)  NOT NULL COMMENT '用户 ID，API 中以字符串暴露',
  email         VARCHAR(190) NOT NULL COMMENT '登录名；190 = utf8mb4 下 InnoDB 唯一索引上限',
  display_name  VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希（含盐与前缀，固定 60 字符，留余量）',
  role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN / BIDDER',
  status        VARCHAR(16)  NOT NULL COMMENT 'ACTIVE / DISABLED',
  created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  CONSTRAINT ck_users_role   CHECK (role IN ('ADMIN', 'BIDDER')),
  CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户与登录凭据';

-- ---------------------------------------------------------------------------
-- 2. 钱包（wallet 上下文）
-- ---------------------------------------------------------------------------
CREATE TABLE wallets (
  user_id       VARCHAR(64) NOT NULL,
  total_balance BIGINT      NOT NULL COMMENT '总余额（含已冻结部分）',
  frozen_amount BIGINT      NOT NULL DEFAULT 0 COMMENT '当前冻结额；不变量 INV-1 的全部状态都在这一行上',
  updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id),
  CONSTRAINT ck_wallets_total_nonneg     CHECK (total_balance >= 0),
  CONSTRAINT ck_wallets_frozen_nonneg    CHECK (frozen_amount >= 0),
  -- 这条约束是 INV-1 的数据库级兜底：任何让可用额变成负数的写入都会被 MySQL 拒绝。
  CONSTRAINT ck_wallets_available_nonneg CHECK (frozen_amount <= total_balance),
  CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户钱包：总额 + 冻结额';

-- ---------------------------------------------------------------------------
-- 3. 资金流水（只追加；类型取值受 openapi.yaml 的 LedgerEntry.type 约束）
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_entries (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  user_id      VARCHAR(64)  NOT NULL,
  entry_type   VARCHAR(16)  NOT NULL COMMENT 'FREEZE / RELEASE / SETTLE',
  amount       BIGINT       NOT NULL COMMENT '正数金额，方向由 entry_type 决定',
  auction_id   VARCHAR(64)  NULL COMMENT '关联拍卖；FREEZE/RELEASE/SETTLE 均必须关联',
  request_id   VARCHAR(128) NULL COMMENT '关联出价幂等键，用于把流水追回到具体请求',
  total_after  BIGINT       NOT NULL COMMENT '本次变更后的总余额快照',
  frozen_after BIGINT       NOT NULL COMMENT '本次变更后的冻结额快照',
  created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- 对账/查询：某用户的时间序流水（id 兜底同毫秒排序）
  KEY idx_ledger_user_time (user_id, created_at, id),
  -- 不变量校验：按「拍卖 + 用户 + 类型」聚合，验证本场冻结净额等于当前最高价
  KEY idx_ledger_auction (auction_id, user_id, entry_type),
  CONSTRAINT ck_ledger_amount_positive CHECK (amount > 0),
  CONSTRAINT ck_ledger_type CHECK (entry_type IN ('FREEZE', 'RELEASE', 'SETTLE')),
  -- 流水写入后必须仍是不变量成立的状态，防止流水自身记录了一个非法余额
  CONSTRAINT ck_ledger_after_nonneg CHECK (total_after >= 0 AND frozen_after >= 0 AND frozen_after <= total_after),
  CONSTRAINT fk_ledger_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='资金流水，只追加';

-- ---------------------------------------------------------------------------
-- 4. auctions：补齐契约字段与结算扫描索引
-- ---------------------------------------------------------------------------
-- title / description / duration_seconds 来自 openapi.yaml 的 AuctionSnapshot
-- 与 CreateAuctionRequest（durationSeconds 为必填）。
-- 默认值 180 是原文规定的默认时长；START 命令按 duration_seconds 计算 ends_at，
-- 使测试可以创建短时长拍卖，而不必用 SQL 直接改 ends_at。
ALTER TABLE auctions
  ADD COLUMN title            VARCHAR(120)  NOT NULL DEFAULT '' AFTER id,
  ADD COLUMN description      VARCHAR(2000) NULL             AFTER title,
  ADD COLUMN duration_seconds INT           NOT NULL DEFAULT 180 AFTER min_increment;

-- 结算扫描器的核心查询是「status 在可结算态 且 ends_at <= now」。
-- 没有这个复合索引，扫描会退化成全表扫描（本表行数增长后直接拖垮结算节奏）。
ALTER TABLE auctions
  ADD KEY idx_auctions_status_ends (status, ends_at);

-- ---------------------------------------------------------------------------
-- 5. bids：出价历史按 seq 分页
-- ---------------------------------------------------------------------------
ALTER TABLE bids
  ADD KEY idx_bids_auction_seq (auction_id, server_seq);

-- ---------------------------------------------------------------------------
-- 6. bid_requests：补齐幂等记录生命周期字段
-- ---------------------------------------------------------------------------
-- 生命周期：INSERT PENDING -> 处理 -> UPDATE DONE + 首次业务结果快照。
-- 失败的请求同样落 status=DONE 并带错误码，保证重试返回同一结果、不重复生效。
-- result_code 允许为 NULL，因为 PENDING 行还没有结果。
-- 注意：位置子句（FIRST / AFTER col）在 MySQL 语法里必须排在 COMMENT 等属性之后，
-- 写成 `BIGINT NULL AFTER status COMMENT '...'` 会直接报 1064 语法错误。
ALTER TABLE bid_requests
  MODIFY COLUMN result_code VARCHAR(32) NULL COMMENT '业务结果码；PENDING 时为 NULL',
  ADD COLUMN status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE；失败也置 DONE 并带错误码' AFTER result_code,
  ADD COLUMN result_price BIGINT        NULL COMMENT '首次结果的成交价快照，用于幂等重放' AFTER status,
  ADD COLUMN result_seq   BIGINT        NULL COMMENT '首次结果的 seq 快照' AFTER result_price,
  ADD COLUMN updated_at   TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) AFTER created_at,
  ADD CONSTRAINT ck_bid_requests_status CHECK (status IN ('PENDING', 'DONE'));

-- ---------------------------------------------------------------------------
-- 7. 种子数据
-- ---------------------------------------------------------------------------
-- 密码为原文规定的演示账号密码，哈希由 jbcrypt 以 cost=10 真实生成（可直接登录）。
-- 种子直接写入 total_balance，因此初始余额没有对应流水；
-- 这是"账本从某一快照开始"的正常做法，对账时以该快照为基线。
INSERT INTO users (id, email, display_name, password_hash, role, status) VALUES
  ('usr_admin',    'admin@example.com',    '演示管理员', '$2a$10$Ag9aTviPMkO3/cdaMSGjDu4ocG7qPz2D7Kv.ZW2X/5I2lCXjzouK2', 'ADMIN',  'ACTIVE'),
  ('usr_bidder_a', 'bidder_a@example.com', '真人 A',     '$2a$10$Qo2NbUI.LqBkWQ7E6WhWo.u6hUDIpjZFHcU7xZC9f0mvEvLnhKvhi', 'BIDDER', 'ACTIVE'),
  ('usr_bidder_b', 'bidder_b@example.com', '真人 B',     '$2a$10$H6rLRiju82ZlyyxsREq/IuRdd8bDPmyyD7EaFgBeuJvhqWi1dnY/C', 'BIDDER', 'ACTIVE');

INSERT INTO wallets (user_id, total_balance, frozen_amount) VALUES
  ('usr_admin',    1000, 0),
  ('usr_bidder_a', 1000, 0),
  ('usr_bidder_b', 1000, 0);

-- 演示拍品：状态为 DRAFT，ends_at 为 NULL。
-- 原文要求"不要让拍卖在服务启动时自动倒计时"，所以这里不预置结束时间；
-- 倒计时由管理员调用 START 时才真正开始。
INSERT INTO auctions (id, title, description, status, start_price, min_increment, duration_seconds,
                      current_price, leader_id, ends_at, extension_count, seq)
VALUES ('auc_demo_0001',
        '演示拍品 · 复古机械键盘',
        '用于五分钟快速验证的演示拍品。管理员点击开始后才进入倒计时，起拍价与最小加价按原文规则设置。',
        'DRAFT', 100, 10, 180, 100, NULL, NULL, 0, 0);
