# 项目进度看板

> 本文件是仓库**唯一的进度看板**。每次提交前必须更新。
> 阶段划分依据 `DESIGN.md`，验收项编号依据 [`TRACEABILITY.md`](TRACEABILITY.md)。
> 图例：⬜ 未开始　🟨 进行中　✅ 完成　⛔ 阻塞

## 1. 阶段进度

| 阶段 | 内容 | 状态 | 备注 |
|---|---|---|---|
| P0 | 文档与契约整理 | ✅ | 文档地图、控制器、设计文档重写、契约修正、技术选型均已完成 |
| P1 | 持久化：迁移 + Repository + 事务型领域服务 | ✅ | V1~V3 迁移与种子、按场冻结、Repository、事务型出价服务、结算服务 + 到期扫描器；32 个真实 MySQL 集成测试（INV-1~4 ✅） |
| P2 | HTTP API + 鉴权 + RBAC + 统一响应 | ✅ | 14 个接口 + JWT/BCrypt + 默认拒绝鉴权 + RBAC + 统一封套与错误码 + CORS 白名单；31 个 P2 用例（HTTP 19 / 身份 10 / 种子口令 2），全量 63/63 绿（`mvn clean verify`） |
| P3 | WebSocket + seq/快照恢复 | ✅ | 事件契约见 [`docs/REALTIME_AND_COMMAND_FLOW.md`](REALTIME_AND_COMMAND_FLOW.md)；`POST /auth/ws-tickets` 一次性票（60s、单次、有容量上限）+ `/ws/auctions/{auctionId}` + 7 类事件（含可见范围）+ 提交后发布（A8）；P3 新增 53 个用例，全量 **116/116** 绿（`mvn clean verify`） |
| P3.5 | 架构守卫：出站适配器独立成 `persistence` 包 + ArchUnit 九条规则（D-6 验证） | ✅ | 出站 JDBC 仓储独立成 `<ctx>.persistence`、视图归 `<ctx>.application`、`ApiTime`/`PageQuery` 归 `shared`（D-24、D-25）；`ArchitectureTest` 九条规则全绿，`tools/arch_mutation_check.py` 注入九种违规 **9/9 KILLED**；全量 **125/125** 绿 |
| P4 | 前端接入真实 HTTP/WS，替换 Mock | ✅ | 类型由契约生成（D-26）；类型化 HTTP 客户端 + 实时订阅状态机 + Pinia store 接上真实服务，界面不再本地算钱/倒计时（D-27/D-28）。**59 个单测**（P6 后累计 63） + **3 个真后端联调**（HTTP/WS）+ **16 个变异 16/16 KILLED**；`npm run typecheck` 与 `vite build` 通过 |
| P5 | Agent API（:8090）+ 模拟脚本 + Compose/E2E | ✅ | 独立端口（`AgentApiPlugin`，只暴露 `/api/v1/agent/**`）+ 独立 Token（签发/范围/权限/过期/吊销/限流）+ 复用同一出价事务并事务内自动加入（D-30）；P5 新增 **62 个用例**，全量 **187/187** 绿；`tools/agent_mutation_check.py` **14/14 KILLED**；端到端模拟 `tools/agent_sim.py`；后端 Dockerfile + Compose `backend` 服务（配置已校验，镜像未在本机构建，见 §3） |
| P6 | 交付收尾：README 边界、录屏、测试证据 | 🟨 | E1 全链路模拟 `tools/auction_sim.py` 已交付并实跑 **52/52**（并发同/邻价、幂等重试、拒绝场景、最后五秒狙击、断线快照、结束核对）；另交付尾段“博弈时间”（D-32，最后 20 秒强制拒绝 Agent 出价）与成交主体标识/隐私遮蔽（D-33），并交付压力/手动测试工具 `tools/stress_test.py`；再把 Agent 授权从运营动作改为用户自助（D-34）：后端新增 `/me/agent-tokens` 三端点 + `/admin/agent-tokens` 总览，前端把“智能体接入”整页换成**“我的 AI 代理”**（授权列表 / 新建 / 一次性明文 / 接入指引 / 运营总览）；最后把 AI 真正做成普通用户可用的产品功能（D-35/D-36）：拍品可预告 `starts_at` 并由 `AuctionStartScheduler` 到点自动开拍，侧边栏“AI 代理”页可直接**创建托管代理**（选进行中/未开拍的场次 + 设定预算上限），`AgentProxyScheduler` 到点自动进场并按最小加价跟价、触顶停手；后端 **225/225** 绿，前端 **69** 单测绿；README 已补 Agent/E2E 路径与更新后的未完成边界；`AI_USAGE.md` 已填写（G1 ✅）；录屏（G7）仍待作者录制 |

## 2. 必交文档状态

| 文档 | 要求 | 状态 |
|---|---|---|
| `README.md` | 快速启动完整路径、演示账号、未完成边界 | 🟨 已补「竞拍 Agent API（:8090）」与 E2E 模拟脚本路径、Compose 后端服务；未完成边界已按 P5 更新 |
| `DESIGN.md` | 架构边界、出价事务、结算、恢复 | ✅ 已同步 §1.6（V1~V6、Agent 边界、尾段博弈时间与成交主体、预告开拍与托管代理、225 证据与“尚未实现”）、§2.2~§2.4（分层与依赖规则）、§4（流水主体）、§8（验证重点）；P5 的 Agent 边界与 P6 的博弈时间/主体标识/托管代理已补入 |
| `DECISIONS.md` | ≥3 项决策（背景/候选/选择/代价/验证） | ✅ 已写 D-1~D-36（含背景/候选/选择/代价/验证结果）；D-9 的验证结果已随 P5 补全；P5 新增 D-29（范围缺省即拒绝）、D-30（复用同一出价事务）；E1 阶段新增 D-31（幂等键颗粒度含 `user_id`）；P6 新增 D-32（尾段博弈时间清场 Agent，有意偏离原文规则 6）、D-33（成交主体标识与隐私遮蔽）、D-34（Agent 授权自助化）、D-35（预告开拍 `starts_at` + 到点自动开拍）、D-36（服务端托管 AI 代理） |
| `AI_USAGE.md` | AI 分工、本人决定、未采用方案、真实错误 | ✅ 已填写（§1~§6 无占位）：工具与模型（`pi` + `deepseek-v4-flash`，可由 `PI_*` 自证）、各模块人机分工与口径、四项本人设计决定（D-32/D-33/D-36/D-5）、六项未采用方案及否决依据、四项真实错误（DBG-30/DBG-29/DBG-27 + 两条未入 DEBUG_LOG 的补充）、七类尚不能独立解释/修改的代码；文末留三项「作者核对清单」（模型列表完整性、比例口径、决定归属） |
| `DEBUG_LOG.md` | ≥2 个真实问题（现象/日志/定位/修复/验证） | ✅ 已写 30 条真实问题（DBG-8~12 来自 P2；DBG-13~17 来自 P3；DBG-18~20 来自架构守卫；DBG-21 来自 P4 前端变异 F14 存活；DBG-22~26 来自 P5：Solon 单例启动、提前拒绝后的连接错位、base64url 填充位、增量编译旧字节码、E2E 脚本自己的断言用错凭证；DBG-27~28 来自 E1 全链路模拟：断言比契约强、幂等键含 `user_id`；DBG-29 手工测试时发现前端仍声称 Agent API 未实现；DBG-30 手工核验托管代理时发现数据库容器时钟慢 3 分钟） |
| `AGENT_TOOL_SPEC.md` | 评审如何用 Token 查询与出价 | ✅ 已从“骨架/尚不可用”更新为 P5 已实现并验证：操作步骤、提示词模板、失败边界、验收清单已勾选并登记证据（对应 `tools/agent_sim.py`） |
| `docs/openapi.yaml` | 覆盖原文要求的能力 | ✅ 已修正；P2 已按实现回填 `ErrorCode`、分页、`Bid`、`LedgerEntry.requestId`；P3 补上 `WsTicket.wsPath/wsPort` 与 `/auth/ws-tickets` 的 429；P4 前端类型已由 `npm run gen:api` 从本文件生成（`frontend/src/api/schema.d.ts`，D-26）；P6 补上 `HUMAN_ONLY_PERIOD`、`AuctionResult.winnerType`、`LedgerEntry.actorType`、`AuctionSnapshot.finalGameWindowSeconds` 与 `/admin/auctions/{auctionId}/ledger`，以及 `/me/agent-proxies`（get/post）、`/me/agent-proxies/{proxyId}/revoke`、`/admin/agent-proxies` 与 `AuctionSnapshot.startsAt` / `CreateAuctionRequest.startsAt` |

## 3. 契约与基础设施状态

| 项 | 状态 | 缺口 |
|---|---|---|
| `docs/openapi.yaml` | ✅ | 已补齐 Agent result、Token 吊销、Agent server、`agentUserId`、`AuctionResult`；P3 补上 `WsTicket.wsPath/wsPort` 与 429；P6 再补 `/admin/auctions/{id}/ledger`、`HUMAN_ONLY_PERIOD`、`ActorType` 与 `/me/agent-tokens`（get/post）、`/me/agent-tokens/{tokenId}/revoke`、`/admin/agent-tokens`、`/me/agent-proxies`（get/post）、`/me/agent-proxies/{proxyId}/revoke`、`/admin/agent-proxies`（共 28 个操作，其中 24 个已实现）；前端类型已由 `npm run gen:api` 生成（`frontend/src/api/schema.d.ts`，D-26） |
| `docs/REALTIME_AND_COMMAND_FLOW.md` | ✅ | 无缺口。内容：命令行路径与一致性、事件信封（含 `seq` 归属）、事件类型与可见范围、匿名标识、WS 接入与握手、序号恢复、广播失败边界、可观测性（共 8 节） |
| `db/migration/` | ✅ | V1~V6 已在空库上完整执行并验证；含 `users` / `wallets` / `ledger_entries` 与 `auctions` 新列、`auction_participants.frozen_amount`；V5 补 `bids.actor_type` / `settlements.winner_type` / `ledger_entries.actor_type`（主体标识与按场次流水索引）；V6 补 `auctions.starts_at`（预告开拍）与 `agent_proxies`（托管代理，含 `uk_proxy_owner_auction`） |
| `pom.xml` | ✅ | 服务器/WebSocket/序列化/连接池/MySQL/Flyway/鉴权/ArchUnit/测试依赖齐备，已验证可启动；`db/migration` 经 `<resources>` 映射为 `classpath:db/migration`；已排除 `solon-web` 传递进来的 snack3，保证序列化器唯一（DBG-11） |
| `src/main/resources/` | ✅ | `app.yml` 只留 `server.port: ${SERVER_PORT:8080}` 与 `server.websocket.port: ${WS_PORT:18080}`；数据源/迁移/JWT/CORS/结算参数/WS 票参数统一经 `bootstrap/Env` 读环境变量，端口覆盖方式与陷阱见 DECISIONS D-17、DEBUG_LOG DBG-10 |
| `.env.example` | ✅ | 已补 `DB_URL` / `DB_USER` / `DB_PASSWORD` / `JWT_SECRET` / `CORS_ORIGINS` / 端口（含 `WS_PORT`）/ `SETTLE_SCAN_INTERVAL_MS` / `SETTLE_BATCH_SIZE` / `WS_TICKET_TTL_SECONDS` / `WS_TICKET_CAPACITY` / `AUCTION_FINAL_GAME_WINDOW_SECONDS` / `AUCTION_START_SCAN_INTERVAL_MS` / `AUCTION_START_BATCH_SIZE` / `AGENT_PROXY_TICK_INTERVAL_MS` / `AGENT_PROXY_BATCH_SIZE`，并对时区、认证插件、为何票要短、为何博弈时间取 20 秒、为何扫描间隔取这个量级加注释 |
| `docker-compose.yml` | 🟨 | 已含 `mysql`（含 healthcheck）与 P5 新增的 `backend` 服务（依赖 mysql 健康、注入 DB/JWT/三个端口）；`docker compose config` 已校验通过。**镜像未在本机构建**：按本章 C-6 不对 VM 容器做重建操作，构建命令已写入 README |
| Dockerfile | 🟨 | 后端：多阶段构建（Maven + JDK17 → JRE，非 root，`java -cp app.jar:libs/*`，无 fat jar 故显式拷依赖）；已配 `.dockerignore`。未在本机构建验证 |
| 种子数据 | ✅ | 3 个演示账号（BCrypt 实测可登录）+ 各 1000 积分钱包 + 1 件 `DRAFT` 演示拍品（`ends_at` 为 NULL，不自动倒计时） |
| 开发库容器 | ✅ | VM 上 `bid-arena-mysql-1`（MySQL 8.4.9，`0.0.0.0:3307->3306`），未动其他 18 个容器 |
| 架构守卫 | ✅ | `src/test/java/com/bidarena/architecture/ArchitectureTest.java`：九条分层/跨上下文/无环规则（`DoNotIncludeTests`，不连库、秒级）；`tools/arch_mutation_check.py` 逐条注入真实违规反向确认，**9/9 KILLED**（`mvn` 需能离线跑） |
| Agent 接入 | ✅ | `agentaccess` 上下文：`AgentToken`/`AgentScopes`（domain）、`AgentTokenService`/`AgentAuctionService`/`AgentRateLimiter`（application）、`AgentAuthFilter`/`HttpAgentController`/`HttpAgentTokenController`/`HttpAgentProxyController`（adapter）、`AgentTokenRepository`/`TokenSummaryRow`/`AgentProxyRepository`（persistence）；`bootstrap/AgentApiPlugin` 在 `:8090` 只暴露 `/api/v1/agent/**`；授权接口既有管理员签发/吊销，也有用户自助的 `/me/agent-tokens`（列表/签发/吊销，归属由服务层钉死，D-34）；托管代理见 `AgentProxyService`/`AgentProxyScheduler` 与 `/me/agent-proxies`（D-36）；预告开拍见 `AuctionStartScheduler` + `auctions.starts_at`（D-35）；迁移 `V4__agent_access.sql`、`V6__agent_proxy.sql` |
| 模拟脚本 | ✅ | `tools/agent_sim.py`：只用标准库，扮演管理员与竞拍 Agent，走真实 `:8080`/`:8090`：登录→创建并开拍→签发读+出价/只读/短命/限流四种 Token→Agent 读状态→Agent 出价→幂等重放→真人加价→Agent 夺回→读结果→逐条验证 403/401/429/404/端口隔离，最后打印“期望 vs 实际”清单（任一条不符则非零退出）。`tools/auction_sim.py`：全链路模拟（20 条并发同/邻价、幂等重试、拒绝场景、最后五秒狙击、WebSocket 断线快照、结束核对），**52/52 实跑通过**；内含最小 RFC 6455 客户端，不引第三方依赖 |
| 前端接入 | ✅ | `frontend/`：契约生成类型（D-26）+ 类型化 HTTP 客户端（统一封套/错误码/幂等头/超时）+ 实时订阅状态机（去重、缺口拉快照、退避重连；D-28）+ Pinia store（服务端为唯一事实来源；D-27）；界面含拍卖大厅/详情（含“预告 mm:ss 后开拍”）、钱包流水（含 AI/真人徽章）、运营台（含全部托管代理总览）与**“我的 AI 代理”**（主路径：创建托管代理 + 在管列表；自助 Token 收进“高级”，D-34/D-36）；**69 单测**（api/realtime/store/anonymous）+ **16 变异 16/16 KILLED**；`npm run typecheck` 与 `vite build` 通过；真后端联调 **3/3**（`npm run test:live`） |
| 一键测试命令 | ✅ | 后端：指定 `BID_ARENA_TEST_DB_*` 后 `mvn clean verify`（实测 **225/225** 绿：真库集成 85〔HTTP 21 + WS 14 + Agent 29 + 拒绝后连接复用 2 + 托管代理与预告开拍 19，共用同一个自启动服务实例〕+ 架构守卫 9 + 其余领域/身份/结算/事件/WS 广播/票/匿名/Agent 单元 131）；Agent 凭据变异 `python tools/agent_mutation_check.py`（**14/14 KILLED**）；架构变异 `python tools/arch_mutation_check.py`（9/9 KILLED）。端到端：后端在 8080/8090/18080 跑起来后 `python tools/agent_sim.py`（**44/44**）与 `python tools/auction_sim.py`（**52/52**），均非零退出即失败。前端：`cd frontend && npm test`（69 绿）与 `npm run typecheck`；变异 `python tools/mutation_check.py`（16/16 KILLED）；真后端联调 `npm run test:live`（3 绿）。手动/压力：后端跑起来后 `python tools/stress_test.py --mode game-window -c 100`（100/100 均为 `403`/`HUMAN_ONLY_PERIOD`，**11/11** checks passed）与 `python tools/stress_test.py --mode throughput -c 50 --seconds 10`（实测 ~412 QPS、无 5xx） |
| 远程仓库 | ✅ | <https://github.com/Ayong-ui/bid-arena>（公开；`main` 已开分支保护：禁强推、禁删除） |

## 4. 决策状态

全部决策已定稿并写入 [`DECISIONS.md`](../DECISIONS.md)（D-1~D-36 含背景/候选/选择/代价/验证结果，附「未采用方案汇总」）。D-14~D-17 是 P2 期间新增的：错误码与 HTTP 状态码的分工、鉴权默认拒绝、CORS 白名单、测试期配置覆盖；D-18~D-23 是 P3 期间新增的：实时通道的鉴权方式、`seq` 的归属与语义、广播失败边界、匿名标识、客户端消息一律忽略、测试基座的“一 JVM 一实例”；D-24~D-25 是架构守卫期间新增的：出站适配器独立成 `persistence` 包、分页参数与 HTTP 解析分离；D-26~D-28 是 P4 期间新增的：前端类型从契约生成、前端不得自己算钱与倒计时、客户端 `seq` 缺口恢复；D-29~D-30 是 P5 期间新增的：Token 范围缺省即拒绝、Agent 出价复用同一事务并自动加入；D-31 是全链路模拟期间补登的：幂等键颗粒度是 `(auctionId, userId, requestId)`；D-32~D-34 是 P6 期间新增的：尾段“博弈时间”清场 Agent（有意偏离原文规则 6）、成交主体标识与隐私遮蔽、Agent 授权自助化；D-35~D-36 是 P6 后期新增的：预告开拍（`starts_at` + 到点自动开拍，不把“运营在线”放进关键路径）、服务端托管 AI 代理（普通用户选场次 + 预算上限即可用）。

| # | 决策 | 结论 | 验证 |
|---|---|---|---|
| D-1 | 数据访问方式 | HikariCP + 手写 JDBC（solon-data 无可用 SQL 工具） | ✅ 已在出价事务与仓储中使用 |
| D-2 | 迁移工具与执行位置 | 应用内 Flyway，删除 initdb 挂载 | ✅ 空库执行 + 失败路径均已实测 |
| D-3 | 鉴权与密码哈希 | jjwt + BCrypt；Agent Token 独立 | ✅ 用户侧已实测（P2）；Agent 侧已实测（P5，`AgentApiIntegrationTest` + `tools/agent_sim.py`） |
| D-4 | 并发正确性归属 | MySQL 唯一约束 + 行锁 + 条件更新 | ✅ 已用变异测试反向确认 |
| D-5 | 时间基准 | 事务内取数据库时间 | ✅ `Db.now()`，测试夹具亦用数据库时间 |
| D-6 | 架构形态与进程模型 | 单模块 + 4 上下文 + 四层包（含出站 `persistence`）+ ArchUnit | ✅ 九条规则全绿且 9/9 变异被杀（D-24） |
| D-7 | 开发环境拓扑 | 代码 Windows / 容器 VM / Docker over SSH | ✅ 已实测 |
| D-8 | 端口规划 | 8080 / 8090 / 3307 / 5173 | ✅ 已同步 |
| D-9 | Agent 凭据 | 独立 Token + 独立端口 + 限流 | ✅ P5：五类边界均有断言，`tools/agent_mutation_check.py` 14/14 KILLED；端到端 `tools/agent_sim.py` |
| D-10 | 不变量下沉到数据库约束 | CHECK / 外键 / 唯一键 | ✅ 7 项反向验证全部被拒绝 |
| D-11 | 被拒 `requestId` 的重试语义 | 返回首次结论（需作者确认） | ✅ 已测，⏳ 待作者确认 |
| D-12 | 结算的原子性边界 | `SETTLING` 为事务内中间态，单事务完成结算 | ✅ 已测 + 变异测试 |
| D-13 | “重放”的适用范围 | 只在同一种结束方式下重放，否则报状态错 | ✅ 已测（含并发结算与取消竞争） |
| D-14 | 错误如何表达 | 封套内 `code` 为权威，HTTP 状态码是它的投影；重放用 200 + `IDEMPOTENCY_REPLAY` | ✅ 6 类失败路径 + 两种成功码均有断言 |
| D-15 | 鉴权边界 | 默认拒绝：白名单（登录、健康检查）之外的路径（含不存在路径）先验令牌 | ✅ 未知路径无令牌 401 / 有令牌 404 均有用例 |
| D-16 | CORS | `CORS_ORIGINS` 白名单，未配置即不放开；预检在鉴权之前短路 | ✅ 白名单内外行为均有断言 |
| D-17 | 测试期配置覆盖 | 系统属性覆盖 yml 真实键（`server.port`），并断言覆盖生效 | ✅ 断言生效，反证能红（DBG-10） |
| D-18 | 实时通道鉴权 | 一次性短票（60s、单次核销、有容量上限），而不是把 JWT 放进 URL | ✅ 一次性/过期/伪造/缺失/容量均有断言（`WsTicketServiceTest` + `WsIntegrationTest`） |
| D-19 | `seq` 的归属 | 已提交状态变更的版本号：一次命令 +1，被拒的出价不推，一条命令的多条事件共享同一 `seq` | ✅ `EventPublishingTest` 逐个场景断言 `Fixtures.seq` 与事件 `seq` 相等；变异“重复 join 也推 seq”被杀 |
| D-20 | 广播失败边界（A8） | 事件在事务提交后发布；发布失败只记日志，绝不回滚已提交的事务 | ✅ 发布器每次抛异常时出价仍成功，且领先者/冻结/流水四项都落库 |
| D-21 | 事件里的用户标识 | 一律用确定性匿名标识（`anon-` + SHA-256 前 8 位），HTTP 快照仍返回原始 ID | ✅ 固定向量断言 + 全流程事件扫描不含原始 `user_id`；变异“改成随机值”被杀 |
| D-22 | 客户端→WS 的消息 | 一律忽略：命令入口只有 HTTP/Agent，WS 是单向通知通道 | ✅ 发送伪造命令后无业务帧、连接不断、库内无变化 |
| D-23 | 测试基座的服务生命周期 | 一个测试 JVM 只起一个服务实例，由 JUnit 根上下文存储持有并在整轮结束时停服 | ✅ 全量 116 绿且 fork 正常退出；反证见 DBG-13 |
| D-24 | 出站适配器归属 | JDBC 仓储独立成 `<ctx>.persistence`，`adapter` 只表示入站；视图 DTO 归 `<ctx>.application`，`ApiTime` 归 `shared` | ✅ 搬运后 116 绿（行为不变）+ 九条规则全绿 + 9/9 变异被杀 |
| D-25 | 分页参数归属 | `shared.PageQuery`（含上限与 `Page<T>`）+ `api.PageParams`（解析查询串） | ✅ `application` 不得依赖 `com.bidarena.api` 的规则通过；分页 HTTP 用例未改仍绿 |
| D-26 | 前端类型来源 | 从 `openapi.yaml` 生成（`openapi-typescript`），不手写 | ✅ `contract.test.ts` + `npm run typecheck`；F1~F10 变异能被抓住的前提 |
| D-27 | 前端事实来源 | 金额/状态/倒计时一律来自服务端快照与事件，倒计时用 `serverTime` 校准 | ✅ `arena.test.ts` 时钟偏差与出价价格用例；变异 F15 被杀 |
| D-28 | 客户端 `seq` 缺口恢复 | 按 `(auctionId, seq, type)` 去重；缺口拉权威快照、旧事件作废、新事件补放；重连换票重置基线 | ✅ `feed.test.ts` 14 用例；变异 F11/F12/F13 被杀 |
| D-29 | Agent Token 的 `auctionIds` 缺省语义 | 缺省 = 空集合 = 默认拒绝（不是“全部允许”） | ✅ `AgentTokenTest`/`AgentTokenServiceTest` + `AgentApiIntegrationTest` 空范围 403；变异 G3/G12 被杀 |
| D-30 | Agent 出价路径 | 复用同一出价事务，事务内自动补参与记录（`AGENT`），不新开写入路径 | ✅ `AgentApiIntegrationTest`（自动加入 + 与真人共用幂等）；变异 G11 被杀 |
| D-31 | 幂等键颗粒度 | `(auctionId, userId, requestId)`：重试去重只在本调用方内生效，跨用户不互吞 | ✅ `tools/auction_sim.py`：同一用户 20 并发重试 + 顺序重试 = 1 写 + 19 重放，冻结增量 = 成交价；另一用户复用同串 = 新出价 |
| D-32 | 尾段“博弈时间” | 最后 20 秒（`AUCTION_FINAL_GAME_WINDOW_SECONDS`）强制拒绝一切 Agent 出价，真人不限；事务内用数据库时间判定，进入即锁到结算 | ✅ `BidServiceTest` 四例 + `AgentApiIntegrationTest` 端到端 403；窗口经快照下发给前端（`AuctionSnapshot.finalGameWindowSeconds`，`HttpApiIntegrationTest`/`WsIntegrationTest` 断言），前端据此渲染博弈时间提示；有意偏离原文规则 6 已留痕 |
| D-33 | 成交主体标识与隐私 | `bids.actor_type` → `settlements.winner_type` 快照 → `ledger_entries.actor_type`；`winnerType` 仅赢家/管理员可见，个人流水只含本人，新增管理员按场次流水端点 | ✅ `SettlementServiceTest` 两例 + `HttpApiIntegrationTest` 隐私遮蔽与权限两例 |
| D-34 | Agent 授权自助化 | 新增 `/me/agent-tokens`（列表/签发/吊销）与 `/admin/agent-tokens`（仅 ADMIN）；请求体不含 `agentUserId`，归属由 `issueForSelf` 钉死；他人 Token 吊销返回 404；列表永不含明文，`status` 由服务端按 `activeAt` 同口径下发 | ✅ `AgentTokenServiceTest` 五例 + `AgentApiIntegrationTest` 五例 + 前端 `store/arena.test.ts` 四例 |

## 5. 已确认决定

| # | 决定 | 出处 |
|---|---|---|
| C-1 | 原文是唯一事实依据，其余文档不得削减 | `docs/DOCS.md` |
| C-2 | 文档四层分层、权威边界、冲突裁决顺序 | `docs/DOCS.md` |
| C-3 | 每类事实只在一处定义，其余只引用 | `docs/DOCS.md` |
| C-4 | 非必交文档可删除或大改 | 本次整理 |
| C-5 | 限界上下文为 4 个；`settlement` 是事务边界而非上下文 | `DESIGN.md` §2.1 |
| C-6 | 只读 Docker 检查，不修改配置、不重建容器、不批量启服务 | 本章约定 |

## 6. 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 前端 `Status` 缺 `SETTLING` | 前后端类型漂移 | ✅ 已解决：类型由 `openapi.yaml` 生成（D-26），缺枚举会在编译期报错 |
| 前端 Mock 本地计算余额/赢家 | 违反唯一事实来源 | ✅ 已解决：P4 已替换为快照/事件驱动，本地不再算钱与倒计时（D-27） |
| initdb 方式加 V2 不生效 | 迁移"看起来做了其实没做" | ✅ 已定：应用内 Flyway（D-2） |
| 内存引擎硬编码 1000 积分 | 与真实钱包脱节 | ✅ 已解决：`AuctionEngine` 已删除，逻辑全部吸收到 `BidService` |
| 迁移脚本副本可能不是最新的（增量拷贝） | 改了迁移却跑旧脚本，会出现不可复现的假失败 | 一键测试命令统一用 `mvn clean verify`（`DEBUG_LOG.md` DBG-2） |
| 验证可能命中残留旧进程 | “健康检查通过”变成假证据，后续结论建立在旧代码上 | 验证脚本加“监听端口 PID == 本次启动 PID”断言，并加非空前置条件；收尾用 `taskkill`（`DEBUG_LOG.md` DBG-4、DBG-5） |
| 单模块下依赖方向只靠自觉 | 架构随时间腐化 | ✅ 已由 ArchUnit 九条规则守卫（D-6/D-24），并用变异脚本确认规则真的会红 |
| 一个包名承担两种含义（`adapter` 同时装控制器与仓储） | 依赖方向看起来成立、实际成环；文档里的“无循环”变成空话 | 出站仓储独立成 `persistence`，`adapter` 只表示入站（DBG-18、D-24） |
| 架构规则写成永远不会失败的断言 | “规则全绿”被当成架构干净的证据 | 每条规则都用变异体验证会红，脚本 `tools/arch_mutation_check.py`（DBG-19） |
| 共享服务基座下按空闲负载给时间预算 | 高负载全量跑时偶发假失败，容易被误判成产品缺陷 | 连接类动作按截止时间重试而非单次预算（DBG-20） |
| 包结构先于业务建立，可能过度设计 | 抽象与需求不匹配 | 先落最小必要结构，随 P1 实际用例调整 |
| “测试全绿但构建失败”被当成环境问题 | 去改无关配置（如 surefire 的 classpath 开关），真因留在测试里继续咬人 | 测试清理不得调用会 `System.exit` 的 API；始终用一条命令跑全量（DBG-8） |
| 用 JDK 自带客户端“模拟浏览器” | 测试工具的限制被误判成服务端缺陷（差点去改 `CorsFilter`） | 显式打开受限头，并让断言直指契约行为（DBG-9） |
| 插件式框架里“声明了”不等于“生效了” | 序列化/解析行为随依赖树静默漂移，只在报错时才暴露 | 让 classpath 上只留一个候选，不为测试改生产代码（DBG-11） |
| 凭证随着系统属性进入测试报告 | 库口令、密钥落到 `target/surefire-reports/*.xml`（会进 CI 归档与录屏画面） | 测试结束清掉带凭证的系统属性，提交前扫一遍 `target/`（DBG-12） |
| “重启一个已停掉的 Solon 实例”其实没换端口 | 第二个测试类会连到一个没人监听的端口上，症状是“服务起不来”，而代码没错 | 一个测试 JVM 只起一次服务，停服交给 JUnit 根上下文存储在整轮结束时做（DBG-13、D-23） |
| 用 `Map.copyOf` 固化 payload 时忽略 `null` 值 | 一次正常的“无赢家/无截止时间”会把发布变成 NPE，症状是结算失败但库已提交 | payload 工厂统一跳过 `null`（缺席即无字段），并在测试里固定“该字段应当缺席”（DBG-14、DBG-15） |

## 7. 下一步

1. **P2 — 已完成**：
   - ✅ 接口层：`HealthController`、`HttpAuthController`（`/auth/login`、`/users/me`）、`HttpAuctionController`（列表/快照/加入/出价/出价记录/结果）、`HttpAdminController`（创建/开始/取消）、`HttpWalletController`（钱包/流水），共 14 个端点，契约见 `docs/openapi.yaml`。
   - ✅ 基础设施：`bootstrap/Env`（唯一配置读取点，系统属性优先于环境变量）、`app.yml` 只留端口、`ApiResponse`/`TraceId`/`ErrorCode` 统一封套与错误码、`ApiExceptionFilter`（最外层，兜住 BizException / StatusException / 其它异常）、`AuthFilter`（默认拒绝）、`CorsFilter`（白名单）、`PageQuery` 统一分页校验。
   - ✅ 身份：`identity` 上下文（`UserRole`/`UserStatus`/`User`/`Principal`、`IdentityService`、`BCryptPasswordHasher`、`JwtTokens`、`UserRepository`）；`Services` 组合根两种接线（生产要求 `JWT_SECRET`，测试显式传入）。
   - ✅ 测试：P2 新增 31 个用例（`HttpApiIntegrationTest` 19、`IdentityServiceTest` 10、`SeededDemoCredentialsTest` 2），全量 **63/63** 绿（`mvn clean verify`）。
   - ✅ 反向确认：`IdentityServiceTest` 断言“未知邮箱 / 密码错 / 已禁用”返回完全相同的响应（防账号枚举）；`SeededDemoCredentialsTest` 直接用 BCrypt 校验种子哈希，并断言明文口令不出现在迁移文件里。
2. **P3 — 已完成**：
   - ✅ 实时通道：`POST /auth/ws-tickets`（一次性票，60s TTL、单次核销、有容量上限 → 超出 429）+ `WebSocketRouter` 注册的 `/ws/auctions/{auctionId}`（握手鉴权 → 订阅 → 权威快照 → 连接状态两帧）。
   - ✅ 事件：`AuctionEventType`（快照/加入/接受/拒绝/延时/结束/连接状态，各自声明可见范围）、`AuctionEvents`（payload 工厂，`null` 即字段缺席）、`WsEventBroadcaster`（按对象身份登记订阅，单播与扇出分离，非可扇出事件 fail-closed，发送失败只计数并摘除连接）。
   - ✅ `seq` 与事务边界：`auctions.seq` 每次成功命令 +1（被拒的出价不推、重放不推、重复加入不推），一次提交的多条事件共享同一 `seq`；事件在**提交之后**发布，发布失败不回滚（A8）。
   - ✅ 隐私：事件里只出现确定性匿名标识 `anon-<sha256 前 8 位>`（HTTP 快照仍返回原始 ID，差异已在契约中写明）。
   - ✅ 测试：P3 新增 53 个用例（WS 端到端 14、事件语义 11、广播器 14、票 10、匿名标识 4），全量 **116/116** 绿；6 个变异（`null` 放行、重复 join 推 `seq`、去掉 A8 边界、票可重用、拒绝事件可扇出、匿名标识随机化）全部被杀死。
3. **架构守卫 — 已完成**（D-6 的验证项）：
   - ✅ 包搬运（行为不变）：出站 JDBC 仓储 → `<ctx>.persistence`；查询视图 → `<ctx>.application`；`ApiTime`/`PageQuery` → `shared`；HTTP 查询串解析 → `api.PageParams`（D-24、D-25）。
   - ✅ `ArchitectureTest` 九条规则：`domain` 纯净、`application` 不依赖入站适配器/装配/`api`/框架、`persistence` 不反向依赖、入站适配器不依赖 `bootstrap`、共享内核不依赖上下文、跨上下文 `domain`/`adapter` 不互引、上下文与层均无环。
   - ✅ 反向确认：`tools/arch_mutation_check.py` 注入九种真实违规（跨层 import、反向依赖、跨上下文引用、两种环路），对应规则全部变红，**9/9 KILLED**。
   - ✅ 全量 **125/125** 绿（116 + 架构 9）。
4. **P4 — 已完成**：
   - ✅ 类型：由 `docs/openapi.yaml` 生成 `frontend/src/api/schema.d.ts`（D-26），`npm run gen:api` 可重生成。
   - ✅ HTTP 客户端：统一封套与错误码（`code` 为权威）、幂等键头、超时中止、401 触发会话清理；`client.test.ts` / `contract.test.ts`。
   - ✅ 实时订阅：`AuctionFeed` 状态机——`(auctionId, seq, type)` 去重、缺口拉快照、退避重连、一次性票重取（D-28）；`feed.test.ts` 14 用例。
   - ✅ store 与界面：Pinia store 接管会话/数据/命令，`App.vue` 不再本地算钱与倒计时（D-27）；`arena.test.ts` 18 用例。
   - ✅ 反向确认：前端变异脚本扩到 **16 条**，全部 KILLED；其中 F14 存活暴露了一条“声称验证幂等键、实际没验证”的测试（DBG-21，已修）。
   - ✅ 真后端联调：后端跑在 8080/18080 时，`npm run test:live` 的 HTTP/WS 3 个用例全绿。
5. **必交文档 — 已完成**：
   - ✅ `AI_USAGE.md`：已按原文要求填写完毕（§1 工具与模型、§2 人机分工与口径、§3 四项本人设计决定、§4 六项未采用方案、§5 四项真实错误 + 两条未入 `DEBUG_LOG.md` 的补充、§6 尚不能独立解释的代码）；内容由 Coding Agent 依据会话记录与仓库证据起草，文末列出三项「作者核对清单」（模型列表是否完整、比例口径、决定归属）供作者 30 秒复核。
   - ✅ `AGENT_TOOL_SPEC.md`：按 `docs/openapi.yaml` 的 `Agent` 端点写明 Token 形态、评审操作步骤、给 Coding Agent 的提示词模板与失败边界；P5 交付后已把“尚不可用”类表述全部换成已实现并验证的操作步骤（DBG-29），并补充“托管代理 vs Token 两条路径”的分岔说明（D-36）。
6. **P5 — 已完成**（Agent API 与模拟脚本）：
   - ✅ 独立凭据：`agent_tokens`（V4）只存 `sha256` 摘要，明文只在签发响应返回一次；范围/权限/过期/吊销/限流五项在 `AgentToken`/`AgentTokenService`/`AgentRateLimiter` 中实现（D-9、D-29）。
   - ✅ 独立端口：`bootstrap/AgentApiPlugin` 在 `AGENT_SERVER_PORT`（默认 8090）上另起监听，只转 `/api/v1/agent/**`，其余路径 404 封套（DBG-22）。
   - ✅ 复用出价事务：`AgentAuctionService` 只做授权 + 调 `BidService`，Agent 出价与真人出价同一张表、同一个事务、同一套幂等；首次出价在事务内自动补 `AGENT` 参与记录（D-30）。
   - ✅ 测试：P5 新增 **62 个用例**（Agent 集成 23、Token 服务 21、Token 领域 6、Scope 4、限流器 6、拒绝后连接复用 2），全量 **187/187** 绿；`tools/agent_mutation_check.py` 注入 14 种真实缺陷，**14/14 KILLED**（过程中修好了脚本自身的两个假阴性来源，见 DBG-25）。
   - ✅ 模拟脚本：`tools/agent_sim.py` 走真实双端口完成「签发→读→出价→幂等重放→越权/过期/吊销/限流边界→结果」，打印可核验清单；真实环境实跑全绿（见 `docs/TRACEABILITY.md` E1）。
   - ✅ 交付：后端 `Dockerfile` + Compose `backend` 服务（`docker compose config` 已校验；按 C-6 未在本机构建镜像）。
7. **P6 — 进行中**：
   - ✅ `tools/auction_sim.py`（E1 全链路模拟）：20 条并发同/邻价、幂等重试、拒绝场景、最后五秒狙击（+10 秒、最多 3 次）、WebSocket 断线快照、结束核对（结果与钱包/冻结一致），实跑 **52/52**（退出码 0）；局限已在脚本头部如实声明（公开 API 无注册端点，“20 用户”以 20 条并发请求等价模拟，真正 20 个不同用户的并发由 `BidConcurrencyTest` 覆盖）。
   - ✅ 尾段“博弈时间”（D-32）与成交主体标识（D-33）：最后 20 秒强制拒绝 Agent 出价（`HUMAN_ONLY_PERIOD`，事务内数据库时间判定）、`bids/settlements/ledger_entries` 三级主体链与隐私遮蔽。
   - ✅ Agent 授权自助化（D-34）：`/me/agent-tokens` 三端点 + `/admin/agent-tokens` 总览，前端“我的 AI 代理”页。
   - ✅ 预告开拍与托管代理（D-35/D-36）：`auctions.starts_at`（V6）+ `AuctionStartScheduler` 到点自动开拍（复用同一个 `start`）；`agent_proxies`（V6）+ `AgentProxyService`/`AgentProxyScheduler` 到点自动进场、按最小加价跟价、硬预算上限触顶停手、对 D-32 无例外；接口 `/me/agent-proxies` 与 `/admin/agent-proxies`；前端“创建 AI 代理”（选进行中/未开拍场次 + 预算上限）+ 在管列表（状态、下一手、撤销）；`AgentProxyIntegrationTest` **19/19** 绿，前端新增 6 例；另在重启后的开发服务上做了一次接口级手工核验（预告到点自动开拍 → 代理自动进场出价 110 → 被真人超过后夺回 → 尾段 0 次动作且真人仍可出价 → 结算收尾 `FINISHED`/`finalPrice=140` → 撤销），**27/27 checks passed**（脚本不入库，踩坑过程见 DBG-30）。
   - 🟨 录屏（G7）：待作者录制。
   - ✅ `AI_USAGE.md` 填写段（G1）：已填写（依据会话记录与仓库证据起草，附三项作者核对清单）。
   - ⬜ README/DESIGN 最后一致性校对；H 组现场核验演练。

## 8. 更新规则

- 任何一次提交前：更新本文件的对应状态行。
- 新增验收项或完成验收项：同步更新 `docs/TRACEABILITY.md`。
- 新决策：从"待决策"移入 `DECISIONS.md`，并在"已确认决定"登记。
