# Bid Arena（拍卖间）

仓库地址：<https://github.com/Ayong-ui/bid-arena>（公开，含完整提交历史；`main` 已开启分支保护）

[![CI](https://github.com/Ayong-ui/bid-arena/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Ayong-ui/bid-arena/actions/workflows/ci.yml)

这是一个公开管理的 Bid Arena 拍卖系统仓库。当前已完成：应用内 Flyway 迁移（V1~V6）、身份/钱包/资金流水数据模型、**并发安全的出价事务**、**唯一结算与到期自动结算**、**尾段“博弈时间”强制拒绝 Agent 出价**、**成交主体（AI / 真人）可追溯且仅对赢家与管理员可见**、**预告开拍与到点自动开拍**、**HTTP API + JWT 鉴权 + RBAC + 统一响应封套**、**WebSocket 实时事件与 `seq` 缺口恢复**、**可执行的架构守卫**（ArchUnit 九条分层/跨上下文/无环规则）、**前端接入真实 HTTP/WS**，以及两条 AI 路径：**面向普通用户的「托管 AI 代理」**（选进行中/未开拍的场次 + 设定预算上限，服务端到点自动进场并按最小加价跟价，D-36）与**面向开发者的竞拍 Agent API**（独立端口 `:8090`、独立 Token、范围/权限/过期/吊销/限流，用户可在“我的 AI 代理”页**自助签发自己名下的授权**，D-34）；另有**两份端到端模拟脚本**（Agent 侧 `tools/agent_sim.py`、用户侧全链路 `tools/auction_sim.py`）和**一份服务器压测脚本**（`tools/stress_test.py`，尾段博弈时间清场 + 持续吞吐）。全量 **232 个测试**（223 个真实 MySQL 集成/领域测试 + 9 条架构规则）。尚未完成：演示录屏与现场核验素材。

实现路线、当前进度与未完成边界见 [docs/STATUS.md](docs/STATUS.md)，文档权威边界见 [docs/DOCS.md](docs/DOCS.md)，技术选型与被否决方案见 [DECISIONS.md](DECISIONS.md)。

## 一键验证

并发与结算相关测试跑在**真实 MySQL** 上，不依赖内存 Mock。准备一个独立测试库（切勿指向开发库）：

```sql
CREATE DATABASE IF NOT EXISTS bid_arena_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON bid_arena_test.* TO 'bid_arena'@'%';
FLUSH PRIVILEGES;
```

指定测试库并运行：

```powershell
# Windows PowerShell
$env:BID_ARENA_TEST_DB_URL = "jdbc:mysql://主机:3307/bid_arena_test?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
$env:BID_ARENA_TEST_DB_USER = "bid_arena"
$env:BID_ARENA_TEST_DB_PASSWORD = "<本地口令>"
mvn clean verify
```

```bash
# Git Bash / Linux
export BID_ARENA_TEST_DB_URL="jdbc:mysql://主机:3307/bid_arena_test?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
export BID_ARENA_TEST_DB_USER="bid_arena"
export BID_ARENA_TEST_DB_PASSWORD="<本地口令>"
mvn clean verify
```

说明：

- 用 `clean` 而不是 `mvn test`：迁移脚本位于仓库根目录 `db/migration/`，增量构建可能让应用跑到旧副本（见 `DEBUG_LOG.md` DBG-2）。
- 测试会自动建表、执行 Flyway 迁移，并在每个用例前清空业务表；库名不得与开发库相同，否则拒绝启动。
- HTTP 与 WebSocket 集成测试**共用同一个自启动的服务实例**（随机空闲端口，不会是 8080），整轮测试结束时停掉；因此跑测试不需要先手动起后端。
- 断言消息含中文；Windows 控制台若乱码，执行 `chcp 65001`，或直接看 `target/surefire-reports/` 下的报告。
- 架构规则不连库（`ArchitectureTest`，秒级）；想反向确认这些规则真的会失败，跑 `python tools/arch_mutation_check.py`（逐条注入真实违规再还原，期望输出 `9/9 KILLED`）。

当前断言内容与未验证部分见 [docs/TRACEABILITY.md](docs/TRACEABILITY.md)。

## 持续集成（GitHub Actions）

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) 在每次 `main` 推送与 PR 上跑一遍，用的就是本文件里的那些命令——把「测试全绿」从「作者本机 + 手工建的测试库」变成任何一台干净机器上都能重放的日志：

| job | 跑什么 | 失败意味着 |
|---|---|---|
| `backend` | 服务容器提供一次性 MySQL 8.4，`mvn clean verify`（**232/232**），并断言 surefire 总用例数 ≥ 232 | 领域/集成/架构有回归，或者用例数被过滤器悄悄减少 |
| `frontend` | `npm ci` → `typecheck` → `npm test`（**73**）→ `VITE_WS_SAME_ORIGIN=1 npm run build` | 前端类型、单测或生产构建坏了 |
| `e2e` | 真起后端（8080/8090/18080）跑 `tools/agent_sim.py`（**44/44**），复位演示数据后再跑 `tools/auction_sim.py`（**52/52**） | 端到端行为与 `docs/openapi.yaml` 描述不一致 |
| `config` | `py_compile` 全部工具脚本、`docker compose config -q`、用 `nginx -t` 校验 `frontend/nginx.conf` | 部署编排或工具脚本语法坏了 |
| `images` | `docker compose build` 两个镜像 → 断言镜像里有产物（`app.jar`/`index.html`）→ `docker compose up -d` 起整栈，验 `:8080` 健康端点、`:8088` 的静态页与 `/api` 反代 → 断言一次性 `migrate` 服务退出码为 0、应用侧走的是“只校验”（D-39） | `Dockerfile` 构建不出来，或者 D-37 的单 origin 编排、D-39 的迁移收敛真的跑不起来 |

首次与目前最近一次运行均五个 job 全绿，整轮约 2 分钟：`d265c55`（[run #1](https://github.com/Ayong-ui/bid-arena/actions/runs/34802156957)）、`74d3e07`（[run #2](https://github.com/Ayong-ui/bid-arena/actions/runs/34802520376)，含上表的镜像构建与整栈验证）。

CI 里出现的库口令都是**一次性值**，只活在该次 run 的服务容器里，与任何真实环境无关；仓库里没有任何真实密钥。压测（`tools/stress_test.py`）与变异检查（`*_mutation_check.py`）**故意不进 CI**：前者在共享 runner 上拿不到可比的 QPS 数字，后者要反复改文件跑 Maven，留在提交前自检里做。

## 开发环境

已提供最小开发环境骨架：Solon 3.x 后端（含 HTTP 接口与鉴权）、Vue 3 + TypeScript + Vite 前端，以及 MySQL 8 Docker Compose。

```powershell
# Windows PowerShell
Copy-Item .env.example .env
# 把 .env 里的 CHANGE_ME 换成真实值，然后导出为环境变量（后端进程读环境变量，不读 .env 文件）
docker compose up -d mysql
mvn -q test-compile
cd frontend
npm install
npm run dev
```

```bash
# macOS / Linux / Git Bash
cp .env.example .env
# 同上：把 CHANGE_ME 换成真实值，并把里面的变量导出到当前 shell
docker compose up -d mysql
mvn -q test-compile
cd frontend && npm install && npm run dev
```

- 后端已接入 HTTP 接口层（统一下面一节的启动方式），`Application` 启动时会同时启动到期结算扫描。
- 前端开发地址：`http://localhost:5173`。后端不在 `8080`（或想换 Vite 端口）**不用改源码**：导出
  `VITE_DEV_API_TARGET` / `VITE_DEV_PORT`，或写在 `frontend/.env.local` 里即可（见 `frontend/vite.config.ts`）。
- 健康检查：`GET http://localhost:8080/api/v1/health`（公开，无需令牌）

也可以用容器起后端（本机只需要 Docker，不需要装 JDK/Maven）：

```bash
cp .env.example .env      # 填好 JWT_SECRET（必须）
docker compose up -d mysql backend
```

`backend` 服务等你所说的那次**一次性迁移**跑完才启动：schema 变更由同样的镜像以另一个 entrypoint（`com.bidarena.MigrateMain`）执行，退出码非 0 时应用根本不会起（D-39）。
它映射三个端口：`8080`（用户/管理）、`8090`（Agent）、`18080`（WebSocket）。
只想建镜像：`docker build -t bid-arena-backend .`。

**一条命令起完整栈（单 origin，推荐）**：前面那个方式还要自己想办法托管前端、把 CORS 与 WS 端口对上；直接把 `frontend` 也交给 compose 即可（D-37）：

```bash
cp .env.example .env          # 至少填 JWT_SECRET；WEB_PORT 默认 8088
# 网络受限时可先指定前端基础镜像源（见 .env.example 的 FRONTEND_*_IMAGE）
docker compose up -d --build
# 浏览器访问 http://localhost:8088
```

浏览器只看到一个 origin：`frontend` 容器用 Nginx 托管前端产物，并把 `/api` 与 `/ws` 反代到后端的
`8080`/`18080`，因此**不需要配 `CORS_ORIGINS`，也不用让浏览器直连 18080**。
`backend` 的端口保留发布只是方便本机 `curl` 与 E2E/压测脚本直连。
Agent API 仍是独立端口 `:8090`，**刻意不经反代**（保留 D-9/D-29 的爆炸半径隔离，理由写在 `frontend/nginx.conf` 顶部）。

`migrate` 是一次性服务（`restart: "no"`），迁完就退出；重跑 `docker compose up -d` 时它会再跑一次，但已是最新版本时是空转。
只想先把库对齐、再决定要不要起应用：`docker compose up --build migrate`。
迁移结论只在 `docker compose logs migrate` 里（`migrate` 与 `backend` 是两个容器）。
（注：“镜像能不能构建、整栈能不能起来”已由 CI 每次提交真验一遍（含上面那条迁移断言），见「持续集成」；
按仓库约定不在评测机上重建容器，见 [docs/STATUS.md](docs/STATUS.md) 的 C-6。）

### 已实现的 HTTP 接口

所有响应都是同一个封套 `{ code, message, data, requestId }`，完整契约（含每个字段与错误码）见 [docs/openapi.yaml](docs/openapi.yaml)。除下表标注「公开」的两个接口外，其余一律需要 `Authorization: Bearer <token>`（默认拒绝，见 `DECISIONS.md` D-15）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 公开。健康检查 |
| POST | `/api/v1/auth/login` | 公开。登录换取 JWT；响应用 `data.token` |
| GET | `/api/v1/users/me` | 当前用户（`id` / `email` / `role` / `name`） |
| GET | `/api/v1/wallets/me` | 我的钱包（总余额、冻结、**可用余额**） |
| GET | `/api/v1/wallets/me/ledger` | 我的资金流水（分页） |
| GET | `/api/v1/auctions` | 拍卖列表（按状态过滤 + 分页） |
| GET | `/api/v1/auctions/{id}` | 拍卖快照（前端唯一事实来源） |
| POST | `/api/v1/auctions/{id}/join` | 加入拍卖（幂等，重复加入不报错） |
| GET | `/api/v1/auctions/{id}/bids` | 出价记录（分页） |
| POST | `/api/v1/auctions/{id}/bids` | 出价（body 含 `requestId` 幂等键与 `amount`） |
| GET | `/api/v1/auctions/{id}/result` | 成交结果（未结算时 404） |
| POST | `/api/v1/admin/auctions` | 管理员：创建拍卖（201；可选 `startsAt` 预告开拍，到点由扫描器自动开拍） |
| POST | `/api/v1/admin/auctions/{id}/start` | 管理员：开始拍卖 |
| POST | `/api/v1/admin/auctions/{id}/cancel` | 管理员：取消并释放全部冻结 |
| GET | `/api/v1/admin/auctions/{id}/ledger` | 管理员：该场全部资金流水（含每条的主体 `actorType`：HUMAN/AGENT） |
| POST | `/api/v1/auth/ws-tickets` | 领一张一次性 WebSocket 入场券（60 秒有效，见下节） |
| POST | `/api/v1/admin/agent-tokens` | 管理员：为某个用户签发 Agent Token（明文**只在本次响应**出现） |
| POST | `/api/v1/admin/agent-tokens/{tokenId}/revoke` | 管理员：吊销 Token（幂等；不存在则 404） |
| GET | `/api/v1/admin/agent-tokens` | 管理员：全部 Agent 授权总览（不含明文） |
| GET | `/api/v1/me/agent-tokens` | 用户：**自己名下**的 Agent 授权列表（含派生 `status`，不含明文） |
| POST | `/api/v1/me/agent-tokens` | 用户：为自己签发一份授权（body **没有** `agentUserId`，归属由服务端钉死） |
| POST | `/api/v1/me/agent-tokens/{tokenId}/revoke` | 用户：吊销自己的 Token（不是自己的一律 404，不泄露存在性） |
| GET | `/api/v1/me/agent-proxies` | 用户：**自己名下**的托管 AI 代理列表（含 `status`/`nextBidAmount`/`leading`，不含明文） |
| POST | `/api/v1/me/agent-proxies` | 用户：在指定拍卖上创建一个托管 AI 代理（body 只有 `auctionId` + `budgetLimit`，归属由服务端钉死） |
| POST | `/api/v1/me/agent-proxies/{proxyId}/revoke` | 用户：撤销自己的托管代理（不是自己的一律 404） |
| GET | `/api/v1/admin/agent-proxies` | 管理员：全部托管 AI 代理总览（只读，带 `ownerUserId`） |
| GET | `/api/v1/agent/auctions/{id}` | Agent（`:8090`）：拍卖快照（需 `auction:read` 且在该 Token 的拍卖范围内） |
| POST | `/api/v1/agent/auctions/{id}/bids` | Agent（`:8090`）：出价（需 `auction:bid`；body 含 `requestId` 与 `amount`） |
| GET | `/api/v1/agent/auctions/{id}/result` | Agent（`:8090`）：成交结果（未结算时 404） |

演示账号（种子数据，与原文一致）：`admin@example.com / Admin123456!`、`bidder_a@example.com / Test123456!`、`bidder_b@example.com / Test123456!`。

### 已实现的实时通道（WebSocket）

事件信封（`auctionId` / `seq` / `serverTime` / `type` / `payload`）、七种事件类型、可见范围、序号语义与客户端重同步规则见 [docs/REALTIME_AND_COMMAND_FLOW.md](docs/REALTIME_AND_COMMAND_FLOW.md)，自动化验证汇总见 [docs/TRACEABILITY.md](docs/TRACEABILITY.md) 的 A8/C3/C4/C5。

两步接入（也可直接用任意 WS 客户端）：

```bash
# 1. 用已登录的 JWT 换一张一次性票（不要去猜 WS 端口，响应会告诉你）
curl -s -X POST http://localhost:8080/api/v1/auth/ws-tickets \
  -H "Authorization: Bearer $TOKEN"
# → {"code":"OK","data":{"ticket":"...","expiresAt":"...","wsPath":"/ws/auctions/{auctionId}","wsPort":18080}}

# 2. 连上去（把 {auctionId} 换成真 ID，把 ticket 填进去）
#    ws://localhost:18080/ws/auctions/{auctionId}?ticket=<ticket>
```

连接成功后先收到一帧权威快照（`type=AUCTION_SNAPSHOT`，带当前 `seq`），再收到 `CONNECTION_STATE`。之后：

- 只有**参与者**（或 ADMIN）能收到该场事件；未加入会收到 `NOT_JOINED` 并断开。
- 事件里的用户标识是匿名值（`anon-` + SHA-256 前 8 位），不是原始 `user_id`；详见 D-21。
- `seq` 是“已提交状态变更的版本号”：一次命令 +1，被拒的出价不推，一次提交的多个事件共用一个 `seq`。发现缺口就重取 `GET /api/v1/auctions/{id}`，不要猜测。
- **发往服务端的消息会被忽略**：命令入口只有 HTTP（D-22）。

## 竞拍 Agent API（`:8090`，P5）

竞拍 Agent（脚本、外部程序）用一个**独立于用户 JWT 的凭据**、在**独立端口**上读状态与出价。
为什么分开：Agent 需要长时间无人看管地运行，把用户 JWT 交给它等于把整张用户权限表交出去（D-9）。
评审可直接看 [AGENT_TOOL_SPEC.md](AGENT_TOOL_SPEC.md)（操作步骤、提示词模板、失败边界）。

三步上手（授权 → Agent 使用 → 随时吊销）。**普通用户不需要找管理员**：登录后在“我的 AI 代理”页点“新建授权”即可，等价于下面的第 1 步（`POST /api/v1/me/agent-tokens`，不传 `agentUserId`）。

```bash
# 1a. 用户自助签发（JWT 即登录令牌；归属就是调用者，请求体里没有 agentUserId）
curl -s -X POST http://localhost:8080/api/v1/me/agent-tokens \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"sniping-bot","scopes":["auction:read","auction:bid"],\
       "auctionIds":["1"],"expiresAt":"2030-01-01T00:00:00Z","rateLimitPerMinute":60}'
# → data.token 即 Agent Token（前缀类似 agt_...；只在这里出现一次）

# 1b. 或由管理员代为签发（多一个 agentUserId）
curl -s -X POST http://localhost:8080/api/v1/admin/agent-tokens \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"sniping-bot","agentUserId":2,"scopes":["auction:read","auction:bid"],\
       "auctionIds":["1"],"expiresAt":"2030-01-01T00:00:00Z","rateLimitPerMinute":60}'

# 2. Agent 读快照与出价（注意端口是 8090，不是 8080）
#    变量名沿用原文与 tools/agent_credentials.py：AUCTION_AGENT_TOKEN
curl -s http://localhost:8090/api/v1/agent/auctions/1 \
  -H "Authorization: Bearer $AUCTION_AGENT_TOKEN"
curl -s -X POST http://localhost:8090/api/v1/agent/auctions/1/bids \
  -H "Authorization: Bearer $AUCTION_AGENT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"requestId":"bot-0001","amount":1200}'

# 3. 吊销（幂等；不存在或不是自己的均为 404）
curl -s -X POST http://localhost:8080/api/v1/me/agent-tokens/1/revoke \
  -H "Authorization: Bearer $TOKEN"
# 管理员版同理：POST /api/v1/admin/agent-tokens/1/revoke
```

> 库里只存 sha256 摘要，明文**只在签发响应出现一次**；列表接口（含管理员总览）永远不会回明文。
> 用户只能看到自己的授权，管理员可在前端“我的 AI 代理”页底部看到全局总览。

几个容易踩的点（都有测试与决策记录）：

- **范围缺省即拒绝**：不写 `auctionIds` 不是“允许全部”，而是空集合、什么都访问不了（D-29）。
  越权访问返回 **403**（说明“换张 Token”，而不是 401“你的 Token 不行”）。
- **只读 Token 不能出价**：`scopes` 里没有 `auction:bid` 就 403（变异 G4/G13 守住）。
- **同一个出价事务**：Agent 出价不是第二条写入路径，它调的就是用户出价用的 `BidService.placeBid`，
  与真人共享 `bid_requests` 幂等表；首次出价会在**同一个事务**里自动补参与记录（D-30）。
- **端口是真隔离**：`:8090` 上只有 `/api/v1/agent/**`，其它路径（包括 `/api/v1/health`）一律 404 封套（DBG-22）。
- **尾段“博弈时间”禁止 Agent**：截止前最后 20 秒（`AUCTION_FINAL_GAME_WINDOW_SECONDS`）内，一切 Agent 出价被拒（**403 `HUMAN_ONLY_PERIOD`**），真人仍可出价；判定在出价事务内用数据库时间完成，进入即清场到结算（D-32，有意收紧原文规则 6）。窗口长度通过快照 `finalGameWindowSeconds`（HTTP 与 WS 同构）下发给前端，前端只用它渲染“博弈时间”提示，**不承担判定职责**。
- **吊销/过期立即失效**；超频返回 **429 `RATE_LIMITED`**。

一键实跑（扮演管理员与两个竞拍 Agent，逐条对比“期望 vs 实际”，任一条不符立即非零退出）：

```bash
# 需要后端已在 8080/8090 上运行（可用开发库）
python tools/agent_sim.py            # 完整流程 + 全部失败边界
python tools/agent_sim.py --skip-boundary   # 只看主链路
python tools/agent_sim.py --duration 60     # 拍卖持续秒数（默认 300）
```

脚本只用 Python 标准库（不需要 `pip install`）；每次运行自己创建拍卖与 Token，结束后默认清理（`--keep` 可保留供手工核对）。

**想用自己的 Token，而不是让脚本自己签发**（评审“把 Token 交给 Agent”的路径）：

```bash
export AUCTION_AGENT_TOKEN=<明文>          # Windows PowerShell: $env:AUCTION_AGENT_TOKEN="<明文>"
python tools/agent_sim.py --agent-only --auction-id <auctionId> [--bid]
```

这个模式不建场、不签发 Token，只用你给的凭据读状态 /（`--bid` 时）出一次价 / 读结果，
因此它复现的是“这枚 Token 的真实权限”，被 403/409/429 拒也算预期。
凭据只从环境变量读（脚本不做交互输入），没设就以退出码 2 停下并打印 PowerShell/Bash 两种设法；
`AGENT_API_BASE` / `AUCTION_ID` 可分别替代 `--agent-base` / `--auction-id`。

## 托管 AI 代理（前端「AI 代理」页，D-36）

上一条是**给开发者**的 Agent API；普通用户不需要写程序、也不需要开服务器：登录后点侧边栏 **「AI 代理」→「创建 AI 代理」**，选一场**进行中或尚未开拍**的拍卖、填一个**预算上限**，剩下的由服务端完成。

- **策略只有一条**（可预测、可断言）：只要自己不是领先者，就出 `当前价 + 最小加价`；达到预算上限就停手并提醒一次。不预冻结资金，钱只在真正出价时按既有出价事务冻结（D-30）。
- **到点自动进场**：若目标拍卖还没开始，需要它有预告时间（管理员创建时填 `startsAt`，或在库中直接设置）；`AuctionStartScheduler` 到点自动开拍（与管理员手动 `start` 复用完全相同的开拍逻辑，D-35），`AgentProxyScheduler` 随即开始跟价。前端大厅与运营台会显示“预告 mm:ss 后开拍”（用服务端时间校准，不用本机时钟）。
- **一人一场只能挂一个代理**（`uk_proxy_owner_auction`）；撤销后可重建，复用同一条记录。
- **尾段“博弈时间”没有例外**：托管代理同样是 Agent，最后 20 秒内同样被 `HUMAN_ONLY_PERIOD` 拒；这是产品规则（D-32），不是故障。
- **提醒走轮询**（仅在“AI 代理”页每 3 秒拉一次列表并对比前后状态），不新增私有 WebSocket 事件。

相关后台配置（都可在 `.env` 里覆盖，见 [`.env.example`](.env.example)）：`AGENT_PROXY_TICK_INTERVAL_MS`（默认 500）、`AGENT_PROXY_BATCH_SIZE`（默认 50）、`AUCTION_START_SCAN_INTERVAL_MS`（默认 1000）、`AUCTION_START_BATCH_SIZE`（默认 50）。

```bash
# 等价的最小 curl（先登录拿 $TOKEN）
curl -s -X POST http://localhost:8080/api/v1/me/agent-proxies \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"auctionId":"1","budgetLimit":1200}'
curl -s http://localhost:8080/api/v1/me/agent-proxies -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/api/v1/me/agent-proxies/1/revoke -H "Authorization: Bearer $TOKEN"
```

> 托管代理与 Agent Token 是**两条并列的路径**：“AI 代理”页把托管做成主路径，Token 那一套收进页面底部的“高级”（自己写程序的人用）。两者共用同一个出价事务与同一套 D-32 规则。

## 全链路模拟脚本（并发 / 狙击 / 断线快照 / 结算核对）

`tools/agent_sim.py` 覆盖 Agent 一侧；面向**用户侧**全链路的是 `tools/auction_sim.py`，
它把“只有并发才成立”的那批事实变成一条可复现命令——20 条并发同/邻价、`requestId` 重试、
拒绝场景、最后五秒狙击、WebSocket 断线快照、到期结算与钱包对账，共八个阶段。

```bash
# 需要后端已在 8080/8090/18080 上运行（可用开发库）
python tools/auction_sim.py            # 八个阶段全跑（含狙击等待，约 1 分钟）
python tools/auction_sim.py --quick    # 跳过最慢的狙击阶段（约省 35 秒）
python tools/auction_sim.py --keep     # 结束时不取消拍卖，便于在前端观察
```

同样只用标准库，**含一个最小 RFC 6455 WebSocket 客户端**（约 100 行），因此不需要 `websocket-client`。
实测 **52/52，退出码 0**（逐条对比“期望 vs 实际”）；它断言的不变量：

- **同价并发恰好一笔成交**（20 条同价 → `OK=1`，其余 `BID_TOO_LOW`）；
- **邻价并发以最高价收尾**（邻价**允许**成交两笔：先 120 后 130；同价位至多一笔）；
- **幂等键含 `user_id`**（D-31）：同一用户同 `requestId` 并发 20 次 = 1 写 + 19 重放且只冻结一次；
  另一用户复用同一串**不算重放**；
- **最后五秒狙击**：出价触发 +10 秒延时，`MAX_EXTENSIONS=3` 达上限后**不再延时但出价照常接受**；
- **尾段“博弈时间”**（P6，D-32）：截止前最后 20 秒（`AUCTION_FINAL_GAME_WINDOW_SECONDS`）拒绝一切 Agent 出价（`HUMAN_ONLY_PERIOD`），真人不受限；模拟脚本用真人账号，因此不受影响，Agent 侧边界由 `BidServiceTest` 与 `AgentApiIntegrationTest` 覆盖；
- **断线快照**：连上第一帧是权威快照，提交后收到 `BID_ACCEPTED`（领先者为匿名值），换新票重连能对齐到最新价；
- **结算对账**：`FINISHED`/`TIMEOUT` 后，钱包“总余额减少 = 冻结释放 = 成交价”。

**局限（如实声明）**：公开 API 没有注册端点，种子只有 3 个演示账号，因此“20 个**不同用户**并发”
无法只靠 HTTP 复现；脚本用“20 条并发出价请求（跨可用账号 + 唯一 `requestId`）”等价模拟并发压力，
真正 20 个不同 `user_id` 的并发由真实库上的 `BidConcurrencyTest` 覆盖。

## 压测脚本（尾段清场 / 持续吞吐）

`tools/auction_sim.py` 关心“流程对不对”、`tools/agent_sim.py` 关心“Agent 边界对不对”，
两者并发都很小。真正只在并发下才成立的两件事由 `tools/stress_test.py` 覆盖：

```bash
# 需要后端已在 8080/8090/18080 上运行（可用开发库）
python tools/stress_test.py                              # 博弈时间清场，50 并发（默认）
python tools/stress_test.py -c 300                       # 拉高并发再验一次
python tools/stress_test.py --mode throughput -c 50 --seconds 10
```

- `--mode game-window`（默认）：等剩余时间进入尾段窗口后，把 `--concurrency` 条 Agent 出价**同时**
  打进 `:8090`，断言 **100% 403 `HUMAN_ONLY_PERIOD`**（把测试 Token 限流拉满到 6000，确保看到的是
  “窗口拒了每一条”而不是“限流先拒了一半”）；紧接着一条真人出价必须被接受，并核对出价记录与
  管理员流水里**没有留下任何 Agent 痕迹**。
- `--mode throughput`：在 `--seconds` 秒内用 `--concurrency` 个 worker 做读写混合（3:1），
  打印 QPS、P50/P95/P99、状态码/错误码分布；出现 5xx 或连接失败即失败。

实测（本机开发库，单场拍卖）：博弈时间 `-c 100` → **100/100 全拒（403）**、11/11 通过；
吞吐 `-c 50 --seconds 10` → **约 410 QPS，P50≈109ms / P95≈243ms / P99≈315ms，0 个 5xx**。
409 全部是 `BID_TOO_LOW`（并发抢价必然结果），不是错误。

同样只用标准库；每次运行自建拍卖与 Token，结束后默认取消（`--keep` 可保留）。

### 重复运行前：演示数据的恢复（`db/reset_demo_data.sql`）

三个脚本都跑在开发库上，并且会**真的花钱**（真冻结、真成交）。连跑几轮之后种子余额（各 1000）
会被花完，此后的出价全是 `INSUFFICIENT_BALANCE`——那是“数据用完了”，不是缺陷。恢复种子状态：

```bash
docker exec -i bid-arena-mysql-1 mysql --default-character-set=utf8mb4 \
  -ubid_arena -p"$DB_PASSWORD" bid_arena < db/reset_demo_data.sql
```

该脚本只动数据（清空竞拍相关表、钱包写回 1000/0、重建那场 `DRAFT` 演示拍品；`users` 保留），
不改 schema、不碰 `flyway_schema_history`，最后回显钱包与拍品供人核对。

三个脚本自己也会在开跑前检查可用余额：不足时**在阶段 0 停下**并打印上面这条命令，
退出码 `2`（`0` = 全绿 / `1` = 有检查失败 / `2` = 缺数据）。这样“跑不完”的原因永远出现在
第一个失败点上，而不是以一排与本因无关的 `INSUFFICIENT_BALANCE` 或一个裸的 WS 超时收场
（见 [DEBUG_LOG.md](DEBUG_LOG.md) DBG-31）。

## 设计与决策

- 业务全景（角色、主链路、四条不变式）、分层与一致性方案见 [DESIGN.md](DESIGN.md)。
- 技术选型与取舍（背景、候选、代价、验证结果）见 [DECISIONS.md](DECISIONS.md)。
- HTTP 契约见 [docs/openapi.yaml](docs/openapi.yaml)。
- WebSocket 事件与 `seq` 恢复见 [docs/REALTIME_AND_COMMAND_FLOW.md](docs/REALTIME_AND_COMMAND_FLOW.md)。
- 领域边界与不变量见 [docs/DOMAIN_DESIGN.md](docs/DOMAIN_DESIGN.md)。
- 资金冻结、锁顺序与幂等见 [docs/FUNDING_AND_CONCURRENCY.md](docs/FUNDING_AND_CONCURRENCY.md)。
- 页面与交互原型见 [docs/PRODUCT_PROTOTYPE.md](docs/PRODUCT_PROTOTYPE.md)。
- 进度看板见 [docs/STATUS.md](docs/STATUS.md)，文档地图与权威边界见 [docs/DOCS.md](docs/DOCS.md)。
- 提交与交付规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，验收追溯见 [docs/TRACEABILITY.md](docs/TRACEABILITY.md)。

文档明确区分目标架构与当前实现状态；前端已经接入真实 HTTP/WebSocket（见下文「前端」），不再有本地 Mock 事实来源。资金的正确性由真实 MySQL 集成测试与变异测试验证。

## 未完成边界（如实声明）

目前**还不能**做到的事，以及对应的原因：

- **模拟脚本已覆盖两侧**。`tools/agent_sim.py` 跑通 Agent 的「签发 → 读 → 出价 → 幂等重放 →
  越权/过期/吊销/限流边界 → 结果」（**44/44**）；`tools/auction_sim.py` 跑通用户侧全链路——
  并发同/邻价、`requestId` 重试、拒绝场景、最后五秒狙击、断线快照、结算对账（**52/52**）。
  并发清场与吞吐另由 `tools/stress_test.py` 覆盖（博弈时间 `-c 100` 全拒、吞吐约 410 QPS 无 5xx）。
  唯一不能只靠 HTTP 复现的是“20 个**不同用户**并发”：公开 API 没有注册端点、种子只有 3 个演示账号，
  这部分由真实库上的 `BidConcurrencyTest` 覆盖（详见 [docs/TRACEABILITY.md](docs/TRACEABILITY.md) E1）。
- **整栈（compose + 反代）已由 CI 每次拉起来验一遍，但没在浏览器里真点过**。`backend`/`frontend` 两个
  `Dockerfile` 与 `docker-compose.yml` 每次提交都会 `docker compose build` + `docker compose up -d`，
  并断言两个镜像里真有产物、`:8080` 健康端点、`:8088` 的静态页与 `/api` 反代都返回 200，以及一次性 `migrate` 服务退出码为 0、应用侧走的是“只校验不迁移”（D-39）——D-37 的单 origin
  编排第一次真的跑起来是在 CI 上。仍未做的：浏览器里的人工核对，以及在评测机上 `docker compose up`
  （本机 Docker daemon 在远程 VM 且连不上 Docker Hub，本地镜像源也没有 node/maven/temurin 基础镜像，
  已用 `ARG` 暴露基础镜像供受限环境替换；按仓库约定不在评测机上重建容器）。
- **多实例/滚动发布所需的“扫描器开关”仍未做**。D-39 已经把 schema 变更收敛成一次性步骤，但三个扫描器
  （结算、开拍、托管代理）仍会在每个实例上无条件启动。当前编排只有**一个** `backend`，所以这不是眼下的问题；
  真要多实例，缺的不是一个布尔开关，而是“哪个实例负责扫描”的职责划分，所以刻意留到真有第二实例时再设计。
- **[AI_USAGE.md](AI_USAGE.md) 已填写**：工具与模型（`pi` + `deepseek-v4-flash`）、各模块人机分工与口径、
  四项本人设计决定、六项未采用方案、四项真实错误，以及七类目前仍不能独立解释/修改的代码；
  文末留三项「作者核对清单」（模型列表完整性、比例口径、决定归属）。
- **没有线上地址、没有演示录屏**（两段式现场核验的素材）。

已实现的边界：用户侧 HTTP 19 个端点 + Agent 侧 3 个业务端点与 2 个签发/吊销端点 + WebSocket 实时通道（P2/P3）、
前端真实接入（P4）、Agent API 与端到端模拟（P5）、用户侧全链路模拟（E1）、架构守卫 9 条。
完整的逐项状态与证据见 [docs/STATUS.md](docs/STATUS.md) 与 [docs/TRACEABILITY.md](docs/TRACEABILITY.md)。

## 公开仓库约定

原始评测 PDF、`.env`、依赖目录、构建产物和本地运行数据不会提交。需求原文和业务分析以 Markdown 形式保留，便于审阅和版本追踪。提交前执行 `git status --short`，确认没有 Token、密码或个人配置。

## 前端（Vue 3 + TypeScript + Pinia）

前端位于 `frontend/`，**接入真实后端**：类型由 `docs/openapi.yaml` 生成（`npm run gen:api`，D-26）；
金额、状态、倒计时全部来自 HTTP 快照与 WebSocket 事件，本地不再自己算（D-27/D-28）。

```powershell
# 1. 先启动后端（见上文），确认 8080 与 18080 可访问
# 2. 启动前端
cd frontend
npm install
npm run dev        # http://localhost:5173/
```

页面用种子里的三个演示账号登录（与原文一致）：

- `admin@example.com / Admin123456!`：管理员，可创建、开始、取消拍卖。
- `bidder_a@example.com / Test123456!`、`bidder_b@example.com / Test123456!`：两名竞拍者。

建议验证路径：管理员登录创建并开始拍卖（也可以在创建时填一个**预告开拍时间**，到点自动开拍）→ 换成 `bidder_a` 加入并出价 → 查看钱包冻结与流水 →
另开一个浏览器用 `bidder_b` 加价，`bidder_a` 的详情页会通过 WebSocket 实时更新价格、领先者与剩余时间 →
等待倒计时结束查看结果。倒计时以服务端时间为准；断线时页面显示“重连中/正在恢复快照”，恢复后由权威快照对齐。
想看 AI 那侧：用 `bidder_a` 打开侧边栏 **「AI 代理」**，选一场进行中的拍卖创建一个托管代理，看它按最小加价跟价、钱包出现冻结与流水（流水里主体为 AI，只有你自己与管理员看得到）；等到最后 20 秒它会按规则停手。

前端自测（不需要后端）：

```powershell
cd frontend
npm test           # 73 个单测（api client / realtime feed / socket / store / anonymous）
npm run typecheck  # vue-tsc
npm run build      # vite build
```

联调测试（需要后端已在 8080 运行）：

```powershell
cd frontend
$env:BID_ARENA_LIVE="1"
$env:BID_ARENA_DEMO_ADMIN_EMAIL="admin@example.com";     $env:BID_ARENA_DEMO_ADMIN_PASSWORD="Admin123456!"
$env:BID_ARENA_DEMO_BIDDER_EMAIL="bidder_a@example.com"; $env:BID_ARENA_DEMO_BIDDER_PASSWORD="Test123456!"
npm run test:live  # HTTP 契约 2 个 + WebSocket 事件流 1 个
```

这四个 `BID_ARENA_DEMO_*` 变量 `tools/agent_sim.py` / `tools/auction_sim.py` / `tools/stress_test.py` 也认（默认值就是种子账号），
换了种子口令时不必去改脚本。`auction_sim.py` 还会用到 `BID_ARENA_DEMO_BIDDER_B_EMAIL` / `..._BIDDER_B_PASSWORD`。

测试有效性同样经过变异验证（`python tools/mutation_check.py`，16/16 KILLED），证据汇总见
[`docs/TRACEABILITY.md`](docs/TRACEABILITY.md) 的「前端（P4）」一节。
