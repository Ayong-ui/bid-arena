# Bid Arena（拍卖间）

一个**并发安全的在线拍卖系统**：真人竞拍、AI 代拍、实时推送、资金冻结与结算对账。
Java 17 + Solon 3（不用 Spring）+ MySQL 8.4 + Flyway；前端 Vue 3 + TypeScript + Vite，由 Nginx 收成**一个入口**。

[![CI](https://github.com/Ayong-ui/bid-arena/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Ayong-ui/bid-arena/actions/workflows/ci.yml)
仓库：<https://github.com/Ayong-ui/bid-arena>（公开，含完整提交历史；`main` 已开分支保护）

**5 分钟跑起来**（本机只要有 Docker，浏览器只开一个端口）：

```bash
git clone https://github.com/Ayong-ui/bid-arena.git
cd bid-arena
cp .env.example .env          # 只需改里面两处 CHANGE_ME
docker compose up -d --build  # 首次会构建两个镜像；MySQL 初始化 + 迁移约 1~2 分钟
# 打开 http://localhost:8088 ，用下面「演示账号」登录
```

演示账号（种子数据，服务起来就已存在）：

| 角色 | 账号 | 口令 | 能做什么 |
|---|---|---|---|
| 管理员 | `admin@example.com` | `Admin123456!` | 创建/开始/取消拍卖，看全场流水与授权总览 |
| 竞拍者 A | `bidder_a@example.com` | `Test123456!` | 加入拍卖、出价、建托管 AI 代理 |
| 竞拍者 B | `bidder_b@example.com` | `Test123456!` | 同上（开两个窗口就能互相加价） |

> 这三个账号写在种子迁移 `db/migration/V2__identity_wallet_ledger.sql` 里，改种子的同时记得改本表。

## 目录

- [1. 这是什么](#1-这是什么)
- [2. 快速启动（推荐路径）](#2-快速启动推荐路径)
  - [2.1 前置条件](#21-前置条件)
  - [2.2 复制环境变量（唯一必做的配置）](#22-复制环境变量唯一必做的配置)
  - [2.3 一条命令起整栈](#23-一条命令起整栈)
  - [2.4 打开并登录](#24-打开并登录)
- [3. 小教程：八步走完整条链路](#3-小教程八步走完整条链路)
  - [第 1~2 步：起栈并确认初始化](#第-12-步起栈并确认初始化)
  - [第 3 步：管理员开始演示拍卖](#第-3-步管理员开始演示拍卖)
  - [第 4 步：两个浏览器用户加入并互相加价](#第-4-步两个浏览器用户加入并互相加价)
  - [第 5 步：20 并发模拟脚本](#第-5-步20-并发模拟脚本)
  - [第 6 步：创建 Agent Token，让竞拍 Agent 参与](#第-6-步创建-agent-token让竞拍-agent-参与)
  - [第 7 步：查看成交、钱包与流水](#第-7-步查看成交钱包与流水)
  - [第 8 步：运行自动化测试](#第-8-步运行自动化测试)
  - [卡住了看这里](#卡住了看这里)
- [4. 部署教材](#4-部署教材)
  - [4.1 服务拓扑与端口](#41-服务拓扑与端口)
  - [4.2 环境变量速查](#42-环境变量速查)
  - [4.3 常见部署形态](#43-常见部署形态)
  - [4.4 迁移与初始化](#44-迁移与初始化)
  - [4.5 多实例部署](#45-多实例部署)
  - [4.6 排错手册](#46-排错手册)
  - [4.7 停止 / 清库 / 复位演示数据](#47-停止--清库--复位演示数据)
- [5. 本地开发（源码方式，不用 Docker 跑应用）](#5-本地开发源码方式不用-docker-跑应用)
  - [5.1 后端](#51-后端)
  - [5.2 前端](#52-前端)
  - [5.3 前端联调测试](#53-前端联调测试)
- [6. 测试与持续集成](#6-测试与持续集成)
  - [6.1 一键测试命令](#61-一键测试命令)
  - [6.2 后端 242 个用例怎么构成](#62-后端-242-个用例怎么构成)
  - [6.3 守卫与变异验证](#63-守卫与变异验证)
  - [6.4 CI（五个 job，失败含义各自独立）](#64-ci五个-job失败含义各自独立)
- [7. 工具脚本（`tools/`）](#7-工具脚本tools)
- [8. API 与实时通道（速览）](#8-api-与实时通道速览)
- [9. 项目结构](#9-项目结构)
- [10. 文档地图](#10-文档地图)
- [11. 未完成边界（如实声明）](#11-未完成边界如实声明)
- [12. 公开仓库约定](#12-公开仓库约定)

## 1. 这是什么

- **业务**：管理员创建拍品（可预填开拍时间，到点自动开拍）→ 竞拍者在倒计时内出价 → 价高者得，结算后钱包与流水可对账。
- **必须做对的三件事**：① 同一瞬间的并发出价只能有一笔成交；② 同一用户的重复请求（`requestId` 重放）只扣一次钱；③ 结算只有一次，进程重启也不能漏结算或重复结算。
- **两条 AI 路径**：面向普通用户的**托管 AI 代理**（填个预算上限，服务端自动跟价）与面向开发者的**竞拍 Agent API**（独立端口 + 独立 Token，可交给脚本或 Coding Agent）。
- **实时**：WebSocket 推送价格、领先者、剩余时间与延时；用 `seq` 检测缺口，断线重连先取权威快照。

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Java 17 + Solon 3 | 手写分层（domain / application / adapter / persistence），无框架魔法，启动约 2 秒 |
| 数据库 | MySQL 8.4 + Flyway | 迁移 `db/migration/V1~V6`；并发靠行锁 + 条件更新，不靠应用内锁 |
| 前端 | Vue 3 + TypeScript + Pinia + Vite | 类型由 `docs/openapi.yaml` 生成；价与剩余时间以服务端快照为准，前端只做倒计时插值，不自己定价 |
| 部署 | Docker Compose + Nginx | 单 origin：`/api` 与 `/ws` 反代，浏览器只访问一个端口 |

## 2. 快速启动（推荐路径）

### 2.1 前置条件

- Docker 与 Docker Compose v2（`docker compose version`）。
- 端口空闲：`8088`（浏览器入口）、`8080` / `8090` / `18080`（后端）、`3307`（MySQL）。被占了改 `.env`，见 [§4.2](#42-环境变量速查)。
- 不需要装 JDK / Node / Maven——都在镜像里。

### 2.2 复制环境变量（唯一必做的配置）

```bash
cp .env.example .env         # Windows PowerShell: Copy-Item .env.example .env
```

打开 `.env`，把**两处** `CHANGE_ME` 换成随机值：

```dotenv
MYSQL_PASSWORD=你自己的一串口令
JWT_SECRET=至少32字节的随机串          # 生成：openssl rand -base64 48
```

> `.env` 已被 `.gitignore` 排除；仓库里只有 `.env.example`，**没有任何真实密钥**。
> 其余键都有可用默认值，想调再看 [§4.2](#42-环境变量速查)。

### 2.3 一条命令起整栈

```bash
docker compose up -d --build
```

四个服务各司其职——这也是它比“手动起三样东西”省事的原因：

| 服务 | 作用 | 起完的状态 |
|---|---|---|
| `mysql` | MySQL 8.4，数据在 `mysql_data` 卷里 | `healthy` |
| `migrate` | **一次性**跑 Flyway 迁移 + 种子数据（D-39） | 退出码 `0`，容器停住 |
| `backend` | 常驻应用：用户/管理 API、Agent API、WebSocket | `Up`，并且**只校验不迁移**（`MIGRATE_ON_START=false`） |
| `frontend` | Nginx 托管前端产物，并把 `/api`、`/ws` 反代到后端 | `Up` |

确认迁移真的成功（应用不会对着旧 schema 提供服务）：

```bash
docker compose ps                       # migrate 应显示 Exited (0)
docker compose logs migrate | tail -5   # 应看到 "Successfully applied ..." 或 "up to date"
```

### 2.4 打开并登录

浏览器访问 **<http://localhost:8088>**，用演示账号里的 `admin@example.com / Admin123456!` 登录。

- 后端健康检查（公开，无需令牌）：<http://localhost:8080/api/v1/health>
- 看某个服务在说什么：`docker compose logs -f backend`（`frontend` / `mysql` / `migrate` 同理）。

## 3. 小教程：八步走完整条链路

从零到“看见成交与流水”。照着做即可，每步都写了**你会看到什么**和**怎么自己验证**。

| 步 | 做什么 | 关键点 |
|---|---|---|
| 1 | 起栈（复制环境变量 → Compose 启动依赖） | 见 [§2](#2-快速启动推荐路径) |
| 2 | 自动迁移与初始化 | `migrate` 退出码 0；演示账号与 DRAFT 拍品就绪 |
| 3 | 管理员开始演示拍卖 | 种子里那场**不会自动倒计时**，必须手动开始 |
| 4 | 两个浏览器用户加入并互相加价 | 冻结/释放、领先者、`seq` 实时变化 |
| 5 | 20 并发模拟脚本 | 同价并发只成交一笔；重试不重复扣钱 |
| 6 | 创建 Agent Token，让竞拍 Agent 参与 | 独立端口 `:8090`；尾段 20 秒被拒 |
| 7 | 查看成交、钱包与流水 | 结算一次；钱对得上 |
| 8 | 运行自动化测试 | 后端 + 前端 + 端到端脚本 |

### 第 1~2 步：起栈并确认初始化

```bash
cp .env.example .env && docker compose up -d --build
docker compose logs migrate | tail -5
```

**会看到**：迁移日志以 “Successfully applied N migrations” 或 “up to date” 收尾。
“初始化”就是这次迁移自带的种子：3 个演示账号（各 1000 积分）+ 1 件 `DRAFT` 演示拍品
`auc_demo_0001`（“演示拍品 · 复古机械键盘”，起拍 100、最小加价 10、时长 180 秒）。
它**有意不自动倒计时**（`ends_at` 为 NULL），等你点开始才进入倒计时。

### 第 3 步：管理员开始演示拍卖

浏览器（`admin@example.com` 登录）→ 侧边栏 **⚙ 运营台** → 那件 `DRAFT` 拍品的**「开始」**。命令行等价：

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin123456!"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

curl -s -X POST localhost:8080/api/v1/admin/auctions/auc_demo_0001/start \
  -H "Authorization: Bearer $TOKEN"
```

**会看到**：状态从 `DRAFT` 变 `RUNNING`，倒计时开始（180 秒），`seq` 前进一位。

### 第 4 步：两个浏览器用户加入并互相加价

1. 开一个**无痕窗口**，用 `bidder_a@example.com` 登录 → 进这场拍卖 → **加入本场** → 出价（点「+ 最小加价」最快）。
2. 再开一个无痕窗口，用 `bidder_b@example.com` 做同样的事，但出更高的价。

**会看到**：价格、领先者、剩余时间**实时**跳动（走 WebSocket，不是轮询）；`bidder_a` 的钱包出现**冻结**；
`bidder_b` 反超后，`bidder_a` 的冻结被**释放**，而 `bidder_b` 只冻结差额。命令行等价：

```bash
BT=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"bidder_a@example.com","password":"Test123456!"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
curl -s -X POST localhost:8080/api/v1/auctions/auc_demo_0001/join -H "Authorization: Bearer $BT"
curl -s -X POST localhost:8080/api/v1/auctions/auc_demo_0001/bids -H "Authorization: Bearer $BT" \
  -H 'Content-Type: application/json' -d '{"requestId":"demo-1","amount":110}'
```

`requestId` 是幂等键：把它连着发两次，第二次会返回**首次的结果**而不会再扣一次钱。

### 第 5 步：20 并发模拟脚本

```bash
python tools/auction_sim.py            # 八个阶段全跑（约 1 分钟）
python tools/auction_sim.py --quick    # 跳过最慢的狙击等待（省约 35 秒）
python tools/auction_sim.py --keep     # 结束后不取消拍卖，方便你在前端接着看
```

**会看到**：逐条打印“期望 vs 实际”，最后 `52/52 checks passed`。
它演的就是第 4 步的“20 个人同时抢”：**20 条同价并发只有一笔成交**、同一 `requestId` 并发 20 次只冻结一次、
最后 5 秒出价触发 +10 秒延时（最多 3 次）、断线重连的第一帧是权威快照、结算后钱包对得上。
（脚本把并发“打满”用的是种子账号，不是 20 个不同用户——公开 API 没有注册端点。这一点如实写在 [§11](#11-未完成边界如实声明)。）

> 脚本会**真的花钱**。连跑几轮后余额花光，之后的出价全是 `INSUFFICIENT_BALANCE`——
> 那是“数据用完了”，不是缺陷：跑 `db/reset_demo_data.sql` 复位（见 [§4.7](#47-停止--清库--复位演示数据)）。

### 第 6 步：创建 Agent Token，让竞拍 Agent 参与

普通用户不需要管理员：侧边栏 **🤖 我的 AI 代理 → 「新建授权」** 就能自助签发（等价于下面这条命令）。
（页面上的另一个入口是**托管 AI 代理**，见本节末。）

```bash
# 用第 4 步的 $BT，为 bidder_a 自己签发一枚「只能在这一场上出价」的令牌
curl -s -X POST localhost:8080/api/v1/me/agent-tokens -H "Authorization: Bearer $BT" \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo-agent","scopes":["auction:read","auction:bid"],
       "auctionIds":["auc_demo_0001"],"expiresAt":"2030-01-01T00:00:00Z","rateLimitPerMinute":600}'
# → data.token 就是 Agent Token；明文只在这里出现一次，库里只存 sha256 摘要
```

让一个“外部 Agent”用它参与（这就是原文“把 Token 交给 Coding Agent”的那条路径）：

```bash
export AUCTION_AGENT_TOKEN=<上一步的 data.token>      # PowerShell: $env:AUCTION_AGENT_TOKEN="..."
python tools/agent_sim.py --agent-only --auction-id auc_demo_0001 --bid
```

**会看到**：Agent 在**独立端口 `:8090`** 上读快照、出价成功，流水里的主体标成 AI（只有本人与管理员看得到）。
把它留到**最后 20 秒**再出价，会拿到 `403 HUMAN_ONLY_PERIOD`——这是产品规则（D-32，“博弈时间”只允许真人），
真人在这 20 秒里照常能出。不想写代码就用页面上的**托管 AI 代理**：选这场 + 填预算上限，
服务端会按最小加价跟价、触顶停手。

### 第 7 步：查看成交、钱包与流水

等倒计时结束（或管理员点取消）后：

- **拍卖详情页**：结果与成交价，成交主体带 `AI / 真人` 徽章。
- **我的资金**（侧边栏 **◎ 我的资金**）：流水里有每一笔冻结（`FREEZE`）、释放（`RELEASE`）、成交扣款（`SETTLE`）。
  对账口径：**总余额减少 = 冻结释放 = 成交价**。
- **运营台**（管理员）：该场全部资金流水与主体，以及全部 AI 代理总览。

命令行核对（结算后应是 `total = 原值 - 成交价`、`frozen = 0`）：

```bash
curl -s localhost:8080/api/v1/wallets/me -H "Authorization: Bearer $BT"
curl -s localhost:8080/api/v1/auctions/auc_demo_0001/result -H "Authorization: Bearer $BT"
```

### 第 8 步：运行自动化测试

```bash
# 后端：需要一个独立测试库（绝不能是开发库）
docker exec -i bid-arena-mysql-1 mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "CREATE DATABASE IF NOT EXISTS bid_arena_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   GRANT ALL PRIVILEGES ON bid_arena_test.* TO 'bid_arena'@'%'; FLUSH PRIVILEGES;"

export BID_ARENA_TEST_DB_URL='jdbc:mysql://localhost:3307/bid_arena_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8'
export BID_ARENA_TEST_DB_USER=bid_arena
export BID_ARENA_TEST_DB_PASSWORD=<你的 MYSQL_PASSWORD>
mvn clean verify          # 期望：242/242，BUILD SUCCESS

# 前端：不需要后端
cd frontend && npm ci && npm test && npm run typecheck
```

**会看到**：后端 `Tests run: 242, Failures: 0, Errors: 0`；前端 `73 passed`。
用例为什么这么分组、数字为什么可信，见 [§6](#6-测试与持续集成)。

### 卡住了看这里

| 症状 | 先看 | 原因与办法 |
|---|---|---|
| 停在 `migrate`，`backend` 没起 | `docker compose logs migrate` | 迁移失败。最常见是 `.env` 里 `MYSQL_PASSWORD` 与 `DB_PASSWORD` 不一致 |
| 启动报 `JWT_SECRET 必须在 .env 里显式配置` | `.env` | 没填，或不足 32 字节 |
| 端口被占用 | `docker compose ps` | 改 `.env` 的 `MYSQL_PORT` / `SERVER_PORT` / `WEB_PORT`（别动容器内的 3306/8080） |
| `:8088` 打不开但 `:8080/api/v1/health` 是 200 | `docker compose logs frontend` | 前端镜像或反代有问题；看 `frontend` 是否 Up |
| 改完 `.env` 不生效 | —— | 环境变量只在**创建容器**时注入：`docker compose up -d --force-recreate` |
| PowerShell 里连接串少了参数 | —— | PowerShell 不展开 `${}`；直接用 `.env.example` 里写好的具体值 |
| Windows 控制台中文乱码 | —— | `chcp 65001`，或直接看 `target/surefire-reports/` |

## 4. 部署教材

### 4.1 服务拓扑与端口

```text
                        浏览器
                          │  http://localhost:8088   ← 唯一入口
                          ▼
            ┌─────────────────────────────┐
            │ frontend (Nginx)            │
            │   /      → 前端静态产物      │
            │   /api/**→ backend:8080     │
            │   /ws/** → backend:18080    │
            └──────────────┬──────────────┘
                           │ compose 内部网络
            ┌──────────────▼──────────────┐      ┌───────────────┐
            │ backend（常驻）              │◀─────│ migrate（一次性）│
            │   :8080 用户/管理 HTTP       │ 退0  │ 跑 Flyway 后退出 │
            │   :8090 Agent HTTP（独立凭据）│ 才放行└───────┬───────┘
            │   :18080 WebSocket          │              │
            └──────────────┬──────────────┘              │
                           └───────────┬─────────────────┘
                                       ▼
                             mysql:3306（宿主机 3307）
```

| 端口 | 谁用 | 必须对外吗 |
|---|---|---|
| `8088`（`WEB_PORT`） | 浏览器 | **是**，唯一入口 |
| `8080` | 用户/管理 HTTP | 不必（前端反代）；发布只为本机 `curl` 与工具脚本 |
| `8090` | 竞拍 Agent API | 交给外部 Agent 时才需要。**刻意不经反代**（隔离爆炸半径，理由在 `frontend/nginx.conf` 顶部） |
| `18080` | WebSocket | 不必（前端反代）；本机工具直连才需要 |
| `3307`（`MYSQL_PORT`） | MySQL | 不必；**切勿暴露到公网** |

### 4.2 环境变量速查

完整清单与逐条解释在 [`.env.example`](.env.example)——**每一个后端会读的键都在那里**，
有测试守着不让它漏（见 [§6.3](#63-守卫与变异验证)）。这里只列你大概率想改的：

| 变量 | 默认 | 什么时候改 |
|---|---|---|
| `MYSQL_PASSWORD` / `JWT_SECRET` | `CHANGE_ME...` | **必改**。`JWT_SECRET` 至少 32 字节 |
| `MYSQL_PORT` | `3307` | 本机 3307 被占用时（容器内始终 3306） |
| `WEB_PORT` | `8088` | 8088 被占用时 |
| `SERVER_PORT` / `AGENT_SERVER_PORT` / `WS_PORT` | `8080` / `8090` / `18080` | 与别的服务撞端口时（三者必须互不相同） |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 指向 `localhost:3307/bid_arena` | **只在“后端不跑容器”时使用**；compose 里后端连的是 `mysql:3306` |
| `MIGRATE_ON_START` | `true` | 多实例/滚动发布设 `false`（只校验，库落后就拒绝启动） |
| 三个 `*_SCHEDULER_ENABLED` | 全 `true` | 多实例时关掉非后台实例，见 [§4.5](#45-多实例部署) |
| `AUCTION_FINAL_GAME_WINDOW_SECONDS` | `20` | 调整“尾段博弈时间”长度（Agent 在此期间一律被拒） |
| `AGENT_PROXY_TICK_INTERVAL_MS` | `500` | 想让 AI 跟价更灵敏、或更省数据库 |
| `CORS_ORIGINS` | `http://localhost:5173` | 只在**跨源**访问时需要（单 origin 下页面同源，不受影响） |
| `FRONTEND_NODE_IMAGE` / `FRONTEND_NGINX_IMAGE` | 官方镜像 | 内网/离线：换成自己的镜像源 |
| `BID_ARENA_TEST_DB_*` | 注释掉 | 只跑测试时需要（见 [§6.1](#61-一键测试命令)） |

> 布尔类开关（`MIGRATE_ON_START` 与三个 `*_SCHEDULER_ENABLED`）**只认 `true/false/1/0`**：
> 写成 `yes`/`on` 之类会直接启动失败，而不是悄悄按默认值跑——拼错必须立刻看得见。

### 4.3 常见部署形态

```bash
# ① 全栈（推荐；“评审五分钟跑起来”就是这条）
docker compose up -d --build               # → http://localhost:8088

# ② 只要数据库，后端在 IDE/命令行里跑（调后端最顺手）
docker compose up -d mysql

# ③ 起后端但不托管前端（前端用 npm run dev，改代码热更新）
docker compose up -d mysql migrate backend # → API :8080 / Agent :8090 / WS :18080

# ④ 只把库对齐，再决定要不要起应用
docker compose up --build migrate && docker compose logs migrate

# ⑤ 内网/离线：换基础镜像源（前端已用 ARG 暴露；后端改 Dockerfile 顶部两行 FROM）
#    .env 里写：FRONTEND_NODE_IMAGE=<镜像源>/node:22-alpine
#              FRONTEND_NGINX_IMAGE=<镜像源>/nginx:1.27-alpine
docker compose up -d --build
```

**用已有的外部 MySQL**（不想用 compose 里的 `mysql` 容器）：加一个 `docker-compose.override.yml`，
覆盖库连接（同名键以覆盖文件为准）：

```yaml
# docker-compose.override.yml
services:
  migrate:
    environment: &db-override
      DB_URL: jdbc:mysql://your-db-host:3306/bid_arena?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8
      DB_USER: bid_arena
      DB_PASSWORD: 你的口令
  backend:
    environment: *db-override
```

改完先 `docker compose config` 看渲染结果，再 `up`。两点提醒：① 本地 `mysql` 容器仍会被
`depends_on` 拉起（不想要就一并覆盖 `depends_on`）；② `DB_URL` 的时区参数
`connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` **不能省**，否则 TIMESTAMP 读写会漂移，
倒计时与结算时间跟着错。

### 4.4 迁移与初始化

- **迁移是一次性的一步**（D-39）：`migrate` 与 `backend` 用**同一个镜像**、只换 entrypoint
  （`com.bidarena.bootstrap.MigrateMain`），跑完退出；`backend` 依赖它**退出码为 0** 才启动，
  自身 `MIGRATE_ON_START=false`（只校验）。这样多实例不会同时抢迁移锁，滚动发布也不会出现
  “新实例改了 schema，旧实例还在跑旧代码”。
- **为什么不用 `docker-entrypoint-initdb.d`**：那个目录只在**空数据卷首次启动**时执行，
  后续版本的迁移根本跑不到（D-2/D-6）。
- **种子数据随迁移写入**：3 个演示账号 + 各 1000 积分 + 1 件 `DRAFT` 演示拍品（不自动倒计时）。
- **库落后于代码时 `backend` 会拒绝启动**并说明落后在哪——这是有意的：宁可拒绝服务，也不要带着错的 schema 跑。
- **回滚**：Flyway 不做自动回滚。真需要就准备反向 SQL 自己执行，并同步 `flyway_schema_history`。

### 4.5 多实例部署

三个后台扫描器（**到期结算 / 预告开拍 / 托管代理**）都只回数据库查“该做什么”，谁跑都不会算错；
但“能跑”不等于“应该跑”，所以每个都有独立开关（D-40，缺省全开）：

```dotenv
# 只对外服务的那个实例
SETTLE_SCHEDULER_ENABLED=false
AUCTION_START_SCHEDULER_ENABLED=false
AGENT_PROXY_SCHEDULER_ENABLED=false
```

关掉的项在该实例**静默不执行**（启动日志会 WARN 提醒）。它**不是**自动分工：
必须保证至少有一个实例负责每一项，否则到期结算/自动开拍/代理跟价会停摆。
为什么不做自动选主，见 [§11](#11-未完成边界如实声明)。

### 4.6 排错手册

```bash
docker compose ps                     # 谁 Up、谁退出了（含退出码）
docker compose logs -f backend        # 应用日志；启动时会打印“本实例负责哪些扫描器”
docker compose logs migrate           # 迁移结论：成功 / 失败在哪一版
docker compose logs mysql | tail -20  # 初始化与连接问题
docker compose exec backend env | grep -E 'DB_|MIGRATE|SCHEDULER'   # 容器里实际生效的配置
curl -s localhost:8080/api/v1/health  # 应用活着吗
```

| 症状 | 先看 | 常见根因 |
|---|---|---|
| 整栈起不来 | `logs migrate` | 库口令不匹配 / `JWT_SECRET` 没填 / 旧数据卷与迁移历史冲突 |
| `backend` 反复重启 | `logs backend` | 端口被占、库连不上、库版本落后（会被明确拒绝） |
| 出价一直 `BID_TOO_LOW` | 前端显示的价格 | 别人先出价了；或 `requestId` 被复用（幂等返回首次结果） |
| 出价 `INSUFFICIENT_BALANCE` | `GET /wallets/me` | 演示数据花完了 → 跑 `reset_demo_data.sql` |
| AI 代理不动 | `logs backend` | 尾段 20 秒按规则拒绝 Agent；或已触预算上限；或它本来就是领先者 |
| 页面一直“重连中” | 浏览器控制台 | WS 不通（单 origin 下应走同源 `/ws`） |

### 4.7 停止 / 清库 / 复位演示数据

```bash
docker compose stop                 # 停服务，保留数据
docker compose down                 # 删容器与网络，保留数据卷（mysql_data）
docker compose down -v              # 连数据卷一起删：下次 up 会重新迁移 + 重放种子

# 复位演示数据：清空竞拍相关表、钱包写回 1000/0、重建那件 DRAFT 拍品（users 保留）
# 口令从容器自己的环境变量里取，不必在宿主机上写明文
docker compose exec -T mysql sh -c \
  'mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
  < db/reset_demo_data.sql
```

两个细节都是踩过的坑：`--default-character-set=utf8mb4` 不能省（脚本含中文，客户端默认字符集若是 latin1 会直接报语法错）；
不要把 `.env` 用 `. ./.env` 读进 shell（`DB_URL` 里的 `&` 会被当控制符），要么让容器自己取，要么手打口令。
脚本只动数据，不改 schema、不碰 `flyway_schema_history`，最后回显钱包与拍品供你核对。

## 5. 本地开发（源码方式，不用 Docker 跑应用）

需要 JDK 17、Maven 3.9+、Node 20+。数据库仍建议用容器（一条命令，省得装 MySQL）：

```bash
docker compose up -d mysql && docker compose logs mysql | tail -3
```

### 5.1 后端

后端只读环境变量，**不读 `.env` 文件**，要把值导进当前 shell（`DB_URL` 里的 `&` 不能直接 `source`，
逐行 `export` 最稳）：

```bash
export DB_URL='jdbc:mysql://localhost:3307/bid_arena?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8'
export DB_USER=bid_arena DB_PASSWORD='你的口令' JWT_SECRET='至少32字节'
mvn -q compile && mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/libs
java -Dfile.encoding=UTF-8 -cp "target/classes;target/libs/*" com.bidarena.Application
# macOS/Linux：类路径分隔符用 ':' → "target/classes:target/libs/*"
```

**会看到**：8080（HTTP + WS）、8090（Agent API）、18080（WebSocket）各自就绪，Flyway 打印
`up to date（当前版本 6）`，并列出本实例负责的后台扫描器。停止用 `Ctrl+C`。

> 注意：`target/classes` 与 `target/libs` 是 Maven 的输出目录，**跑着后端时 `mvn clean` 删不掉**
> 它们，会导致变异脚本之类的“先改文件再编译”流程整体失效（见 `DEBUG_LOG.md` DBG-32）。
> 要跑 `mvn clean verify` 请先停后端。

### 5.2 前端

```bash
cd frontend
npm ci
npm run dev          # → http://localhost:5173 ，/api 代理到 localhost:8080
```

dev 端口与代理目标可覆盖：`VITE_DEV_PORT`（默认 5173）、`VITE_DEV_API_TARGET`（默认 `http://localhost:8080`）。
`VITE_WS_SAME_ORIGIN=1` 时前端走同源 `/ws`（容器里的前端就是这样构建的）；本机 dev 默认按票据里返回的 `wsPort`
直连 18080。

### 5.3 前端联调测试

```bash
cd frontend && npm run test:live    # 需要后端已在 8080 跑起来：3/3
```

## 6. 测试与持续集成

结论先给：**后端 242/242、前端 73、端到端脚本 44/44 与 52/52、架构守卫 9/9、压测 11/11**，
全部在真实 MySQL 上跑过（不是 H2、不是 mock）。

### 6.1 一键测试命令

| 范围 | 命令 | 期望 |
|---|---|---|
| 后端 | `mvn clean verify`（先按 [第 8 步](#第-8-步运行自动化测试) 设好 `BID_ARENA_TEST_DB_*`） | `Tests run: 242, Failures: 0, Errors: 0` |
| 前端 | `cd frontend && npm test` | `73 passed`（+ `npm run typecheck` 干净） |
| 端到端 | 后端起来后 `python tools/agent_sim.py` / `python tools/auction_sim.py` | `44/44` / `52/52 checks passed` |
| 压测 | `python tools/stress_test.py --mode game-window -c 100` | `11/11 checks passed`（尾段 100 个 Agent 全被 403 拒绝） |
| 压测 | `python tools/stress_test.py --mode throughput -c 50 --seconds 10` | 实测 ~410 QPS、P50≈109ms / P95≈243ms / P99≈315ms、**0 个 5xx** |
| 变异 | `python tools/agent_mutation_check.py` / `tools/arch_mutation_check.py` / `tools/mutation_check.py` | `14/14` / `9/9` / `16/16 KILLED` |

测试库必须**独立于开发库**：`BID_ARENA_TEST_DB_URL` 指向 `bid_arena_test`，
测试基座会自己推倒重建它；它还会从 `DB_URL` 反推开发库名，一旦发现你指着开发库就直接拒绝运行（防误清）。

### 6.2 后端 242 个用例怎么构成

| 分组 | 数量 | 守的是什么 |
|---|---|---|
| 真库集成 | 90 | HTTP 21 + WebSocket 14 + Agent 29 + 拒绝后连接复用 2 + 托管代理与预告开拍 19 + 迁移开关 3 + 扫描器真库对照 2（共用同一个自启动服务实例） |
| 架构守卫 | 9 | 分层/跨上下文/无环：domain 不依赖框架、adapter 不互相调、包之间无环 |
| 扫描器开关纯策略 | 4 | 缺省全开 / 单项关闭 / 拼错报错 / 与 `.env.example` 一致 |
| 配置键守卫 | 3 | 扫源码里每个 `Env.*("KEY")`，断言 `.env.example` 里有它（反向断言防“守卫真空”） |
| 其余单元 | 136 | 领域规则、身份、结算、事件、WS 广播、票、匿名、Agent、迁移开关、编排自检 |

### 6.3 守卫与变异验证

只写“正确的话能被通过”的测试是不够的，所以关键守卫都做了**变异验证**：改坏一行代码，
测试必须变红；把改动还原，必须变绿。三个脚本合计 39 个变异体，目前全部 KILLED——
这证明测试不是“恒真断言”凑数。

其中最容易被忽略的一条：**“代码会读的配置键 ⊆ `.env.example` 写明的键”**。
新增 `EnvDocumentationTest` 之前，已经有 4 个真实键（`AGENT_SERVER_HOST`、`JWT_TTL_SECONDS`、
`DB_CONNECTION_TIMEOUT_MS`、`DB_MAX_LIFETIME_MS`）处在“代码读、文档没写”的漂移状态；
现在是会失败的断言（D-41）。这道守卫自己也有反向断言：往 `.env.example` 里塞一个只有注释、
没有赋值行的键，它必须报缺失。

### 6.4 CI（五个 job，失败含义各自独立）

[`.github/workflows/ci.yml`](.github/workflows/ci.yml)，每次推 `main` 或提 PR 都跑：

| job | 内容 | 失败说明什么 |
|---|---|---|
| `backend` | 一次性 MySQL 8.4 服务容器 + `mvn clean verify`，并断言总用例数 ≥ 242 | 后端逻辑/真库并发回归，或用例数被悄悄删少 |
| `frontend` | `npm ci` → `typecheck` → 73 单测 → `VITE_WS_SAME_ORIGIN=1` 构建 | 前端类型、状态机或构建坏了 |
| `e2e` | 真起 8080/8090/18080 跑 `agent_sim.py` 44/44，复位数据后再跑 `auction_sim.py` 52/52 | 真实链路（含 Agent 鉴权与并发）坏了 |
| `config` | 全部工具脚本 `py_compile` + `docker compose config -q` + `nginx -t` | 编排/反代配置或脚本语法错了 |
| `images` | `docker compose build` → 断言镜像里有产物 → `docker compose up -d` 起整栈，验 `:8080` 健康端点与 `:8088` 静态页/`/api` 反代，并断言 `migrate` 退出码 0 | **交付形态**坏了（镜像构建、迁移步骤、反代） |

CI 里跑过一次的实证：`images` job 连挂三轮，挖出两个真问题——`migrate` 的 entrypoint 少写一层包名
（`com.bidarena.MigrateMain`，容器 `ClassNotFoundException` 退出，compose 于是在依赖条件上放弃整个 `up`），
以及 MySQL healthcheck 用 `-h localhost` 走 unix socket、在“3306 还没监听”的窗口里误报健康
（迁移一上来就 `Connection refused`）。两个都已修，并各自补了守卫（`ComposeEntrypointTest`、
TCP healthcheck 注释）。详见 `DEBUG_LOG.md` DBG-33 / DBG-34。

**压测与变异检查有意不进 CI**：共享 runner 上 QPS 不可比；变异要反复改文件跑 Maven，
属于“提交前自检”。所以文档里的 242/73/44/52 以本地实跑为准，CI 保证的是
**同一套命令在干净机器上同样全绿**。

## 7. 工具脚本（`tools/`）

只用 Python 标准库（含一个手写的 RFC 6455 客户端），不需要 `pip install`。

| 脚本 | 用途 | 典型命令 |
|---|---|---|
| `auction_sim.py` | 全链路模拟：20 条并发同/邻价、幂等重试、拒绝场景、最后五秒狙击、WS 断线快照、结束核对 | `python tools/auction_sim.py`（`--quick` / `--keep` / `--base`） |
| `agent_sim.py` | Agent 视角：登录 → 建场开拍 → 签发多枚 Token → 读/出价/重放 → 逐条验证 401/403/429/404 与端口隔离 | `python tools/agent_sim.py`（`--duration` / `--agent-only --auction-id <id>` / `--skip-boundary`） |
| `stress_test.py` | 尾段博弈时间压测 / 吞吐与延迟分位 | `--mode game-window -c 100`、`--mode throughput -c 50 --seconds 10` |
| `preconditions.py` | 上面三个脚本共用的**阶段 0 前置检查**：余额不够就直接停并指向复位脚本 | 被自动调用 |
| `agent_credentials.py` | Agent 凭据只从**显式参数或 `AUCTION_AGENT_TOKEN`** 读，不做交互输入；缺失就打印“怎么拿到”并退 2 | 被自动调用 |
| `agent_mutation_check.py` / `arch_mutation_check.py` / `mutation_check.py` | 变异验证（后端 Agent / 架构规则 / 前端） | 见 [§6.1](#61-一键测试命令) |

演示账号可以不改脚本就换人：读环境变量 `BID_ARENA_DEMO_ADMIN_EMAIL` / `BID_ARENA_DEMO_ADMIN_PASSWORD` 等，
默认值就是种子账号。退出码约定：`0` 全通过、`1` 有检查失败、`2` 缺数据/缺参数（不是缺陷，是让你先复位）。

## 8. API 与实时通道（速览）

契约以 [`docs/openapi.yaml`](docs/openapi.yaml) 为准（前端类型就是从这里生成的，D-26）。
鉴权一律 `Authorization: Bearer <JWT>`；写接口需要幂等键（`requestId` 或 `Idempotency-Key` 头，两者都给必须一致）。

<details>
<summary><b>点开：主要接口一览</b></summary>

**鉴权只有两条白名单**：`POST /api/v1/auth/login` 与 `GET /api/v1/health`；其余 `/api/v1/**` 一律要 JWT
（Agent 那组走独立端口 + Agent Token）。**没有注册端点**——账号来自迁移种子，见 [§11](#11-未完成边界如实声明)。

| 方法 | 路径 | 谁 | 说明 |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | 公开 | 登录 → `data.accessToken` |
| `GET` | `/api/v1/health` | 公开 | 健康检查（探活不依赖登录） |
| `GET` | `/api/v1/users/me` | 登录 | 当前用户与角色 |
| `GET` | `/api/v1/auctions` | 登录 | 拍卖列表（大厅） |
| `GET` | `/api/v1/auctions/{id}` | 登录 | 快照：`currentPrice`、`leader`、`seq`、`status`、`serverTime`+`endsAt`、`participantCount` |
| `POST` | `/api/v1/auctions/{id}/join` | 登录 | 加入本场（未加入不能出价） |
| `GET` | `/api/v1/auctions/{id}/bids` | 登录 | 出价记录（分页） |
| `POST` | `/api/v1/auctions/{id}/bids` | 登录 | 出价，体 `{requestId, amount}` |
| `GET` | `/api/v1/auctions/{id}/result` | 登录 | 成交结果；`winnerType`（AI/真人）是隐私，只对赢家本人与管理员返回，其他人恒为 `null` |
| `POST` | `/api/v1/auth/ws-tickets` | 登录 | 换一次性 WS 票据（体 `{auctionId}` → `ticket` / `wsPort`） |
| `GET` | `/api/v1/wallets/me` | 登录 | 我的余额与冻结 |
| `GET` | `/api/v1/wallets/me/ledger` | 登录 | 我的流水（`FREEZE`/`RELEASE`/`SETTLE`） |
| `GET`/`POST` | `/api/v1/me/agent-tokens` | 登录 | 自助列出/签发 Agent Token（`auctionIds` 省略 = 默认拒绝） |
| `POST` | `/api/v1/me/agent-tokens/{id}/revoke` | 登录 | 吊销 |
| `GET`/`POST` | `/api/v1/me/agent-proxies` | 登录 | 托管 AI 代理：列表 / 创建（体 `{auctionId, budgetLimit}`） |
| `POST` | `/api/v1/me/agent-proxies/{id}/revoke` | 登录 | 撤销（位置释放） |
| `POST` | `/api/v1/admin/auctions` | 管理员 | 创建（可带 `startsAt` 预告开拍） |
| `POST` | `/api/v1/admin/auctions/{id}/start` | 管理员 | 开始（种子里那件必须手动开始） |
| `POST` | `/api/v1/admin/auctions/{id}/cancel` | 管理员 | 取消（释放全部冻结） |
| `GET` | `/api/v1/admin/auctions/{id}/ledger` | 管理员 | 该场全部资金流水与主体 |
| `GET`/`POST` | `/api/v1/admin/agent-tokens` | 管理员 | 列出全部 / 签发给指定用户 |
| `POST` | `/api/v1/admin/agent-tokens/{id}/revoke` | 管理员 | 吊销 |
| `GET` | `/api/v1/admin/agent-proxies` | 管理员 | 全部托管代理总览（运营台） |
| `GET` | `/api/v1/agent/auctions/{id}` | Agent | **都在 `:8090` 上**，用 Agent Token 读快照 |
| `POST` | `/api/v1/agent/auctions/{id}/bids` | Agent | 用 Agent Token 出价（同一套并发与幂等规则） |
| `GET` | `/api/v1/agent/auctions/{id}/result` | Agent | 读结果 |

</details>

<details>
<summary><b>点开：WebSocket 怎么接</b></summary>

1. `POST /api/v1/auth/ws-tickets`（体 `{"auctionId":"..."}`）拿一次性票据，响应里有 `ticket` 与 `wsPort`。
2. 连 `ws://<host>:<wsPort>/?auctionId=...&ticket=...`（单 origin 前端走同源 `/ws`）。
3. 服务端推的每条消息都带递增 `seq`；客户端**只看 `seq` 是否连续**：缺号就重新拉一次 `GET /auctions/{id}`
   快照，不做增量猜测（D-28）。票据是一次性的、60 秒有效（`WS_TICKET_TTL_SECONDS`）。

票据走 query 参数、而不是长连接里再鉴权，是为了让鉴权在**连接建立前**完成；
客户端发来的任何消息一律忽略（服务端只推不收），避免把长连接变成第二套 API。

</details>

## 9. 项目结构

```text
bid-arena/
├── src/main/java/com/bidarena/
│   ├── auction/        # 拍卖：出价、结算、状态机、定时任务（domain/application/adapter/persistence）
│   ├── identity/       # 账号、登录、JWT、WS 票据
│   ├── wallet/         # 钱包、冻结/释放/扣款、资金流水
│   ├── agentaccess/    # Agent Token、Agent API、托管 AI 代理
│   ├── bootstrap/      # 启动装配：Env（读环境变量）、Services、ScannerBootstrap、MigrateMain
│   ├── shared/         # 错误码、统一响应封套、业务异常
│   └── api/            # 出价入口共用的幂等键解析等
├── src/test/java/com/bidarena/   # 单元 + 真库集成 + architecture/ 架构守卫
├── frontend/           # Vue 3 + TS + Pinia；src/api/schema.d.ts 由 openapi.yaml 生成
├── db/migration/V1~V6  # Flyway 迁移（含种子）；db/reset_demo_data.sql 复位演示数据
├── tools/              # 模拟、压测、变异验证脚本（纯标准库）
├── docs/               # openapi.yaml、STATUS、TRACEABILITY、DOCS、演示脚本
└── docker-compose.yml  # mysql / migrate / backend / frontend
```

## 10. 文档地图

| 文件 | 讲什么 | 什么时候看 |
|---|---|---|
| `README.md` | 快速启动、八步教程、部署教材、测试证据 | 你在这里 |
| [`docs/STATUS.md`](docs/STATUS.md) | 逐项交付清单、当前进度、已知边界 | 想知道“做到哪一步了” |
| [`DECISIONS.md`](DECISIONS.md) | **42 条决策**：背景、候选方案、为什么选它、代价、验证结果（含未采用方案汇总） | 想知道某个设计为什么长这样 |
| [`DESIGN.md`](DESIGN.md) | 架构分层、并发与幂等的实现路径、数据模型 | 想改代码 |
| [`DEBUG_LOG.md`](DEBUG_LOG.md) | **34 条**真实缺陷：现象、根因、修法与守卫 | 想找“这类坑怎么防” |
| [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md) | 原文要求 ↔ 实现 ↔ 测试证据的对照表 | 想核对“哪条要求由哪个用例守着” |
| [`docs/openapi.yaml`](docs/openapi.yaml) | 接口契约（前端类型的来源） | 写客户端 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 提交前自检、密钥与数据规范、录屏分镜、现场核验演练 | 你要提交代码 |
| [`AI_USAGE.md`](AI_USAGE.md) | AI 参与方式与人工复核点 | 想知道哪些是机写、谁审的 |
| [`docs/DOCS.md`](docs/DOCS.md) | 文档本身的维护规则（谁说什么、改哪个文件） | 改文档前 |

## 11. 未完成边界（如实声明）

写清楚没做的部分，比含糊过去更省双方时间：

1. **没有自动选主/分片**：多实例下后台扫描器的开关要人工设（[§4.5](#45-多实例部署)），
   没有租约或选主机制。
2. **没有注册/找回口令**：账号只来自迁移种子（公开 API 里只有 `login`，删除或新增用户目前要直接写库）。
   要多人演示就预先在种子里加账号，或用同一个账号在多窗口跑。
3. **没有支付网关**：钱包是积分账本，不对接真实资金；没有退款流程（取消拍卖是释放冻结，不是退款）。
4. **没有图片存储**：拍品只有文字描述，没有上传/对象存储/CDN。
5. **没有管理后台的账号 CRUD**：管理员能做拍卖与授权，不能停用/删除用户。
6. **端到端脚本的并发是“同账号高并发”**：公开 API 无批量建号手段，所以 20 并发是同一批种子账号打满，
   不是 20 个真实不同用户；并发正确性的断言（只成交一笔、只冻结一次）不受影响。
7. **单机部署规模**：压测数字（~410 QPS / P95≈243ms）来自本机单实例 + 单 MySQL，
   不代表横向扩展后的容量。
8. **评测机上没跑过 `docker compose up --build`**：那台机器的 Docker daemon 在远程且连不上 Docker Hub
   （本地镜像源也没有 node/maven 基础镜像），所以按约束只做了“只读 Docker 检查”。
   镜像构建与整栈启动由 CI 的 `images` job 每次提交真跑一遍（[§6.4](#64-ci五个-job失败含义各自独立)）。
   受限网络下前端基础镜像可用 `FRONTEND_NODE_IMAGE` / `FRONTEND_NGINX_IMAGE` 替换，
   后端需改 `Dockerfile` 顶部两行 `FROM`。
9. **H 组“现场核验”与演示视频**（原文的两项“讲清 / 演示”要求）：稿子已备好照着走就行——
   录屏分镜在 `CONTRIBUTING.md` §9.1.1，现场讲解与四个 drill、四个临时变更 playbook 在 §9.4。
   视频与现场演示**不能替代**代码、测试、Git 与文档核验。

## 12. 公开仓库约定

- **分支保护**：`main` 禁止强推与删除；提交历史上每一条都能对上一个 CI run。
- **密钥不入库、不入镜、不入日志**（`CONTRIBUTING.md` §8.3）：仓库里只有 `.env.example`，`CHANGE_ME` 是占位符；
  真实口令 / `JWT_SECRET` / Agent Token 明文只存在本地 `.env` 或 CI Secrets；日志里不打印它们。
  Agent Token 库里只存 sha256 摘要，明文只在签发响应里出现一次；模拟脚本只从 `AUCTION_AGENT_TOKEN` 读，不写进命令历史或仓库。
- **可复现**：所有“做到了”都配了可跑的命令或测试；文档里每个数字都能用 [§6.1](#61-一键测试命令) 重跑一遍。
- **提交规范与自检**见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。
