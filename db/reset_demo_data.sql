-- 开发库「恢复到种子状态」脚本（只动数据：不改 schema、不碰 flyway_schema_history）。
--
-- 为什么需要它
--   tools/auction_sim.py / tools/agent_sim.py / tools/stress_test.py 会在**真实库**里真的花钱。
--   连跑几次之后演示账号（种子各 1000）余额耗尽，之后所有出价都会 INSUFFICIENT_BALANCE，
--   脚本会打出十几条 FAIL（还可能以一个与本因无关的 WebSocket 超时收场）。
--   这不是缺陷，是"数据用完了"——一条命令恢复到种子状态，比对着失败清单猜要省事得多。
--
-- 用法（容器名与口令按你的 .env 替换；口令仅出现在命令行，不会写进本文件）
--   docker exec -i bid-arena-mysql-1 mysql --default-character-set=utf8mb4 \
--     -ubid_arena -p"$DB_PASSWORD" bid_arena < db/reset_demo_data.sql
--
--   `--default-character-set=utf8mb4` 不能省：本文件含中文注释，客户端默认字符集若是 latin1，
--   字节会被当成 latin1 解释，连“用中文当列别名”这种写法都会直接报 ERROR 1064。
--   下面的回显查询句因此刻意只用 ASCII 别名。
--
-- 口径：与 db/migration/V1/V2 的种子保持一致——三个账号各 1000 总余额、0 冻结，
--       外加一场 DRAFT 的演示拍品（倒计时仍要管理员点 START 才开始）。

SET FOREIGN_KEY_CHECKS = 0;

-- 顺序与测试基座 TestDatabase.TABLES_IN_WIPE_ORDER 一致：先删引用方，再删被引用方。
-- users 保留：账号与口令哈希没变，重建它们只会让文档里的演示账号失效。
TRUNCATE TABLE ledger_entries;
TRUNCATE TABLE bids;
TRUNCATE TABLE bid_requests;
TRUNCATE TABLE settlements;
TRUNCATE TABLE auction_participants;
TRUNCATE TABLE auctions;
TRUNCATE TABLE agent_token_auctions;
TRUNCATE TABLE agent_tokens;
TRUNCATE TABLE agent_proxies;

SET FOREIGN_KEY_CHECKS = 1;

-- 余额、冻结直接写回种子值。种子本来就没有对应流水（见 V2 的注释：
-- "种子直接写入 total_balance，因此初始余额没有对应流水"），这里保持同一口径，不补流水。
UPDATE wallets SET total_balance = 1000, frozen_amount = 0;

INSERT INTO auctions (id, title, description, status, start_price, min_increment, duration_seconds,
                      current_price, leader_id, ends_at, extension_count, seq)
VALUES ('auc_demo_0001',
        '演示拍品 · 复古机械键盘',
        '用于五分钟快速验证的演示拍品。管理员点击开始后才进入倒计时，起拍价与最小加价按原文规则设置。',
        'DRAFT', 100, 10, 180, 100, NULL, NULL, 0, 0);

-- 回显恢复结果，便于一眼确认（而不是"跑完了但不知道成没成"）。
SELECT 'wallet' AS kind, user_id AS name, total_balance AS total, frozen_amount AS frozen
  FROM wallets ORDER BY user_id;
SELECT 'auction' AS kind, id AS name, status AS state FROM auctions ORDER BY id;
