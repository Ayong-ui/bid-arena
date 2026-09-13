# Bid Arena（拍卖间）

仓库地址：<https://github.com/Ayong-ui/bid-arena>（公开，含完整提交历史；`main` 已开启分支保护）

这是一个公开管理的 Bid Arena 拍卖系统仓库。当前已完成：应用内 Flyway 迁移（V1~V4）、身份/钱包/资金流水数据模型、**并发安全的出价事务**、**唯一结算与到期自动结算**、**HTTP API + JWT 鉴权 + RBAC + 统一响应封套**、**WebSocket 实时事件与 `seq` 缺口恢复**、**可执行的架构守卫**（ArchUnit 九条分层/跨上下文/无环规则）、**前端接入真实 HTTP/WS**，以及**竞拍 Agent API**（独立端口 `:8090`、独立 Token、范围/权限/过期/吊销/限流）与**两份端到端模拟脚本**（Agent 侧 `tools/agent_sim.py`、用户侧全链路 `tools/auction_sim.py`）。全量 **187 个测试**（178 个真实 MySQL 集成/领域测试 + 9 条架构规则）。尚未完成：演示录屏与现场核验素材。

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

## 开发环境

已提供最小开发环境骨架：Solon 3.x 后端（含 HTTP 接口与鉴权）、Vue 3 + TypeScript + Vite 前端，以及 MySQL 8 Docker Compose。

```powershell
Copy-Item .env.example .env
# 把 .env 里的 CHANGE_ME 换成真实值，然后导出为环境变量（后端进程读环境变量，不读 .env 文件）
docker compose up -d mysql
mvn -q test-compile
cd frontend
npm install
npm run dev
```

- 后端已接入 HTTP 接口层（统一下面一节的启动方式），`Application` 启动时会同时启动到期结算扫描。
- 前端开发地址：`http://localhost:5173`
- 健康检查：`GET http://localhost:8080/api/v1/health`（公开，无需令牌）

也可以用容器起后端（本机只需要 Docker，不需要装 JDK/Maven）：

```bash
cp .env.example .env      # 填好 JWT_SECRET（必须）
docker compose up -d mysql backend
```

`backend` 服务会等 MySQL 健康后启动，并自己跑 Flyway 迁移与种子（与本地直连共用同一套迁移）。
它映射三个端口：`8080`（用户/管理）、`8090`（Agent）、`18080`（WebSocket）。
只想建镜像：`docker build -t bid-arena-backend .`。
（注：本仓库的验证流程没有实际 `docker compose up` 过——评测机的容器按约定不重建，见 [docs/STATUS.md](docs/STATUS.md) 的 C-6。）

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
| POST | `/api/v1/admin/auctions` | 管理员：创建拍卖（201） |
| POST | `/api/v1/admin/auctions/{id}/start` | 管理员：开始拍卖 |
| POST | `/api/v1/admin/auctions/{id}/cancel` | 管理员：取消并释放全部冻结 |
| POST | `/api/v1/auth/ws-tickets` | 领一张一次性 WebSocket 入场券（60 秒有效，见下节） |
| POST | `/api/v1/admin/agent-tokens` | 管理员：为某个用户签发 Agent Token（明文**只在本次响应**出现） |
| POST | `/api/v1/admin/agent-tokens/{tokenId}/revoke` | 管理员：吊销 Token（幂等；不存在则 404） |
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

三步上手（管理员签发 → Agent 使用 → 随时吊销）：

```bash
# 1. 管理员签发：把明文交出去一次（仅本次响应有；库里只存 sha256 摘要）
curl -s -X POST http://localhost:8080/api/v1/admin/agent-tokens \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"sniping-bot","agentUserId":2,"scopes":["auction:read","auction:bid"],\
       "auctionIds":["1"],"expiresAt":"2030-01-01T00:00:00Z","rateLimitPerMinute":60}'
# → data.token 即 Agent Token（前缀类似 agt_...；只在这里出现一次）

# 2. Agent 读快照与出价（注意端口是 8090，不是 8080）
curl -s http://localhost:8090/api/v1/agent/auctions/1 \
  -H "Authorization: Bearer $AGENT_TOKEN"
curl -s -X POST http://localhost:8090/api/v1/agent/auctions/1/bids \
  -H "Authorization: Bearer $AGENT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"requestId":"bot-0001","amount":1200}'

# 3. 吊销（幂等；不存在返回 404）
curl -s -X POST http://localhost:8080/api/v1/admin/agent-tokens/1/revoke \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

几个容易踩的点（都有测试与决策记录）：

- **范围缺省即拒绝**：不写 `auctionIds` 不是“允许全部”，而是空集合、什么都访问不了（D-29）。
  越权访问返回 **403**（说明“换张 Token”，而不是 401“你的 Token 不行”）。
- **只读 Token 不能出价**：`scopes` 里没有 `auction:bid` 就 403（变异 G4/G13 守住）。
- **同一个出价事务**：Agent 出价不是第二条写入路径，它调的就是用户出价用的 `BidService.placeBid`，
  与真人共享 `bid_requests` 幂等表；首次出价会在**同一个事务**里自动补参与记录（D-30）。
- **端口是真隔离**：`:8090` 上只有 `/api/v1/agent/**`，其它路径（包括 `/api/v1/health`）一律 404 封套（DBG-22）。
- **吊销/过期立即失效**；超频返回 **429 `RATE_LIMITED`**。

一键实跑（扮演管理员与两个竞拍 Agent，逐条对比“期望 vs 实际”，任一条不符立即非零退出）：

```bash
# 需要后端已在 8080/8090 上运行（可用开发库）
python tools/agent_sim.py            # 完整流程 + 全部失败边界
python tools/agent_sim.py --skip-boundary   # 只看主链路
python tools/agent_sim.py --duration 60     # 拍卖持续秒数（默认 300）
```

脚本只用 Python 标准库（不需要 `pip install`）；每次运行自己创建拍卖与 Token，结束后默认清理（`--keep` 可保留供手工核对）。

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
- **断线快照**：连上第一帧是权威快照，提交后收到 `BID_ACCEPTED`（领先者为匿名值），换新票重连能对齐到最新价；
- **结算对账**：`FINISHED`/`TIMEOUT` 后，钱包“总余额减少 = 冻结释放 = 成交价”。

**局限（如实声明）**：公开 API 没有注册端点，种子只有 3 个演示账号，因此“20 个**不同用户**并发”
无法只靠 HTTP 复现；脚本用“20 条并发出价请求（跨可用账号 + 唯一 `requestId`）”等价模拟并发压力，
真正 20 个不同 `user_id` 的并发由真实库上的 `BidConcurrencyTest` 覆盖。

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
  唯一不能只靠 HTTP 复现的是“20 个**不同用户**并发”：公开 API 没有注册端点、种子只有 3 个演示账号，
  这部分由真实库上的 `BidConcurrencyTest` 覆盖（详见 [docs/TRACEABILITY.md](docs/TRACEABILITY.md) E1）。
- **Compose 的 `backend` 服务尚未在本机构建过镜像**。`Dockerfile` 与 `docker-compose.yml` 已就位，
  `docker compose config` 已校验；但按仓库约定（不重建评测机上的容器），没有实际 `docker compose up` 过。
- **[AI_USAGE.md](AI_USAGE.md) 仍是骨架**：结构与素材索引就位，但分工比例、本人设计决定等
  `【本人填写】` 段落需由作者本人补齐，不代填。
- **没有线上地址、没有演示录屏**（两段式现场核验的素材）。

已实现的边界：用户侧 HTTP 15 个端点 + Agent 侧 3 个业务端点与 2 个签发/吊销端点 + WebSocket 实时通道（P2/P3）、
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

建议验证路径：管理员登录创建并开始拍卖 → 换成 `bidder_a` 加入并出价 → 查看钱包冻结与流水 →
另开一个浏览器用 `bidder_b` 加价，`bidder_a` 的详情页会通过 WebSocket 实时更新价格、领先者与剩余时间 →
等待倒计时结束查看结果。倒计时以服务端时间为准；断线时页面显示“重连中/正在恢复快照”，恢复后由权威快照对齐。

前端自测（不需要后端）：

```powershell
cd frontend
npm test           # 56 个单测（api client / realtime feed / store / anonymous）
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

测试有效性同样经过变异验证（`python tools/mutation_check.py`，16/16 KILLED），证据汇总见
[`docs/TRACEABILITY.md`](docs/TRACEABILITY.md) 的「前端（P4）」一节。
