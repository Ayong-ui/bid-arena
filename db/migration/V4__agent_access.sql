-- P5：竞拍 Agent 的受限凭证与拍卖范围。
--
-- V1 建的 agent_tokens 只有一列 auction_id（一个 token 只能绑一场拍卖），
-- 与契约 CreateAgentTokenRequest.auctionIds（数组，一个 token 可覆盖多场）对不上，
-- 也缺 token_id（对外可见的标识）与 name（运营用的备注）。
-- 该表从建好起就没有任何代码写过它，因此这里直接重建，而不是逐列 ALTER：
-- 重建后的表结构就是 P5 的最终形态，读迁移的人不必在脑子里做加法。

DROP TABLE IF EXISTS agent_token_auctions;
DROP TABLE IF EXISTS agent_tokens;

CREATE TABLE agent_tokens (
  id                    BIGINT       AUTO_INCREMENT PRIMARY KEY,
  -- 对外可见的标识，出现在 URL（吊销）与响应里。与 token_hash 分开是刻意的：
  -- 运营需要"能指着某个 token 说吊销它"，而这不该要求它持有明文。
  token_id              VARCHAR(64)  NOT NULL,
  name                  VARCHAR(80)  NOT NULL,
  -- SHA-256 十六进制摘要。明文只在签发响应里出现一次，数据库、日志、Git 里都没有它。
  -- 用摘要而不是可逆加密：验签时只需比对摘要，泄漏了库也拿不到能用的 Token。
  token_hash            CHAR(64)     NOT NULL,
  agent_user_id         VARCHAR(64)  NOT NULL,
  -- 逗号分隔的权限集合，取值与 openapi 的 scopes 枚举一致（auction:read, auction:bid）。
  -- 权限是固定的两三个枚举值，用子表会让"加一个权限"变成一次迁移，收益为零。
  scopes                VARCHAR(128) NOT NULL,
  rate_limit_per_minute INT          NOT NULL DEFAULT 60,
  expires_at            TIMESTAMP(6) NOT NULL,
  revoked_at            TIMESTAMP(6) NULL,
  created_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_agent_token_id (token_id),
  UNIQUE KEY uk_agent_token_hash (token_hash),
  KEY idx_agent_token_user (agent_user_id),
  CONSTRAINT fk_agent_token_user FOREIGN KEY (agent_user_id) REFERENCES users (id),
  -- 频率上限必须是正数：0 或负数的 token 无法使用，那一定是调用方写错了参数，
  -- 与其让它安静地"永远 429"，不如在写入时就拒绝。
  CONSTRAINT ck_agent_token_rate_positive CHECK (rate_limit_per_minute >= 1)
);

-- 拍卖范围。一个 token 可以覆盖多场，所以是集合，单独建表。
--
-- 为什么不用 CSV 列：范围需要参与"这个 auctionId 在不在授权内"的判断，
-- CSV 只能靠 LIKE 匹配，而 LIKE 会把 a1 命中 a10——那是一个会真实发生的越权。
-- 子表用主键做等值判断，顺带能用外键保证被授权的拍卖确实存在。
CREATE TABLE agent_token_auctions (
  token_id   VARCHAR(64) NOT NULL,
  auction_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (token_id, auction_id),
  CONSTRAINT fk_agent_scope_token FOREIGN KEY (token_id) REFERENCES agent_tokens (token_id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_scope_auction FOREIGN KEY (auction_id) REFERENCES auctions (id)
);
