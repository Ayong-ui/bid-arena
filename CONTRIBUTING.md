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

