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
| P3 | WebSocket + seq/快照恢复 | ⬜ | 事件契约见 `docs/REALTIME_AND_COMMAND_FLOW.md` |
| P4 | 前端接入真实 HTTP/WS，替换 Mock | ⬜ | 需先生成前端类型 |
| P5 | Agent API（:8090）+ 模拟脚本 + Compose/E2E | ⬜ | — |
| P6 | 交付收尾：README 边界、录屏、测试证据 | ⬜ | — |

## 2. 必交文档状态

| 文档 | 要求 | 状态 |
|---|---|---|
| `README.md` | 快速启动完整路径、演示账号、未完成边界 | 🟨 待更新 |
| `DESIGN.md` | 架构边界、出价事务、结算、恢复 | 🟨 存在，待按原文补齐 |
| `DECISIONS.md` | ≥3 项决策（背景/候选/选择/代价/验证） | 🟨 已写 D-1~D-17；D-9 的验证结果待 P5 补全 |
| `AI_USAGE.md` | AI 分工、本人决定、未采用方案、真实错误 | ⬜ 未创建 |
| `DEBUG_LOG.md` | ≥2 个真实问题（现象/日志/定位/修复/验证） | ✅ 已写 12 条真实问题（DBG-8~12 来自 P2：test/fork、CORS 受限头、配置覆盖、序列化器夺权、口令进测试报告） |
| `AGENT_TOOL_SPEC.md` | 评审如何用 Token 查询与出价 | ⬜ 未创建 |
| `docs/openapi.yaml` | 覆盖原文要求的能力 | ✅ 已修正；P2 已按实现回填 `ErrorCode`、分页、`Bid`、`LedgerEntry.requestId` |

## 3. 契约与基础设施状态

| 项 | 状态 | 缺口 |
|---|---|---|
| `docs/openapi.yaml` | ✅ | 已补齐 Agent result、Token 吊销、Agent server、`agentUserId`、`AuctionResult`；前端类型待 P4 生成 |
| `db/migration/` | ✅ | V1~V3 已在空库上完整执行并验证；含 `users` / `wallets` / `ledger_entries` 与 `auctions` 新列、`auction_participants.frozen_amount` |
| `pom.xml` | ✅ | 服务器/WebSocket/序列化/连接池/MySQL/Flyway/鉴权/测试依赖齐备，已验证可启动；`db/migration` 经 `<resources>` 映射为 `classpath:db/migration`；已排除 `solon-web` 传递进来的 snack3，保证序列化器唯一（DBG-11） |
| `src/main/resources/` | ✅ | `app.yml` 只留 `server.port: ${SERVER_PORT:8080}`；数据源/迁移/JWT/CORS/结算参数统一经 `bootstrap/Env` 读环境变量，端口覆盖方式与陷阱见 DECISIONS D-17、DEBUG_LOG DBG-10 |
| `.env.example` | ✅ | 已补 `DB_URL` / `DB_USER` / `DB_PASSWORD` / `JWT_SECRET` / `CORS_ORIGINS` / 端口 / `SETTLE_SCAN_INTERVAL_MS` / `SETTLE_BATCH_SIZE`，并对时区与认证插件加注释 |
| `docker-compose.yml` | 🟨 | 已修正 MySQL 端口与迁移方式；无后端/前端服务 |
| Dockerfile | ⬜ | 后端、前端均无 |
| 种子数据 | ✅ | 3 个演示账号（BCrypt 实测可登录）+ 各 1000 积分钱包 + 1 件 `DRAFT` 演示拍品（`ends_at` 为 NULL，不自动倒计时） |
| 开发库容器 | ✅ | VM 上 `bid-arena-mysql-1`（MySQL 8.4.9，`0.0.0.0:3307->3306`），未动其他 18 个容器 |
| 模拟脚本 | ⬜ | 无 |
| 一键测试命令 | ✅ | `README.md` 一键验证节：指定 `BID_ARENA_TEST_DB_*` 后 `mvn clean verify`（实测 63/63 绿，含 HTTP 集成测试自行启动/停掉服务实例） |
| 远程仓库 | ✅ | <https://github.com/Ayong-ui/bid-arena>（公开；`main` 已开分支保护：禁强推、禁删除） |

## 4. 决策状态

全部决策已定稿并写入 [`DECISIONS.md`](../DECISIONS.md)（D-1~D-17 含背景/候选/选择/代价/验证结果，附「未采用方案汇总」）。D-14~D-17 是 P2 期间新增的：错误码与 HTTP 状态码的分工、鉴权默认拒绝、CORS 白名单、测试期配置覆盖。

| # | 决策 | 结论 | 验证 |
|---|---|---|---|
| D-1 | 数据访问方式 | HikariCP + 手写 JDBC（solon-data 无可用 SQL 工具） | ✅ 已在出价事务与仓储中使用 |
| D-2 | 迁移工具与执行位置 | 应用内 Flyway，删除 initdb 挂载 | ✅ 空库执行 + 失败路径均已实测 |
| D-3 | 鉴权与密码哈希 | jjwt + BCrypt；Agent Token 独立 | ✅ 用户侧已实测（P2）；⏳ Agent 侧待 P5 |
| D-4 | 并发正确性归属 | MySQL 唯一约束 + 行锁 + 条件更新 | ✅ 已用变异测试反向确认 |
| D-5 | 时间基准 | 事务内取数据库时间 | ✅ `Db.now()`，测试夹具亦用数据库时间 |
| D-6 | 架构形态与进程模型 | 单模块 + 4 上下文 + 四层包 + ArchUnit | ✅ 依赖 / ⏳ 规则 |
| D-7 | 开发环境拓扑 | 代码 Windows / 容器 VM / Docker over SSH | ✅ 已实测 |
| D-8 | 端口规划 | 8080 / 8090 / 3307 / 5173 | ✅ 已同步 |
| D-9 | Agent 凭据 | 独立 Token + 独立端口 + 限流 | ⏳ P5 |
| D-10 | 不变量下沉到数据库约束 | CHECK / 外键 / 唯一键 | ✅ 7 项反向验证全部被拒绝 |
| D-11 | 被拒 `requestId` 的重试语义 | 返回首次结论（需作者确认） | ✅ 已测，⏳ 待作者确认 |
| D-12 | 结算的原子性边界 | `SETTLING` 为事务内中间态，单事务完成结算 | ✅ 已测 + 变异测试 |
| D-13 | “重放”的适用范围 | 只在同一种结束方式下重放，否则报状态错 | ✅ 已测（含并发结算与取消竞争） |
| D-14 | 错误如何表达 | 封套内 `code` 为权威，HTTP 状态码是它的投影；重放用 200 + `IDEMPOTENCY_REPLAY` | ✅ 6 类失败路径 + 两种成功码均有断言 |
| D-15 | 鉴权边界 | 默认拒绝：白名单（登录、健康检查）之外的路径（含不存在路径）先验令牌 | ✅ 未知路径无令牌 401 / 有令牌 404 均有用例 |
| D-16 | CORS | `CORS_ORIGINS` 白名单，未配置即不放开；预检在鉴权之前短路 | ✅ 白名单内外行为均有断言 |
| D-17 | 测试期配置覆盖 | 系统属性覆盖 yml 真实键（`server.port`），并断言覆盖生效 | ✅ 断言生效，反证能红（DBG-10） |

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
| 前端 `Status` 缺 `SETTLING` | 前后端类型漂移 | 由 openapi 生成前端类型 |
| 前端 Mock 本地计算余额/赢家 | 违反唯一事实来源 | P4 替换为快照驱动 |
| initdb 方式加 V2 不生效 | 迁移"看起来做了其实没做" | ✅ 已定：应用内 Flyway（D-2） |
| 内存引擎硬编码 1000 积分 | 与真实钱包脱节 | ✅ 已解决：`AuctionEngine` 已删除，逻辑全部吸收到 `BidService` |
| 迁移脚本副本可能不是最新的（增量拷贝） | 改了迁移却跑旧脚本，会出现不可复现的假失败 | 一键测试命令统一用 `mvn clean verify`（`DEBUG_LOG.md` DBG-2） |
| 验证可能命中残留旧进程 | “健康检查通过”变成假证据，后续结论建立在旧代码上 | 验证脚本加“监听端口 PID == 本次启动 PID”断言，并加非空前置条件；收尾用 `taskkill`（`DEBUG_LOG.md` DBG-4、DBG-5） |
| 单模块下依赖方向只靠自觉 | 架构随时间腐化 | ArchUnit 架构测试守卫（D-6） |
| 包结构先于业务建立，可能过度设计 | 抽象与需求不匹配 | 先落最小必要结构，随 P1 实际用例调整 |
| “测试全绿但构建失败”被当成环境问题 | 去改无关配置（如 surefire 的 classpath 开关），真因留在测试里继续咬人 | 测试清理不得调用会 `System.exit` 的 API；始终用一条命令跑全量（DBG-8） |
| 用 JDK 自带客户端“模拟浏览器” | 测试工具的限制被误判成服务端缺陷（差点去改 `CorsFilter`） | 显式打开受限头，并让断言直指契约行为（DBG-9） |
| 插件式框架里“声明了”不等于“生效了” | 序列化/解析行为随依赖树静默漂移，只在报错时才暴露 | 让 classpath 上只留一个候选，不为测试改生产代码（DBG-11） |
| 凭证随着系统属性进入测试报告 | 库口令、密钥落到 `target/surefire-reports/*.xml`（会进 CI 归档与录屏画面） | 测试结束清掉带凭证的系统属性，提交前扫一遍 `target/`（DBG-12） |

## 7. 下一步

1. **P2 — 已完成**：
   - ✅ 接口层：`HealthController`、`HttpAuthController`（`/auth/login`、`/users/me`）、`HttpAuctionController`（列表/快照/加入/出价/出价记录/结果）、`HttpAdminController`（创建/开始/取消）、`HttpWalletController`（钱包/流水），共 14 个端点，契约见 `docs/openapi.yaml`。
   - ✅ 基础设施：`bootstrap/Env`（唯一配置读取点，系统属性优先于环境变量）、`app.yml` 只留端口、`ApiResponse`/`TraceId`/`ErrorCode` 统一封套与错误码、`ApiExceptionFilter`（最外层，兜住 BizException / StatusException / 其它异常）、`AuthFilter`（默认拒绝）、`CorsFilter`（白名单）、`PageQuery` 统一分页校验。
   - ✅ 身份：`identity` 上下文（`UserRole`/`UserStatus`/`User`/`Principal`、`IdentityService`、`BCryptPasswordHasher`、`JwtTokens`、`UserRepository`）；`Services` 组合根两种接线（生产要求 `JWT_SECRET`，测试显式传入）。
   - ✅ 测试：P2 新增 31 个用例（`HttpApiIntegrationTest` 19、`IdentityServiceTest` 10、`SeededDemoCredentialsTest` 2），全量 **63/63** 绿（`mvn clean verify`）。
   - ✅ 反向确认：`IdentityServiceTest` 断言“未知邮箱 / 密码错 / 已禁用”返回完全相同的响应（防账号枚举）；`SeededDemoCredentialsTest` 直接用 BCrypt 校验种子哈希，并断言明文口令不出现在迁移文件里。
2. **P3**：WebSocket 事件与 `seq` 缺口恢复（`/auth/ws-tickets` 与推送事件属本阶段；P2 只做了生成 ticket 的凭据来源 JWT 验证）。
3. ArchUnit 规则测试（D-6 的未完成验证项）。
4. 建 `AI_USAGE.md` / `AGENT_TOOL_SPEC.md` 骨架（内容是边开发边填，不得预填）。

## 8. 更新规则

- 任何一次提交前：更新本文件的对应状态行。
- 新增验收项或完成验收项：同步更新 `docs/TRACEABILITY.md`。
- 新决策：从"待决策"移入 `DECISIONS.md`，并在"已确认决定"登记。
