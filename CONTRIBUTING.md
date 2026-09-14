# 协作与提交规范

> 本文件的流程依据是 [`docs/DOCS.md`](docs/DOCS.md)（文档地图与权威边界）。
> 业务规则只以 [`全栈评测-拍卖间-原文.md`](全栈评测-拍卖间-原文.md) 为准，本文件不复述规则。

## 1. 基本原则

1. **可追溯**：每个提交说明"行为变化"，能对应到验收项。
2. **可复盘**：提交粒度小且独立，任何一次提交都能单独编译、单独解释。
3. **可验证**：没有测试或证据的"完成"不算完成。
4. **不掩盖**：未完成的部分如实写进 `README.md` 和 [`docs/STATUS.md`](docs/STATUS.md)。

## 2. 分支与提交策略

- 主干为 `main`，必须始终保持可编译、可启动。
- 单人开发允许直接提交到 `main`，但**每个提交必须是一个原子的纵切面**。
- 阶段性成果打 tag（例如 `m1-persistence`、`m2-http-api`）。
- **禁止**把多天工作压成一次提交，再机械拆分成多次无意义的提交。

## 3. 提交信息规范

采用 Conventional Commits：

```text
<type>(<scope>): <说明行为变化> (<验收编号>)
```

| 字段 | 取值 |
|---|---|
| `type` | `feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `build` / `perf` |
| `scope` | `auth` / `auction` / `wallet` / `bid` / `settle` / `ws` / `agent` / `web` / `db` / `docs` / `infra` / `test` |
| 验收编号 | 取自 [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md)，如 `A5`、`C1` |

要求：

- 说明**行为变化**，不写 `update`、`fix bug`、`wip`、`调整一下`。
- 一次提交尽量只对应**一个验收项或一个纵切面**。

正例：

```text
feat(wallet): release previous leader freeze when new bid becomes leader (A5)
fix(bid): compare deadline with server received time strictly (A3)
feat(db): add users wallets ledger migration (B1-B3)
docs: record real deadlock and fix in DEBUG_LOG (A4)
```

反例：

```text
update code
fix bug
完成一部分功能
```

## 4. 完成定义（DoD）

一个功能只有同时满足以下全部条件才算"完成"：

- [ ] 代码可编译，服务可启动
- [ ] 相关测试已新增或更新，并且通过
- [ ] **新增/修改的关键测试已做变异验证**：故意拆掉一项它声称保护的保障，确认它会红（见 `DEBUG_LOG.md` DBG-5）。凡"断言某种东西不存在"的测试（如架构规则）尤其必须做——否则你分不清它是守卫还是一行永远为真的注释（见 `DEBUG_LOG.md` DBG-19）
- [ ] 涉及接口改动 → `docs/openapi.yaml` 已同步
- [ ] 按 [`docs/DOCS.md` §5 改动联动表](docs/DOCS.md) 同步了所有受影响文档
- [ ] 修复了真实 bug → 已记入 `DEBUG_LOG.md`（现象、日志、定位、修复、验证）
- [ ] 使用 AI 生成或更换方案 → 已记入 `AI_USAGE.md` / `DECISIONS.md`
- [ ] `README.md` 的"未完成边界"已更新
- [ ] [`docs/STATUS.md`](docs/STATUS.md) 已更新
- [ ] 提交可独立编译、独立验证
- [ ] CI 绿（`.github/workflows/ci.yml` 的 5 个 job；本机无法复现的项——例如镜像构建——以 CI 为准）

> 关键：**接口先改 `openapi.yaml`，再改代码。** 契约是唯一裁决依据，不允许"代码先行、文档后补"。

## 5. 提交前自检

```powershell
git status --short          # 确认无误提交文件
mvn -q test-compile         # 后端可编译
# 后端全量（需指定 BID_ARENA_TEST_DB_*）：mvn clean verify
# 前端：cd frontend; npm test; npm run typecheck
python tools/arch_mutation_check.py   # 架构规则的反向确认（期望 9/9 KILLED）
# 前端变异：cd frontend; python tools/mutation_check.py（期望 16/16 KILLED）
```

推上去之后看一眼 [Actions](https://github.com/Ayong-ui/bid-arena/actions/workflows/ci.yml)：`backend`/`frontend`/`e2e`/`config`/`images` 五个 job 对应上面这些命令，全绿才箥称“已验证”（详见 README 的「持续集成」）。

逐项确认：

- [ ] 没有 `.env`、Token、密码、`完整 Authorization`
- [ ] 没有 `frontend/node_modules/`、`frontend/dist/`、`target/`、`*.pdf`
- [ ] `docs/openapi.yaml` 与代码字段一致
- [ ] `docs/STATUS.md` 与本次改动一致

## 6. 禁止提交的内容

`.env` 及任何真实密钥、Agent Token 明文、数据库密码、`node_modules/`、`dist/`、`target/`、构建产物、PDF 原件、本地运行数据。

## 7. 测试要求

- 单测覆盖规则层。
- **并发出价、幂等、唯一结算、重启恢复**必须使用真实 MySQL 或等价的容器化环境，**不允许全部用内存 Mock 代替**。
- 测试即规格：验收项的最终证据是 [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md) 中对应的测试。

## 8. GitHub 仓库管理

### 8.1 仓库设置

- 仓库公开（原文要求可提交仓库地址与完整历史）。
- `main` 为默认分支，始终保持可编译、可启动；可选开启分支保护，禁止直接强推覆盖历史。
- 不提交的内容由 `.gitignore` 保证，提交前用 `git status --short` 复核。

### 8.2 分支与标签

| 名称 | 用途 |
|---|---|
| `main` | 主干，原子小步提交 |
| `feat/<scope>-<brief>` | 单功能短分支（可选，用于较大改动） |
| `tag m1` … `m6` | 对应 `docs/STATUS.md` 的阶段里程碑 |
| `tag m0` | P0：文档、契约与环境基线 |

- 里程碑完成时打 tag 并写一句说明，便于评审按阶段查看演进。
- 已推送的历史不重写；确需修正用新提交说明，而不是 `push --force`。

### 8.3 Secrets 与敏感信息

- 密码、`JWT_SECRET`、Agent Token 明文只存在本地 `.env` 或 GitHub Secrets，**永不入库、不入日志、不入录屏**。
- 模拟脚本通过环境变量 `AUCTION_AGENT_TOKEN` 读取 Token，**不写入命令历史或仓库**。
- 若发现误提交：立即吊销对应凭据，并在 `DEBUG_LOG.md` 记录真实事件与处理。

### 8.4 提交历史的可读性

- 提交信息使用 §3 规范，评审应能仅凭 `git log --oneline` 看懂开发顺序。
- 允许合并相邻的 WIP 提交，但**不允许**把最终一次性上传的代码机械拆成多次提交。

## 9. 交付清单

### 9.1 要交什么

| 交付物 | 具体要求 | 位置 | 状态 |
|---|---|---|---|
| 仓库地址 | GitHub / Gitee，含完整提交历史 | <https://github.com/Ayong-ui/bid-arena> | ✅ |
| 演示录屏 | 3~5 分钟：真人加入、脚本并发、Agent 出价、最后五秒延时、结算与资金结果 | 随提交邮件 | ⬜ |
| 线上地址 | 加分项，非必须 | — | ⬜ |
| `.env.example` | 不包含真实密钥 | 仓库根目录 | ✅ |
| Compose | 一键启动 MySQL（推荐含前后端） | `docker-compose.yml` | 🟨 |
| 迁移 + 种子 | 自动执行，不手工建表/插账号 | `db/migration/` | ✅ 空库执行实测 |
| 一键测试命令 | 可复制执行 | `README.md`（后端 `mvn clean verify`；前端 `npm test` / `npm run typecheck` / `npm run test:live`） | ✅ |
| 模拟脚本 | 支持多机器人参数与随机种子 | `scripts/` | ⬜ |
| 必交文档 | `DESIGN.md` / `DECISIONS.md` / `AI_USAGE.md` / `DEBUG_LOG.md` / `AGENT_TOOL_SPEC.md` | 仓库根目录 | ✅（五份均已完成：`AI_USAGE.md` 已填写，附三项作者核对清单） |
| OpenAPI | 覆盖原文要求的全部能力 | `docs/openapi.yaml` | 🟨 |
| README | 快速启动完整路径 + 演示账号 + 未完成边界 | `README.md` | 🟨 |

提交方式：将**仓库地址、姓名、联系方式**发送至原文指定邮箱。

#### 9.1.1 录屏分镜（3~5 分钟，供本人录制）

原文要求覆盖五件事：**真人加入、脚本用户并发、Agent 出价、最后五秒延时、结算与资金结果**。下表把每一拍落到具体的画面与命令上，照着点即可；总时长约 4 分 30 秒。

**开录前（不录进视频）**

1. `cp .env.example .env` 并填好 `JWT_SECRET`，然后 `docker compose up -d --build`，浏览器开 `http://localhost:8088`（D-37 单 origin：只暴露一个端口，`/api` 与 `/ws` 由 Nginx 反代）。
2. 复位演示数据（脚本会**真的在库里花钱**）：`mysql -h127.0.0.1 -P3307 -ubid_arena -p bid_arena < db/reset_demo_data.sql`。
3. **关掉所有可能露出真实口令的窗口**：开着 `.env` 的编辑器、带 `-p<口令>` 的终端标签、`GITHUB` secrets 页——`JWT_SECRET`／数据库口令／Agent Token 明文都不得入镜（§8.3）。终端先 `chcp 65001` 并把字号调大。
4. 演示账号：`admin@example.com / Admin123456!`、`bidder_a@example.com`／`bidder_b@example.com`（密码均为 `Test123456!`）。

| 时间 | 画面 | 操作 | 要体现的点 |
|---|---|---|---|
| 0:00–0:25 | 仓库 + 终端 | `git log --oneline` 滚两屏；`docker compose ps` 显示 `mysql / migrate / backend / frontend` | 有分阶段的真实提交历史；`migrate` 已退出（退出码 0） |
| 0:25–1:10 | 浏览器 | 用 `bidder_a` 登录 → 拍卖大厅 → 进一场进行中的拍卖 → **加入本场** → 出价（可用「+ 最小加价」） | 真人加入；出价后余额被**冻结**、领先者与 `seq` 实时前进（数字全来自服务端） |
| 1:10–2:10 | 第二个终端 | `python tools/auction_sim.py --keep`（八阶段全跑；**阶段 2~4 的 20 条并发/幂等重试是重点**） | 并发同/邻价只有合法的那几笔成交、`requestId` 重试不重复冻结；等待段可在剪辑时倍速或剪掉 |
| 2:10–2:55 | 浏览器「我的 AI 代理」 | 选一场进行中/未开拍的场次 → 填预算 → **创建 AI 代理**，旁边终端可另开 `python tools/agent_sim.py --duration 60` | 普通用户不写代码就能让服务端 AI 出价（D-36）；Agent 走独立端口/独立 Token（D-34/D-9） |
| 2:55–3:40 | 浏览器详情页 | 盯到**剩余 ≤ 5 秒**时手动出价一次 | 最后五秒出价触发 **+10 秒延时**，`已延时 N 次` 增加、倒计时重置（尾盘狙击） |
| 3:40–4:20 | 浏览器 | 等结算完成后：详情页「结算」区看中标者与 `AI 代理/真人` 徽章 →「我的资金」看**冻结释放 + 成交扣款**流水 → 管理员看运营台「场次流水」 | 结算与资金结果可对账，成交主体（AI/真人）可追溯 |
| 4:20–4:30 | 终端 | `python tools/stress_test.py --mode game-window -c 100` 滚出一片 `403 / HUMAN_ONLY_PERIOD` | 博弈时间真的把 Agent 挡在外面，真人不受影响（D-32） |

**备选镜头**（时间不够时二选一）：`python tools/agent_sim.py --agent-only --auction-id <场次ID>` 只用 `AUCTION_AGENT_TOKEN` 参与已有拍卖（对应 `AGENT_TOOL_SPEC.md` 的“把 Token 交给 Coding Agent”路径）；或 `python tools/stress_test.py --mode throughput -c 50 --seconds 10` 展示吞吐。

**录完自检**：① 五个必考点都有镜头；② 画面里没有任何密钥/Token 明文；③ 文件 3~5 分钟；④ 随提交邮件附上（视频**不能**替代代码、测试、Git 与文档核验）。

### 9.2 要持续更新什么

| 触发 | 必须更新的内容 |
|---|---|
| 任意提交 | `docs/STATUS.md` |
| 接口变化 | `docs/openapi.yaml` → 前端生成类型 |
| 事件变化 | `docs/REALTIME_AND_COMMAND_FLOW.md` |
| 表结构变化 | 新增 `db/migration/V*` |
| 完成/新增验收项 | `docs/TRACEABILITY.md` |
| 修真实 bug | `DEBUG_LOG.md` |
| 用 AI 或换方案 | `AI_USAGE.md` / `DECISIONS.md` |
| 完成度变化 | `README.md` 未完成边界 |
| 阶段完成 | 打 tag + `docs/STATUS.md` 阶段行 |

> 完整联动关系见 [`docs/DOCS.md` §5](docs/DOCS.md)。

### 9.3 提交前最终自检

- [ ] `docs/STATUS.md`、`docs/TRACEABILITY.md` 与实际状态一致
- [ ] 必交文档没有过期内容
- [ ] README 的未完成边界属实
- [ ] 无密钥、Token、密码、构建产物
- [ ] `git log --oneline` 能读懂行为变化

### 9.4 现场核验演练（H 组：讲清并发事务 + 做临时变更）

现场核验两问：**第一段**要讲清“并发出价事务 / 旧领先者释放 / 重复请求 / 结算 / 重启”，并能顺着日志把问题定位出来；**第二段**要接下四个临时变更（VIP 加价、取消释放、代理最高价、可配置延时），证明**扩展点清晰、迁移可加**。

下面是照着自己点就能走完的稿子（H1 约 10 分钟，H2 约 15 分钟）。**不要在 `main` 上做临时变更**：改完就 `git checkout .` 还原——演练不是提交。

#### 9.4.0 开演前 60 秒

| 动作 | 命令 | 期望 |
|---|---|---|
| 起整栈 | `docker compose up -d --build`，浏览器开 `http://localhost:8088` | `docker compose ps` 里 `migrate` 已退出（0）；`mysql/backend/frontend` 为 Up |
| 复位演示数据 | `mysql -h127.0.0.1 -P3307 -ubid_arena -p bid_arena < db/reset_demo_data.sql` | 演示账号余额回到种子值（脚本真花钱，见 DBG-31） |
| 开两个终端 | ① `docker compose logs -f backend`；② 留着敲 SQL（`mysql -h127.0.0.1 -P3307 -ubid_arena -p bid_arena`） | ① 能实时看到 `出价成功`/`结算完成`；② 能验证不变量 |
| 记住三句口述 | —— | 权威时钟是数据库（D-5）；唯一赢家靠“锁拍卖行 + 条件更新”（D-4/D-12）；事件在提交后发（D-20） |

> 口令不得入镜、不得粘进终端历史（§8.3）：用 `-p` 时不加参数，让客户端交互式要口令。

#### 9.4.1 H1：一个事务、六个步骤（讲解骨架）

锚点都在 [`BidService.java`](src/main/java/com/bidarena/auction/application/BidService.java) 的类注释里（“一个事务，六个步骤”），逐条指着讲即可：

| 步 | 代码锚点 | 要讲清的点 |
|---|---|---|
| 1 取数据库时间 | `doPlaceBid` 中 `Instant now = Db.now(conn);`（约 225 行） | 截止/延时判定不能用应用机器时钟——DBG-30 就是容器时钟慢 3 分钟那次 |
| 2 锁拍卖行 | `AuctionRepository.lockAuction`（约 81 行，`... WHERE a.id = ? FOR UPDATE`） | 同一场拍卖的所有出价在这里**串行化**，这就是“唯一赢家”的全部依据 |
| 3 判幂等 | `AuctionRepository.lockRequest`（约 184 行，按 `(auction_id, user_id, request_id)` 加锁）+ `insertRequestPending` 占位 + `markRequestDone` 收尾 | 重放**直接返回首次结果且不广播**（`publishAccepted` 里 `idempotent()` 早退）；失败也落终态（`upsertRejectedRequest`，约 217 行），所以重试拿到的是同一个错误码 |
| 4 校验与旧主释放 | `long minimum = currentPrice + minIncrement`（约 277 行）；`releasePreviousLeader`（约 305 行调用，约 342 行实现） | 旧主在本场的冻结额必须**等于**当前最高价，不等就让本次出价失败（宁可报错也不带着错误前提算差额）；新领先者只冻结差额 `delta = amount - myFrozen` |
| 5 锁资金 | `WalletRepository` 约 80/104/124 行，三处都带 `ORDER BY user_id FOR UPDATE` | 一次出价可能同时改两个用户，统一按 `user_id` 升序把死锁从“偶发”变成“不存在”（类注释有一节专讲） |
| 6 写入并收尾 | 延时判定约 318–322 行（`EXTENSION_WINDOW_SECONDS=5` / `EXTENSION_SECONDS=10` / `MAX_EXTENSIONS=3`，常量在 55–59 行）；`AuctionRepository.applyBid`（约 95 行，`... AND seq = ? AND status = 'RUNNING'`） | 一次提交 = 一个 `seq`；`BID_ACCEPTED` 已经带上新的 `endsAt`，`AUCTION_EXTENDED` 只是补充视图（两者同 `seq`） |

结算与重启，说这三点就够：

- **结算唯一**：`SettlementService.settleIfDue`（约 97 行）先用 `SettlementRepository` 的 `... WHERE auction_id = ? FOR UPDATE`（约 36 行）锁结算行，再用 `UPDATE auctions SET status = ? WHERE id = ? AND status = ?`（`AuctionRepository` 约 256 行）做条件更新——第二个并发调用者影响行数为 0，于是返回**已有结果**而不是再算一遍（D-12/D-13）。
- **到期扫描**：`findDueAuctionIds` 用 `status='RUNNING' AND ends_at <= now`（约 231 行）走 `idx_auctions_status_ends` 索引，不扫全表。
- **重启不丢待办**：“该结算”这件事在**数据库里**（`status='RUNNING'` + `ends_at`），不在内存里；进程重启后下一轮扫描自动补上。

#### 9.4.2 H1：照着日志定位（四个现场 drill）

**A. 重复请求 = 幂等（既不报错、也不重复扣钱）**

```bash
for i in 1 2; do curl -s -X POST localhost:8080/api/auctions/<场次ID>/bids \
  -H "Authorization: Bearer <Token>" -H 'Content-Type: application/json' \
  -d '{"amount":120,"requestId":"demo-1"}'; echo; done
```

第二次**不应**出现新的 `出价成功 ... seq=...` 日志（重放不推进 `seq`、也不广播）。数据库侧对账：

```sql
SELECT status, result_code, result_price, result_seq FROM bid_requests
 WHERE auction_id='<场次ID>' AND request_id='demo-1';
SELECT COUNT(*) FROM bids WHERE auction_id='<场次ID>' AND request_id='demo-1';  -- 恒为 1
SELECT user_id, total_balance, frozen_amount FROM wallets WHERE user_id='<用户ID>';  -- 冻结只发生一次
```

**B. 旧主释放对账（INV-1/INV-2）**

```sql
SELECT user_id, frozen_amount FROM auction_participants
 WHERE auction_id='<场次ID>' ORDER BY user_id;
```

口述不变量：任一时刻全场 `auction_participants.frozen_amount` 之和 = 当前最高价（结束后清零），且每个用户 `frozen_amount <= total_balance`。同样的 SQL 就在 [`Invariants.java`](src/test/java/com/bidarena/support/Invariants.java)，集成测试每笔出价后都会跑。

**C. 重启不丢待办（结算）**

留一场“已到期但未结算”的场次 → `docker compose restart backend` → `docker compose logs -f backend | grep 结算完成`：下一轮扫描把它结算掉。再做一次反面：`SETTLE_SCHEDULER_ENABLED=false` 起后端（D-40），启动日志会 WARN “本实例不负责结算”，并且**不再**结算——说明“谁负责”是显式配置、不是猜的。

**D. 广播失败不回滚（可选，5 秒）**

把 `WS_PORT` 指向一个没人监听的端口再出价：出价**照样成功**，日志里只有 `事件广播失败，已提交的事务不受影响 ...`（D-20，对应 A8）。

#### 9.4.3 H2：四个临时变更（每个改一处 + 一张迁移 + 一组测试）

> 每个变更都问自己三句：**改了几处？要不要迁移？测试怎么证明没改坏？** 下面的答案就是“扩展点清晰、迁移可加”的证据。

**① VIP 加价（最小加价按等级放大）**

- 改：`BidService.java` 约 277 行 `long minimum = auction.currentPrice() + auction.minIncrement();`。系数来源二选一——只读用 `Env.intOr("VIP_INCREMENT_PERCENT", 100)`（不动库），或落库用 `users.vip_level`。
- 迁移（选落库）：新增 `db/migration/V7__user_vip_level.sql`：`ALTER TABLE users ADD COLUMN vip_level INT NOT NULL DEFAULT 100 COMMENT '最小加价系数百分比；100 = 与旧行为一致';` + `CHECK (vip_level >= 100)`。**只加列、不回填**——默认值 100 就是旧行为，所以老数据零影响（与 `V5`/`V6` 加列同手法）。再在 `AuctionRepository` 读 `min_increment` 的那条 SELECT 旁边带出该列即可。
- 测试：`BidServiceTest` 加“等级 200 → 最小加价翻倍”“等级 100 → 与现状一致”两例；`HttpApiIntegrationTest` 里 `BID_TOO_LOW` 的 `minimum` 字段断言跟着改。
- 变异：把系数写死成 100，新用例必须变红（否则等于没测）。
- 预计：20 分钟。

**② 取消释放（改语义：取消时收 1% 手续费）**

- 改：`SettlementService.cancel`（约 111 行）复用的资金分支（`SettlementReason.CANCELLED`，见 104 行注释）。
- 迁移：**不需要**——这正是要讲的点：语义变更不一定等于迁移。但必须同步改 `Invariants.java` 里“取消后冻结清零”的断言与 `docs/STATUS.md` 的对账口径，否则测试会红（这是期望的：不变量变了，测试先叫）。
- 测试：`SettlementServiceTest` 加“取消后冻结清零且扣 1%、流水出现 FEE”；`HttpApiIntegrationTest` 的取消快照断言（`frozenAmount` 回到 0）跟着改。
- 验证：跑 `BidConcurrencyTest` + `SettlementConcurrencyTest`，证明并发语义没被顺手改坏。
- 口述：取消与到期结算**共用同一套资金逻辑**（D-13），所以只改一个分支就能同时覆盖两条路径。

**③ 代理最高价（预算上限语义调整）**

- 改：`AgentProxy.java` 约 42 行 `return amount <= budgetLimit;`（`canAfford`）与 `AgentProxyRepository` 约 58 行的跟价计算 `currentPrice + minIncrement`。
- 迁移：若要让上限随市场浮动，`V7` 给 `agent_proxies` 加 `max_follow_amount BIGINT NULL`（可空、不回填 = 旧行为）。
- 测试：`AgentProxyIntegrationTest`（19 例）加“触顶后不再出价，且 `budget_reached_at` 只写一次”；改完跑一遍调度器每轮上限的用例。
- 验证：真跑 `python tools/agent_sim.py --duration 30`，看代理出价次数与触顶时刻。
- 口述：代理**没有自己的资金逻辑**（`V6` 头注释第 3 条）——它调的是同一个 `BidService.placeBid`，所以 `actor_type=AGENT`、博弈时间拒绝、冻结与释放语义自动一致。

**④ 可配置延时（常量 → 每场可配）**

- 改：`BidService.java` 55–59 行的 `EXTENSION_WINDOW_SECONDS` / `EXTENSION_SECONDS` / `MAX_EXTENSIONS`，以及 318–322 行的判定。
- 迁移：`V7` 给 `auctions` 加 `extension_seconds INT NULL`、`max_extensions INT NULL`（可空 = 走常量默认，同样只加列不回填）。
- 契约：`finalGameWindowSeconds` 已经在快照与响应里下发（D-32/V5），照同样方式把 `extensionSeconds` 加进快照与 `docs/openapi.yaml`；前端只读服务端下发值（D-27），不自己算。
- 测试：`BidServiceTest` 的延时用例 + `WsIntegrationTest` 的 `AUCTION_EXTENDED` 断言。
- 口述：**契约早就留了口子**——前端从不自己算延时，所以这属于“加两列 + 改一处判定”，不需要动前端逻辑。
- 预计：25 分钟。

#### 9.4.4 收尾（演练完必须回到基线）

1. `git status` 必须干净；四个临时变更用 `git checkout .` 还原（演练不产生提交）。
2. 若演练中真挖出问题：走 §4/§5 正常流程（先补测试、记 `DEBUG_LOG.md`、单独提交），**不要**把临时变更混进去。
3. 复查基线仍是绿的：后端 `mvn clean verify`（**242/242**）、前端 `cd frontend && npm test`（**73**）。

> 现场核验的视频**不能**替代代码、测试、Git 与文档核验；它只是把上面这些证据用嘴讲一遍、用手点一遍。

