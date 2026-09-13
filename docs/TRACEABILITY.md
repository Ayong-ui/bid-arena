# 验收追溯表

> 把 [`全栈评测-拍卖间-原文.md`](../全栈评测-拍卖间-原文.md) 的每条要求编号，并映射到**实现位置**与**验证证据**。
> 这是评审追问"这条怎么证明"时的唯一索引。提交信息中的验收编号（如 `(A5)`）引用本表。
> 状态：⬜ 未开始　🟨 进行中　✅ 已验证

## 不变式映射

不变式定义见 [`DESIGN.md` §1.3](../DESIGN.md#13-四条不变式可执行断言)。并发压测后**直接查库校验不变式**，是“并发正确”最硬的证据；只看接口返回不算。

| 不变式 | 覆盖的验收项 | 校验手段（压测后 SQL） | 实现类 | 状态 |
|---|---|---|---|---|
| INV-1 资金非负且守恒 | A2, A5, A7, B2, B3 | 可用额非负；钱包冻结 = 各按场冻结之和；流水净额可解释冻结额；本场冻结总额 = 当前最高价 | `wallet.adapter.WalletRepository` | ✅ |
| INV-2 领先者唯一 | A4, A5, A6 | 本场冻结中非领先者为 0；出价链 `server_seq` 严格递增、每步至少一个最小加价；末条出价与拍卖行领先者一致 | `auction.application.BidService` | ✅ |
| INV-3 请求幂等 | A4, B6 | 同 `requestId` 重放后 `bids` 计数 = 1 且 `ledger_entries` 计数 = 1；重复提交返回首次结果 | `bid_requests` 主键 + `bids.uk_bid_request` | ✅ |
| INV-4 成交唯一 | A7, B7 | 重复 / 并发触发结算后 `settlements` 计数 ≤ 1；余额与流水一致 | `auction.application.SettlementService` + `settlements` 主键 | ✅ |

## 已登记的验证证据

上面 INV-1~4 的“校验手段”已实现为可执行 SQL，位于 `src/test/java/com/bidarena/support/Invariants.java`，
由真实 MySQL 集成测试调用（`BidConcurrencyTest` 5 个、`BidServiceTest` 11 个、
`SettlementConcurrencyTest` 5 个、`SettlementServiceTest` 11 个）。

P2 追加 31 个用例，全量共 **63 个**，运行方式见 [`README.md` 一键验证](../README.md)：

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| `HttpApiIntegrationTest` | 19 | 封套与错误码全路径（200/201/400/401/403/404/405/409）、鉴权与默认拒绝、RBAC、CORS 预检、出价全链路（加入 → 出价 → 幂等重放 → 钱包冻结 → 流水 → 快照 → 结算 → 结果）、取消释放、分页与过滤边界 |
| `IdentityServiceTest` | 10 | 登录签发/校验令牌、角色与过期、篡改/错密钥/空令牌拒绝、三种失败返回同一响应、弱密钥快速失败、`toString` 不泄露哈希 |
| `SeededDemoCredentialsTest` | 2 | 从迁移脚本里按行解析种子账号，用 BCrypt 实测三个演示口令可登录、错口令不可登录，且明文口令不出现在仓库文件中 |

HTTP 集成测试**自己启动一个完整的服务实例**（随机空闲端口、独立于 8080），因此它同时验证了“组合根接线是否可用”，而不只是控制器逻辑。

**测试有效性经过变异测试反向确认**，不以“全绿”为证据：

| 变异 | 预期被谁抓住 | 实测结果 |
|---|---|---|
| 拿掉拍卖行行锁（`lockAuction` 的 `FOR UPDATE`） | 并发用例 | 20 次同额出价中 14 次既非成功也非规则拒绝而是内部错误；两用例失败（断言 19 实际 6、断言 20 实际 5） |
| 拿掉幂等重放短路 | 幂等用例 | 出价记录数与成功幂等记录数不再相等；两用例失败 |
| 拿掉结算事务内的幂等短路 | 唯一结算 | 4 个用例失败（并发 3 + 功能 1） |
| 赢家当普通出价者处理（只释放不扣款） | 赢家必须被扣款 | 11 个用例失败 |
| `settleWinner` 结算时钱包冻结不减 | 两层冻结一致 | 11 个用例失败（含 4 个内部错误） |
| 一致性检查从 `!=` 放宽成 `>`（少扣也放行） | 少扣必须被拒绝 | 1 个用例失败，正是 `oneFailingAuctionDoesNotBlockTheRestOfTheBatch` |

还原后复跑全绿。**绿而不会红，等于没测**——此规则已写入 [`CONTRIBUTING.md` §4 完成定义](../CONTRIBUTING.md)。

### 尚未验证的部分

以下内容**当前没有任何测试**，属于已知缺口而非已完成：
WebSocket 事件与 `seq` 缺口（C3〜C5）、Agent 凭据（D1）、前端与模拟脚本（E1/E4/F1）、ArchUnit 包边界规则（D-6 的未验证项）、录屏与现场核验（H 组）。

## A. 拍卖与资金规则（原文 第 2 页）

| 编号 | 原文出处 | 要求摘要 | 实现位置 | 验证证据 | 状态 |
|---|---|---|---|---|---|
| A1 | 规则 1 数值规则 | 常量与起拍/加价/时长/初始余额（数值以原文为准） | 迁移种子 + 拍卖表字段 | 集成测试 | 🟨 |
| A2 | 规则 2 余额口径 | 可用余额口径；按“本场新增冻结”校验 | `wallet.adapter.WalletRepository`、`auction.application.BidService` | `BidServiceTest.rebidBySameUserFreezesOnlyTheDelta`、`insufficientAvailableBalanceIsRejectedOnDeltaBasis` | ✅ |
| A3 | 规则 3 截止边界 | 服务端接收时间严格早于截止 | `auction.application.BidService` | `BidServiceTest.bidAfterDeadlineIsRejected` | ✅ |
| A4 | 规则 4 幂等与并发 | 同 `requestId` 只生效一次；并发出价唯一赢家 | 唯一约束 + 行锁 + 死锁重试 | `BidConcurrencyTest.sameAmountOnlyOneBecomesLeader`、`sameRequestIdIsAppliedOnce`、`repeatedConcurrentRounds` | ✅ |
| A5 | 规则 5 冻结余额 | 释放旧领先者、同用户只加差额、失败不改资金、非负、流水可解释、无半完成 | `BidService` + `WalletRepository` + `ledger_entries` | `previousLeaderIsReleasedOnTransfer`、`bidBelowMinimumIsRejected`，均由 `Invariants` 逐条校验 | ✅ |
| A6 | 规则 6 最后五秒延时 | 基准为最新截止、最多三次、超限仍成功、与出价同一边界 | `auction.application.BidService` | `bidWithinLastFiveSecondsExtendsDeadlineAtMostThreeTimes`、`bidOutsideWindowDoesNotExtend` | ✅ |
| A7 | 规则 7 唯一结算 | 赢家扣款、他人释放、无人出价无扣款、自动结算、重启继续、重复触发不重复 | `auction.application.SettlementService` + `SettlementScheduler` | `SettlementServiceTest` 11 个（含 `expiredAuctionDeductsWinnerAndReleasesOthers`、`auctionWithoutAnyBidEndsWithNoWinnerAndNoDeduction`、`schedulerResumesExpiredButUnsettledAuctionsAfterRestart`）、`SettlementConcurrencyTest` 5 个 | ✅ |
| A8 | 规则 8 通知边界 | 广播失败不回滚、快照可重同步 | 事件发布层（未实现） | 集成测试 + 断线测试 | ⬜ |

## B. 数据模型（原文 第 2~3 页）

| 编号 | 原文出处 | 要求摘要 | 实现位置 | 验证证据 | 状态 |
|---|---|---|---|---|---|
| B1 | 数据模型 用户 | 身份、角色、密码哈希、状态 | `db/migration` V2 + `identity/domain/User` | 迁移执行；`POST /auth/login` 实测可登录 + `SeededDemoCredentialsTest` 用 BCrypt 校验种子哈希；三种失败同响应见 `IdentityServiceTest` | ✅ |
| B2 | 数据模型 钱包 | 总余额、冻结金额、并发所需字段 | `db/migration` V2 + `ck_wallets_available_nonneg` | `Invariants.walletsAvailableNonNegative`、`frozenMatchesTwoLevels`；约束反向验证 | ✅ |
| B3 | 数据模型 资金流水 | 用户、类型、金额、关联拍卖、关联请求、时间 | `db/migration` V2 + `ledger_entries` | `Invariants.ledgerReconcilesWithFrozen`（流水净额可解释冻结额） | ✅ |
| B4 | 数据模型 拍卖 | 状态、起拍价、当前价、领先者、截止、延长次数 | `db/migration` V1 + V2 | `BidServiceTest`（截止、延时、状态流转列均被读写） | ✅ |
| B5 | 数据模型 参与者 | 拍卖用户关系、加入时间、Agent/真人标识 | `db/migration` V1 + V3（按场冻结） | `Invariants.auctionFrozenEqualsPrice` | ✅ |
| B6 | 数据模型 出价 | 拍卖、用户、金额、`requestId`、服务端序号、时间 | `db/migration` + `bids.uk_bid_request` | `Invariants.oneBidPerRequest`、`bidChainStrictlyIncreasing` | ✅ |
| B7 | 数据模型 成交结果 | 赢家、成交价、原因；每场最多一条 | `db/migration` + `settlements` 唯一键 + `auction.adapter.SettlementRepository` | `Invariants.settlementIsConsistent`、`oneSettlementPerAuction`；`SettlementConcurrencyTest.twoSchedulersRunningAtTheSameTimeSettleEachAuctionOnce` | ✅ |
| B8 | 数据模型 Agent Token | 摘要、所属用户、拍卖范围、权限、过期、吊销 | `db/migration` + `agent_tokens` | 表已就位，Agent 认证未实现 | 🟨 |

## C. 接口与事件（原文 第 3 页）

| 编号 | 原文出处 | 要求摘要 | 实现位置 | 验证证据 | 状态 |
|---|---|---|---|---|---|
| C1 | 接口清单 | 覆盖原文列出的 HTTP 路径（含 `/bids` 查询） | `identity/adapter/HttpAuthController`、`auction/adapter/HttpAuctionController`、`auction/adapter/HttpAdminController`、`wallet/adapter/HttpWalletController`、`HealthController`（共 14 个端点） | `HttpApiIntegrationTest` 逐个端点调用（含未实现路径的 404 行为）；契约 `docs/openapi.yaml` | ✅ |
| C2 | 出价请求 | 至少含 `requestId` 与 `amount` | `HttpAuctionController.BidRequest` + `openapi.yaml` | `HttpApiIntegrationTest.bidFlowWithIdempotentReplay`（首次 200 `OK` / 重放 200 `IDEMPOTENCY_REPLAY` 且 `seq` 不变）、`bidGuards`（未加入 409 `NOT_JOINED`、加价不足 409 `BID_TOO_LOW`、金额 0 → 400）、`idempotencyKeyResolution`（缺 `requestId` 400；header 与 body 不一致 400） | ✅ |
| C3 | 确认事件字段 | 含 `auctionId` / 单调 `seq` / `serverTime` / `type` | 事件 DTO | WS 测试 | ⬜ |
| C4 | 推荐事件类型 | 快照、加入、接受、拒绝（仅本人）、延时、结束、连接状态 | 事件发布层 | WS 测试 | ⬜ |
| C5 | seq 缺口处理 | 发现缺口时重新获取权威快照，不猜测 | 前端 store | 前端 Store 测试 + 集成 | ⬜ |

## D. 竞拍 Agent Token（原文 第 3 页）

| 编号 | 原文出处 | 要求摘要 | 实现位置 | 验证证据 | 状态 |
|---|---|---|---|---|---|
| D1 | Agent Token | 字段齐全、只存摘要、明文仅一次、最小权限、独立凭据 | `db/migration` + `agent_tokens` 表已就位；**认证与签发未实现** | ⏳ 表结构已就位；`/auth/ws-tickets` 与 Agent Token 签发属 P3/P5 | 🟨 |

## E. 模拟脚本与自动化测试（原文 第 3~4 页）

| 编号 | 原文出处 | 要求摘要 | 实现位置 | 验证证据 | 状态 |
|---|---|---|---|---|---|
| E1 | 模拟脚本 | 20 用户、并发同/邻价、`requestId` 重试、拒绝场景、最后五秒狙击、断线快照、结束核对 | `scripts/` 模拟脚本 | 脚本输出断言与摘要 | ⬜ |
| E2 | 自动化测试 | 覆盖原文列出的全部测试点 | `src/test` | `mvn test` 报告 | ⬜ |
| E3 | 真实环境 | 并发与结算测试使用真实 MySQL/容器 | 独立测试库 `bid_arena_test`（可用环境变量指定） | 已实测：63 个用例全部跑在真实 MySQL 8.4 上（含 HTTP 集成测试自行启动服务实例） | ✅ |
| E4 | 前端测试 | 至少一个 Vue Store 或核心组件测试 | `frontend` 测试 | 测试报告 | ⬜ |

## F. 快速启动与初始数据（原文 第 4 页）

| 编号 | 原文出处 | 要求摘要 | 实现位置 | 验证证据 | 状态 |
|---|---|---|---|---|---|
| F1 | 根目录交付 | `.env.example`、Compose、迁移、种子、一键测试、模拟脚本 | 仓库根目录 | 全新环境实测 | ⬜ |
| F2 | 演示账号 | 原文建议的三类账号 | `db/migration/V2` 种子（ADMIN + 两个 BIDDER） | `SeededDemoCredentialsTest`（三个口令登录成功、错口令失败）；`HttpApiIntegrationTest` 用同一批账号走完整 HTTP 登录 | ✅ |
| F3 | README 路径 | 可复制的完整演示路径 | `README.md` | 照做一遍 | ⬜ |
| F4 | 演示拍品 | 至少一件可立即开始，服务启动不自动倒计时 | 种子数据 | 实测 | ⬜ |

## G. 交付文档与历史（原文 第 4 页）

| 编号 | 原文出处 | 要求摘要 | 实现位置 | 验证证据 | 状态 |
|---|---|---|---|---|---|
| G1 | `AI_USAGE.md` | 分工、本人决定、未采用方案、真实错误、无法独立解释的代码 | `AI_USAGE.md` | 文档评审 | ⬜ |
| G2 | `DESIGN.md` | 边界、事务、结算、并发、恢复、丢消息策略 | `DESIGN.md` | 文档评审 | ⬜ |
| G3 | `DECISIONS.md` | ≥3 项决策含代价与验证 | `DECISIONS.md` | 文档评审 | ⬜ |
| G4 | `DEBUG_LOG.md` | ≥2 个真实问题，禁止编造 | `DEBUG_LOG.md` | 与提交/日志对应 | ⬜ |
| G5 | `AGENT_TOOL_SPEC.md` | 评审如何用 Token 操作 Agent | `AGENT_TOOL_SPEC.md` | 文档评审 | ⬜ |
| G6 | Git 历史 | 有意义、非机械拆分、说明行为变化 | 提交历史 | `git log` | ⬜ |
| G7 | 录屏 | 3~5 分钟覆盖关键流程 | 交付物 | 人工核验 | ⬜ |

## H. 现场核验（原文 第 5 页）

| 编号 | 原文出处 | 要求摘要 | 准备方式 | 状态 |
|---|---|---|---|---|
| H1 | 第一段 | 解释并发出价事务、旧领先者释放、重复请求/结算/重启，并按日志定位问题 | `DEBUG_LOG.md` 演练 + 事务脚本 | ⬜ |
| H2 | 第二段 | 临时变更（VIP 加价 / 取消释放 / 代理最高价 / 可配置延时 + 迁移与测试） | 保证扩展点清晰、迁移可加 | ⬜ |

## I. 评分维度自检（原文 第 5 页，满分 10）

| 编号 | 维度 | 分值 | 关键证据 | 状态 |
|---|---|---:|---|---|
| I1 | 架构与职责边界 | 1.5 | `DESIGN.md` + 分层结构 | ⬜ |
| I2 | 拍卖业务与并发一致性 | 1.8 | A2–A8 的测试 | ⬜ |
| I3 | Solon 后端能力 | 1.2 | HTTP 与鉴权已可运行（过滤器链、统一异常、注入、分页）；WS 待 P3 | 🟨 |
| I4 | Vue 3 / TypeScript | 1.0 | 类型、状态、重连恢复 | ⬜ |
| I5 | MySQL 实战能力 | 1.2 | 模型、事务、约束、锁、流水 | ⬜ |
| I6 | 代码质量 | 1.2 | 可读性、职责、安全、可维护 | ⬜ |
| I7 | 测试与可验证性 | 0.8 | E2–E4 | ⬜ |
| I8 | Git 与交付规范 | 0.3 | G6 + F1 + F3 | ⬜ |
| I9 | Agent 使用能力 | 1.0 | `AI_USAGE.md` + 现场核验 | ⬜ |
