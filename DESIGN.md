# Bid Arena 设计方案

## 1. 业务全景与当前边界

> 规则与数值以 [`全栈评测-拍卖间-原文.md`](全栈评测-拍卖间-原文.md) 为准。本节只定义**边界、链路与可执行断言**，不复述原文数值。

### 1.1 四个角色与权限边界

| 角色 | 能做 | 不能做 |
|---|---|---|
| 管理员 | 创建 / 开始 / 取消拍卖（可预告开拍）；签发与吊销 Agent Token；查看全局授权、托管代理总览与按场次流水 | 代替服务端改余额、最高价、截止时间或赢家 |
| 真人竞拍者 | 登录、加入、出价、查询自己的钱包与流水 | 直接改余额或冻结；读取他人私有数据 |
| 竞拍 Agent | 用受限 Token 读取被授权拍卖的状态、出价、读取结果 | 访问管理接口、数据库、他人钱包；超出授权拍卖范围 |
| 服务端（Solon） | 唯一裁判：校验、冻结 / 释放 / 扣款、判定赢家、写流水、广播 | —— |

一句话：**客户端与 Agent 只能表达意图，最终结果永远由服务端在事务内决定。**

### 1.2 四条主链路

1. **开拍**：管理员创建（草稿态）→ 开始（进入运行态并设定截止时间）→ 广播权威快照。
2. **出价**：加入 → 提交 `requestId + amount` → 单事务内校验、差额冻结、释放旧领先者、写流水与出价、更新价格 / 领先者 / 截止 / 序号 → 提交后广播。
3. **延时**：截止边界内的合法出价，**在同一次出价事务内**延长截止并累加延长次数 → 广播延时事件。
4. **结算**：到期由服务端自行触发；结算与出价**共用同一把拍卖行锁**，因此两者天然互斥 → 赢家冻结转扣款、他人释放、写唯一成交记录 → 结束并广播。重复触发、重启、双实例均不得重复扣款。

四条链路共同的难点是：**状态机、资金闭环、并发控制、失败恢复**。

### 1.3 四条不变式（可执行断言）

用 SQL 在任意并发压测后校验。它们是“正确性”的操作性定义，也是并发方案有效性的判据。

| 编号 | 不变式 | 违反时的表现 |
|---|---|---|
| INV-1 | **资金非负且守恒**：对任意用户，总余额与冻结金额均非负，可用余额非负；对任一场拍卖，有领先者时本场冻结总额等于当前最高价，无领先者时为零 | 透支、负冻结、冻结与最高价不符 |
| INV-2 | **领先者唯一**：任一瞬间一场拍卖至多一个领先者，其本场冻结等于当前最高价，其余用户本场冻结为零 | 双领先、冻结残留 |
| INV-3 | **请求幂等**：同一 `(auction_id, user_id, request_id)` 至多产生一条出价、一次资金变化、一次序号递增 | 重复冻结、重复流水 |
| INV-4 | **成交唯一**：一场拍卖至多一条成交记录；结束后赢家总余额已扣、所有相关冻结归零、流水可逐笔解释 | 重复扣款、结算后冻结残留 |

不变式与验收项的映射见 [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md#不变式映射)。

### 1.4 技术边界：做什么、不做什么

| 做 | 不做（主动排除，避免用复杂度掩盖正确性） |
|---|---|
| 状态机、出价事务、差额冻结与释放、幂等、唯一结算、重启恢复 | 真实支付、聊天、推荐、精细美术、复杂运营后台 |
| 单调 `seq` + 快照重同步的实时通道 | 让 WebSocket 承担事实来源；广播失败回滚已提交事务 |
| Agent 最小权限、范围限定与限流 | Agent 直连数据库或管理接口 |
| 用 MySQL 事务 / 锁 / 唯一约束保证正确性 | 依赖应用层“先查询再判断”；ES / Canal 同步；无必要的微服务拆分 |
| Redis 仅为可选加速，故障一律回退 MySQL | 把 Redis 当作余额或赢家的事实来源 |

### 1.5 验证策略

功能测试不足以证明并发正确性。本项目的证据链分三段：

1. **规则单测**：状态机、截止边界、拒绝分支、幂等返回。
2. **真实 MySQL 集成测试**：并发出价、唯一结算、重启恢复（原文要求关键并发与结算测试不得全部由内存 Mock 代替）。
3. **压测 + 校验 SQL**：模拟脚本制造并发与狙击延时后，直接查库校验 **INV-1 ~ INV-4**，而不是只看接口返回。

### 1.6 当前实现边界

**已实现并已在真实 MySQL 8.4 上验证：**

- 迁移与连接：Flyway `V1`~`V6`（含两级冻结、`CHECK` 约束、种子数据、Agent Token 表、成交主体与流水主体、预告开拍列与托管代理表），HikariCP，`Services` 组合根（测试与生产共用同一套接线）。
- 出价事务 `BidService`：差额冻结、换庄释放、幂等重放、最后 5 秒延时（上限 3 次）、按 `user_id` 升序的固定锁序；**尾段“博弈时间”（默认最后 20 秒，`AUCTION_FINAL_GAME_WINDOW_SECONDS`）在事务内强制拒绝一切 Agent 出价**（`HUMAN_ONLY_PERIOD`，D-32），真人不受限。
- 结算 `SettlementService` + 扫描器 `SettlementScheduler`：到期结算、无人出价、取消三条路径共用一个终局逻辑，成交记录唯一、可重放。
- HTTP API（`docs/openapi.yaml` 为契约）：统一响应封套与错误码、JWT 鉴权与 RBAC、幂等键、限流、分页。
- 实时通道：一次性 WS 票、提交后广播（广播失败不回滚）、`seq` 缺口恢复，事件负载只带确定性匿名标识。
- Agent 接入（P5）：独立端口 `:8090` 只暴露 `/api/v1/agent/**`（其余路径 404）、独立凭据（明文只回一次、库里只存 sha256）、
  范围/权限/过期/吊销/限流五项在鉴权阶段生效；Agent 出价**复用同一出价事务**（`BidService.placeBid`，`AGENT` 参与记录同事务插入），不新开写入路径（D-30）。
- 结算与主体标识：结算把赢家最后一笔出价的 `actor_type` 快照进 `settlements.winner_type`，也写进每条 `ledger_entries.actor_type`；`result.winnerType` 仅赢家本人/管理员可见，另提供管理员按场次流水 `GET /admin/auctions/{id}/ledger`（D-33）。
- Agent 授权自助化（D-34）：新增 `GET/POST /me/agent-tokens` 与 `POST /me/agent-tokens/{tokenId}/revoke`，请求体**没有** `agentUserId`（归属由服务层 `issueForSelf` 钉死），他人的 Token 吊销返回 404；管理员侧另有 `GET /admin/agent-tokens` 总览；所有列表接口**从不回明文**，状态 `status` 由服务端按 `activeAt` 同口径下发；前端对应“我的 AI 代理”页面。
- 博弈时间的可见性：快照（HTTP 与 WS 同构）带 `finalGameWindowSeconds`，由服务端下发而不是前端硬编码；前端只用它把“剩余 ≤ 窗口”渲染成提示（真人仍可出价，Agent 已被服务端事务无条件拒绝），不承担任何判定职责。
- 托管 AI 代理（D-36）：普通用户在“AI 代理”页选一场**进行中或未开拍**的拍卖并设定预算上限，服务端 `AgentProxyScheduler` 到点（若拍品有预告时间）自动进场——策略单一（不领先就出 `currentPrice + minIncrement`，到硬预算上限停手并置 `BUDGET_REACHED`），一人一场最多一个（`uk_proxy_owner_auction`），**创建时不预冻结**（钱只在真正出价时按 D-30 冻结）；出价复用 `BidService.placeBid(..., AGENT)`，**对 D-32 的博弈时间无例外**；提醒走前端轮询读模型对比前后状态（不新增私有 WS 事件）。
- 预告开拍（D-35）：`auctions.starts_at`（可空）+ `AuctionStartScheduler` 到点自动开拍；自动开拍复用与管理员手动 `start` 完全相同的用例（不复制状态流转），时间基准取数据库时间（`Db.now`）；快照下发 `startsAt` 供前端预告。
- 架构守卫：`ArchUnit` 九条分层/跨上下文/无环规则（§2.4）。
- 后台扫描器归属（D-40）：三个扫描器（结算/预告开拍/托管代理）由 `bootstrap/ScannerBootstrap` 按 `SETTLE_SCHEDULER_ENABLED` / `AUCTION_START_SCHEDULER_ENABLED` / `AGENT_PROXY_SCHEDULER_ENABLED` 决定是否在本实例启动，缺省全开、构造期生效；它只表达“本实例不跑”，不解决多实例自动分工。
- 前端：Vue 3 + Pinia 接入真实 HTTP/WS，类型从契约生成，金额/倒计时以服务端为准（P4）；P6 增补尾段“博弈时间”提示（依据快照下发的 `finalGameWindowSeconds`）、成交主体 `AI/真人` 徽标与管理员按场次流水面板（D-33），并把“智能体接入”整页换成用户向的“我的 AI 代理”——主路径是**创建托管代理**（选场次 + 预算上限）与在管列表，自助 Token 收进页底“高级”（D-34/D-36）；大厅与运营台显示“预告 mm:ss 后开拍”（用 `store.serverNow`，不用本机时钟，D-35）。
- 证据：`mvn clean verify` 共 242 个测试全绿（真库集成 90 + 扫描器开关纯策略 4 + 配置键守卫 3〔`EnvDocumentationTest`：扫源码确保 `.env.example` 不漏键〕+ 其余领域/身份/结算/事件/WS/Agent/迁移开关单元 136 + 架构守卫 9）；
  关键路径另做变异测试反向确认确实会红（架构 9/9、Agent 14/14、前端 16/16）；前端另有 73 单测与 3 个真后端联调。逐类明细见 `docs/STATUS.md`、`docs/TRACEABILITY.md`。

**尚未实现（如实声明）：** 只能靠 HTTP 复现的部分已全部有脚本——`tools/auction_sim.py` 覆盖并发同/邻价、`requestId`
重试、拒绝场景、最后五秒狙击、断线快照与结算对账（实测 52/52），`tools/agent_sim.py` 覆盖 Agent 侧（44/44）。
唯一仍需集成测试承担的是“20 个**不同用户**并发”：公开 API 没有注册端点、种子只有 3 个演示账号，
这部分由真实库上的 `BidConcurrencyTest` 覆盖。另：
`docker compose up` 是在 CI 上真跑过的（`Dockerfile`/`frontend/Dockerfile`/`frontend/nginx.conf` 与 `mysql`/`migrate`/`backend`/`frontend` 四个服务已配置，`docker compose config` 与 `nginx -t` 本地校验，CI 每次提交 `docker compose build` + `up -d` 并断言镜像有产物、`:8080` 健康、`:8088` 静态页与 `/api` 反代、`migrate` 退 0；本条曾写“未实测”，已按 CI 证据更新）、
本机（Docker daemon 在远程 VM 且连不上 Docker Hub，本地镜像源无 node/maven/temurin）与评测机上仍不重建容器（C-6）、
演示录屏与现场核验素材。

## 2. 组件与职责

```text
Vue 3 + Pinia  ──HTTP/JSON──>  Solon API  ──事务/行锁──> MySQL 8
       │                            │
       └────WebSocket 事件<─────────┘
竞拍 Agent ──Token API──> Agent API :8090 ──┘
```

- **前端**：路由、表单校验、倒计时展示、Pinia 快照/事件合并、断线重连；不计算赢家、余额或最终截止时间。
- **Solon API**：认证、RBAC、请求校验、领域服务、统一错误响应、WebSocket 广播、结算扫描任务。
- **Agent API（独立端口 :8090）**：仅暴露 `/api/v1/agent/**`，使用 Agent Token 鉴权和独立限流；不得访问管理员路由、任意用户钱包或数据库。
- **领域层**：规则写在事务型应用服务（`BidService` / `SettlementService`）内，与持久化状态处于同一事务；`domain` 包只放不依赖任何基础设施的枚举与判定（`AuctionStatus` / `SettlementReason` / `LedgerType`）。
- **MySQL**：用户、钱包、流水、拍卖、参与者、出价、幂等请求、成交、Agent Token 与托管代理的权威数据。
- **WebSocket**：只做提交后通知，广播失败不回滚已提交事务；客户端可用快照恢复。

### 2.1 限界上下文

按**业务概念**划分，不按数据表划分。本项目只需要 **4 个上下文**，每个都对应一条真实的业务边界：

| 上下文 | 拥有的概念 | 对外提供的用例 |
|---|---|---|
| `identity` | 用户、角色、密码哈希、状态、会话令牌 | 登录、查询当前用户、管理员 RBAC |
| `wallet` | 钱包、冻结金额、资金流水 | 冻结、释放、扣款、查询余额与流水 |
| `auction` | 拍卖、参与者、出价、`seq`、成交结果 | 创建 / 开始 / 取消、加入、出价、结算、查询 |
| `agentaccess` | Agent Token 与托管代理：摘要、范围、权限、过期、吊销、限流；代理状态机 | 签发（管理员代表他人 / 用户为自己）、吊销、校验 Agent 请求（D-34）、创建 / 撤销 / 推进托管代理（D-36） |

**刻意不拆成 7 个上下文**：参考方案中的 errand 等上下文来自另一个领域，此处不存在；`settlement` 也不是独立上下文——它与出价共享“拍卖生命周期”这一概念，只是**事务边界**不同（见 §3）。把事务边界误当成上下文边界，会凭空制造跨上下文事务，违背本项目的取舍原则。

两个一致性边界落在上下文之间：

- **出价事务** = `auction` 决策 + `wallet` 资金变动，同一 MySQL 事务。
- **结算事务** = `auction` 判定赢家 + `wallet` 扣款与释放，同一 MySQL 事务。

### 2.2 四层依赖隔离与端口适配器

每个上下文内部都是同一种四层结构，依赖方向**只能向内**：

```text
bootstrap/          装配与启动（唯一允许知道所有上下文的地方）
  adapter/          入站：HTTP / WebSocket / Agent
  persistence/      出站：JDBC 仓储（只做 SQL 与行映射）
    application/    用例编排、事务边界、端口调用
      domain/       聚合、值对象、不变量、领域端口（接口）——零外部依赖
```

| 层 | 允许依赖 | 明确禁止 |
|---|---|---|
| `domain` | 仅 JDK 与 `shared` | 框架注解、`java.sql`、JSON、任何其它层 |
| `application` | 本层、`domain`、`shared`、本上下文的 `persistence` | 入站 `adapter`、`bootstrap`、框架 web/JSON 类型 |
| `persistence` | 本层、`domain`、`shared` | `application`、`adapter`、`bootstrap` |
| `adapter`（入站） | 本层、`application`、`domain`、`shared` | `bootstrap` |
| `bootstrap` | 全部 | —— |

**端口与适配器**：`domain` 永远不 import JDBC、HTTP 或 Solon。**当前实现的折中是**：只有真正需要替换的基础设施才在 `domain` 定义端口（如事件发布 `AuctionEventPublisher`，测试里用录制/爆炸实现替换），其余出站能力是 `persistence` 里的具体仓储类——「一次出价 = 一个事务」要求每条 SQL 与应用层共用同一个 `Connection`，此时再套一层端口接口只会把事务边界打散（取舍见 DECISIONS D-24）。

**充血模型**：状态流转与规则应当写在聚合内部，而不是散落在 Service 的 `if-else` 里。**当前实现的折中是**：规则判定集中在两个事务型应用服务内（一个事务边界对应一个服务），而不是散落到控制器或仓储里；`domain` 包只放不依赖基础设施的枚举与判定。把规则再下沉到“聚合对象自己持有连接”，在当前规模下得不偿失——那会把事务边界拆散到多个对象，而“一次出价 = 一个事务”正是本项目的核心约束。

### 2.3 包结构

按上下文分包，层在上下文内部：

```text
com.bidarena
├── shared/                   共享内核：异常与错误码、时间序列化、分页参数、确定性匿名标识
├── identity/{domain,application,persistence,adapter}
├── wallet/{domain,application,persistence,adapter}
├── auction/{domain,application,persistence,adapter}
├── agentaccess/{domain,application,persistence,adapter}
├── api/                      横切的 HTTP 入站基础设施：封套、过滤器、当前用户、查询串解析
└── bootstrap/                装配、配置、Application
```

各包职责的边界（写下来是因为它们最容易被“顺手放一下”弄乱）：

- `adapter`：只放入站适配器（控制器、WebSocket 监听器、广播器）与入站相关的基础设施实现（JWT 签发、BCrypt 哈希）。
- `persistence`：出站 JDBC 适配器，只做 SQL 与行映射，不判断业务规则。
- `application`：用例编排与事务边界；查询视图（`AuctionViews` / `WalletViews` / `UserView`）也在这里——它们是**用例的返回形状**，不是 HTTP 契约（HTTP 契约只由 `docs/openapi.yaml` 定义）。
- `domain`：枚举、判定、领域事件与领域端口；**不得**出现 JDBC / HTTP / JSON / 框架类型。
- `api`：不属于任何上下文，是横切的入站基础设施。它依赖 `identity`（过滤器验票需要 `TokenService` / `Principal`）又被各控制器依赖，这是设计使然，不算上下文循环。

跨上下文只允许 `application` 依赖另一个上下文的**端口接口或应用服务**；`adapter` 之间禁止互相引用；跨上下文的具体实现在 `bootstrap` 装配。

### 2.4 依赖规则可测试化

上述约束不靠约定，靠 `ArchUnit` 写成单元测试，`mvn test` 即守卫（规则源码：`src/test/java/com/bidarena/architecture/ArchitectureTest.java`）：

| 规则 | 对应约束 |
|---|---|
| `domainDependsOnlyOnItselfAndTheSharedKernel` | `domain` 不得依赖 `application` / `adapter` / `persistence` / `bootstrap` / `api`，也不得依赖 `org.noear`、`java.sql`、`javax.sql`、`com.fasterxml.jackson`、`org.slf4j` 与 JDBC 助手 `shared.Db` |
| `applicationLayerDoesNotDependOnInboundAdaptersOrBootstrap` | `application` 不得依赖入站 `adapter` / `bootstrap` / `api`，也不得直接用框架 web/JSON 类型 |
| `persistenceLayerDoesNotDependOnApplicationOrAdapters` | 出站适配器不得反向依赖用例层与入站适配器 |
| `inboundAdaptersDoNotDependOnBootstrap` | 入站适配器不得介入装配 |
| `sharedKernelDoesNotDependOnContexts` | 共享内核是被依赖方，不得依赖任何上下文 |
| `domainModelsOfDifferentContextsDoNotDependOnEachOther` | 不同上下文的 `domain` 之间不得直接引用 |
| `adaptersOfDifferentContextsDoNotDependOnEachOther` | 不同上下文的 `adapter` 之间不得互相引用 |
| `contextsAreFreeOfCycles` | 上下文之间不得相互依赖成环 |
| `layersAreFreeOfCycles` | 任意两个包之间不得存在循环依赖 |

**两条维护约定**：

1. 规则本身也要被验证。`tools/arch_mutation_check.py` 逐条注入一次真实违规（跨层 import、反向依赖、跨上下文引用、环路），确认对应规则真的会变红——当前 **9/9 KILLED**。没有这一步，“规则全绿”既可能代表架构干净，也可能代表规则写错了（真的写错过一次，见 DEBUG_LOG DBG-19）。
2. 先改设计文档，再改规则。**不允许为了让它变绿而放宽规则**：某条规则不成立就是一个待修的架构问题，应登记在 `docs/STATUS.md`，而不是删掉断言。

**仍然是一个可部署单元**：不引入微服务、消息中间件或事件溯源；上下文是代码边界，不是网络边界。

### 2.5 部署形态与进程模型

**单个可部署单元，单个 Maven 模块。** 不拆 Maven 多模块，也不拆独立 worker 进程：

- 依赖方向的**编译期强制**（Maven 模块）与**测试期强制**（ArchUnit）在本项目规模下效果等价；为此把 4 个上下文再乘上层数，会得到十几个模块，与“小而完整”的取向相反。
- **结算入口只有一条**：定时扫描、启动后补齐、管理员取消全部调用同一个 `SettlementService`。因此进程是否分离**不影响正确性**——正确性来自拍卖行锁与 `settlements` 主键，而不是进程独占。
- **重启无需恢复内存状态**：扫描器每一轮都回数据库查"已到期且仍为 `RUNNING`"的拍卖，不维护待结算队列；进程重启后第一轮就把没结的补上。
- 原文要求的“两个实例同时触发结算”场景，由应用内定时器天然构成（每个实例都会扫），用**并发触发测试**即可验证，无需额外部署一个 worker。
- **迁移不再是每个实例的启动职责**（D-39）：`bootstrap/MigrateMain` 就是上面说的“同一产物里的第二个 main 方法”（`java -cp app.jar:libs/* com.bidarena.bootstrap.MigrateMain`），compose 以一次性 `migrate` 服务跑它，`backend` 等它退出码 0 才启动。应用侧 `MIGRATE_ON_START` 默认仍为 `true`（本机与单实例零改动），部署里显式设 `false` 后应用**只校验**：库落后于代码就拒绝启动。于是多实例/滚动发布既不会在 `flyway_schema_history` 上互相抢锁，也不存在“新实例已改 schema、旧实例还在跑旧代码”的窗口。这套排序成立的前提是 `depends_on: service_healthy` 真的代表“依赖方连得上”：mysql 的 healthcheck 必须走 TCP（`-h 127.0.0.1 --protocol=TCP`），否则会在初始化、3306 还没监听的窗口里误报健康（DBG-34）。

若后续确实需要资源隔离，只需增加一个启动入口（同一产物内第二个 main 方法），不动模块结构。

## 3. 状态机与事务

状态流转：`DRAFT -> RUNNING -> SETTLING -> FINISHED`；`DRAFT/RUNNING -> CANCELLED`。只有 `RUNNING` 且服务端接收时间严格早于 `ends_at` 才能出价。

其中 `SETTLING` 是**结算事务内部的中间态**，提交后不可观测；"`SETTLING` 之后不可取消"这条规则因此体现为"出价事务与结算事务都要先拿到拍卖行锁，谁先拿到谁定结局"。理由见下一节。

### 原子出价

一个 MySQL 事务内按固定顺序执行：拍卖行锁 -> 参与者/本场冻结 -> 相关钱包按 `user_id` 排序 -> 幂等记录检查。校验状态、截止时间、加入关系、金额下限和新增冻结余额后，释放旧领先者冻结，冻结新领先者差额，写资金流水、`bids` 和 `bid_requests`，更新价格/领先者/`ends_at`/`extension_count`/`seq`，最后提交。唯一键 `(auction_id,user_id,request_id)` 保证重复请求只处理一次；死锁按有限次数重试。

### 唯一结算

扫描器查找到期 `RUNNING` 记录，逐条交给 `SettlementService`。**整个结算在一个 MySQL 事务内完成**：拍卖行锁 → 读 `settlements`（命中即重放，返回已有结论）→ 锁定本场全部按场冻结与相关钱包（按 `user_id` 升序）→ 赢家冻结转扣款、其余冻结释放 → 插入唯一 `settlements` → 置终态。

`SETTLING` 写在这个事务内部、不单独提交，因此外部观测不到。这样做是刻意的：若把"已抢占但钱还没动"变成一个持久状态，进程崩溃会留下一个资金悬空、且扫描"到期 `RUNNING`"再也找不到的死状态，必须再写一套超时回收。单事务没有这个中间态——崩溃等于什么都没发生，拍卖仍是 `RUNNING`（已过截止时间，出价会被拒绝），下一轮扫描自然重试。**自愈比多一个可见状态更值钱。**

重复触发的收敛靠三层：拍卖行锁串行化 → 锁内读 `settlements` 命中即重放 → 主键兜底。三者缺一不可，但只有第一层负责"不重复扣款"，后两层负责让重复触发给出**同一个结论**而不是报错。

## 4. 资金模型

金额使用整数积分。`available_balance = total_balance - frozen_amount`，任何更新都保证非负。出价只冻结 `max(0, amount - user_bid_frozen_in_auction)`；成为新领先者时释放上一领先者本场冻结。失败请求不写业务变化；每次冻结、释放、扣款均写 `ledger_entries`，可按 `request_id` 追溯，并带 `actor_type`（`HUMAN`/`AGENT`）标识这笔资金动作的主体。

## 5. 实时一致性与恢复

每场拍卖维护单调递增 `seq`。所有确认事件包含 `auctionId/seq/serverTime/type`。客户端若发现序号缺口、连接重建或事件版本落后，立即 `GET /api/v1/auctions/{id}` 获取权威快照，并以快照的 `seq` 为新基线；不使用本地倒计时推断状态。事件在事务提交后发布，广播异常记录日志并依靠下一次快照恢复。

## 6. 安全与可观测性

登录返回短期 Bearer Token；管理员接口要求 `ADMIN`。Agent Token 只存 SHA-256 摘要，绑定用户、拍卖范围、权限、过期时间、吊销时间和限流值，明文仅创建响应返回一次。日志脱敏 Token、密码和钱包隐私；请求携带 trace/request id，关键事务记录 auction、user、request、seq 和结果码。

## 7. 部署与演进

Docker Compose 启动 MySQL、一次性 `migrate` 与后端、前端：迁移由 `migrate`（同一镜像只换 entrypoint）执行 `MigrateMain`，`backend` 等它退出码 0 后再起来并只做校验（`MIGRATE_ON_START=false`，D-39）；种子演示账号与草稿拍卖写在 V1~V6 迁移里（理由见 `DECISIONS.md` 的 D-2 / D-6）。前端容器用 Nginx 托管产物并把 `/api` 与 `/ws` 反代到后端，**浏览器只访问一个 origin**（`WEB_PORT`，默认 8088）：组件间 HTTP 与 WebSocket 都不再需要跨源配置，Agent API 仍保持独立端口 `:8090` 直连以保留隔离（D-37）。MySQL 对外端口由 `.env` 指定，默认避开宿主机已占用的 3306。Redis 暂不作为事实来源；若未来用于广播或限流，故障时均回退 MySQL，不能依赖 Redis 恢复余额或赢家。

推荐按以下顺序实现，保证每一阶段均可独立验证：

1. 补齐用户、钱包、流水和本场冻结表迁移，实现 MySQL Repository 与事务型领域服务。
2. 接入登录、RBAC、统一响应和拍卖 HTTP API，使用集成测试锁定接口契约。
3. 实现事务提交后的领域事件与 WebSocket，补 seq 缺口/快照恢复测试。
4. 建立 Vue 3 + TypeScript + Pinia 前端，先接 HTTP 快照，再接实时事件。
5. ✅ 加入 Agent Token、模拟脚本、Compose 和端到端验收（P5；独立端口与独立凭据、复用同一出价事务，`tools/agent_sim.py` 44/44；用户侧全链路 `tools/auction_sim.py` 52/52，E1）。
6. ✅ 尾段“博弈时间”清场 Agent、成交主体标识与隐私遮蔽、Agent 授权自助化（P6，D-32~D-34）。
7. ✅ 把 AI 做成普通用户可用的产品功能：预告开拍（`starts_at` + 自动开拍扫描，D-35）与服务端**托管 AI 代理**（选场次 + 预算上限，到点自动进场，D-36）。

## 8. 验证重点

单测覆盖规则；真实 MySQL 8（由环境变量指向独立测试库 `bid_arena_test`，每次用例前清表）覆盖并发出价、幂等、结算和重启恢复；架构守卫覆盖分层与循环依赖；Vue 测试覆盖 Pinia 快照、seq 缺口和重连；**Agent 侧边界（端口隔离、凭证互不通用、范围/权限/过期/吊销/限流）与 P6 的“尾段博弈时间拒绝 Agent、真人仍可出价、成交主体隐私遮蔽”均由 `AgentApiIntegrationTest` / `HttpApiIntegrationTest` 在真实服务上断言**；`tools/agent_sim.py` 覆盖 Agent 的读/出价/幂等与全部失败边界（44/44）；`tools/auction_sim.py` 覆盖用户侧全链路——并发同/邻价、该场 `requestId` 重试与冻结不变量、拒绝场景、最后五秒狙击与延时上限、WebSocket 断线快照、到期结算对账（52/52）。真正“20 个不同用户”的并发由真实库上的 `BidConcurrencyTest` 覆盖（公开 API 无注册端点，无法脚本化）。验收以数据库余额、流水、成交记录与公开 API 快照一致为准。

反向确认：架构规则 `tools/arch_mutation_check.py` 9/9 KILLED；Agent 凭据与端口隔离 `tools/agent_mutation_check.py` 14/14 KILLED；前端 `tools/mutation_check.py` 16/16 KILLED。“全部测试通过”本身不构成证据，只有“把缺陷注入后确实变红”才算。
