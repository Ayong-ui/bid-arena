# Bid Arena（拍卖间）

仓库地址：<https://github.com/Ayong-ui/bid-arena>（公开，含完整提交历史；`main` 已开启分支保护）

这是一个公开管理的 Bid Arena 拍卖系统仓库。当前已完成：应用内 Flyway 迁移（V1~V3）、身份/钱包/资金流水数据模型、**并发安全的出价事务**、**唯一结算与到期自动结算**、**HTTP API + JWT 鉴权 + RBAC + 统一响应封套**，以及 **WebSocket 实时事件与 `seq` 缺口恢复**（含 116 个真实 MySQL 集成测试，覆盖 INV-1~4 与 A/C 组相关验收项）。尚未完成：前端接真实 HTTP/WS、Agent API、模拟脚本与 Compose/E2E。

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

文档明确区分目标架构与当前实现状态；不要把 Mock 前端当作生产前端，也不要把"尚未接入 HTTP"理解成"资金逻辑未实现"——资金的正确性已经由真实 MySQL 集成测试与变异测试验证。

## 公开仓库约定

原始评测 PDF、`.env`、依赖目录、构建产物和本地运行数据不会提交。需求原文和业务分析以 Markdown 形式保留，便于审阅和版本追踪。提交前执行 `git status --short`，确认没有 Token、密码或个人配置。

## 前端 MVP（当前可运行）

前端 MVP 位于 `frontend/`，不依赖后端，使用 Vue 3 + TypeScript + Pinia 和本地 Mock 数据验证核心页面流程。

```powershell
cd frontend
npm install
npm run dev
```

打开 <http://localhost:5173/>。页面右上角可切换演示身份：

- `林默`：竞拍者，可加入拍卖、出价、查看钱包和流水。
- `周航`：另一位竞拍者，作为已有竞价数据展示。
- `管理员`：可创建、开始和取消本地拍卖。

建议验证路径：管理员创建并开始草稿拍卖 → 切换为林默 → 加入并出价 → 查看钱包冻结积分 → 在拍卖详情中等待倒计时结束 → 查看最终结果。MVP 的数据只保存在当前页面内，刷新后会恢复初始演示数据；真实 HTTP、MySQL 和 WebSocket 接入按 `DESIGN.md` 与 `docs/openapi.yaml` 后续替换 Mock 层（见 `docs/STATUS.md` 的 P4）。
