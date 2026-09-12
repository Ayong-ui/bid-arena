# 项目进度看板

> 本文件是仓库**唯一的进度看板**。每次提交前必须更新。
> 阶段划分依据 `DESIGN.md`，验收项编号依据 [`TRACEABILITY.md`](TRACEABILITY.md)。
> 图例：⬜ 未开始　🟨 进行中　✅ 完成　⛔ 阻塞

## 1. 阶段进度

| 阶段 | 内容 | 状态 | 备注 |
|---|---|---|---|
| P0 | 文档与契约整理 | ✅ | 文档地图、控制器、设计文档重写、契约修正、技术选型均已完成 |
| P1 | 持久化：迁移 + Repository + 事务型领域服务 | 🟨 | 连接池/Flyway/V1+V2 迁移与种子已完成并实测；Repository 与事务型出价服务未开始 |
| P2 | HTTP API + 鉴权 + RBAC + 统一响应 | ⬜ | 依赖 P1 与 openapi 修正 |
| P3 | WebSocket + seq/快照恢复 | ⬜ | 事件契约见 `docs/REALTIME_AND_COMMAND_FLOW.md` |
| P4 | 前端接入真实 HTTP/WS，替换 Mock | ⬜ | 需先生成前端类型 |
| P5 | Agent API（:8090）+ 模拟脚本 + Compose/E2E | ⬜ | — |
| P6 | 交付收尾：README 边界、录屏、测试证据 | ⬜ | — |

## 2. 必交文档状态

| 文档 | 要求 | 状态 |
|---|---|---|
| `README.md` | 快速启动完整路径、演示账号、未完成边界 | 🟨 待更新 |
| `DESIGN.md` | 架构边界、出价事务、结算、恢复 | 🟨 存在，待按原文补齐 |
| `DECISIONS.md` | ≥3 项决策（背景/候选/选择/代价/验证） | 🟨 已写 D-1~D-9；验证结果待随实现补全 |
| `AI_USAGE.md` | AI 分工、本人决定、未采用方案、真实错误 | ⬜ 未创建 |
| `DEBUG_LOG.md` | ≥2 个真实问题（现象/日志/定位/修复/验证） | ✅ 已写 5 条真实问题 |
| `AGENT_TOOL_SPEC.md` | 评审如何用 Token 查询与出价 | ⬜ 未创建 |
| `docs/openapi.yaml` | 覆盖原文要求的能力 | ✅ 已修正 |

## 3. 契约与基础设施状态

| 项 | 状态 | 缺口 |
|---|---|---|
| `docs/openapi.yaml` | ✅ | 已补齐 Agent result、Token 吊销、Agent server、`agentUserId`、`AuctionResult`；前端类型待 P4 生成 |
| `db/migration/` | ✅ | V1+V2 已在空库上完整执行并验证；含 `users` / `wallets` / `ledger_entries` 与 `auctions` 新列 |
| `pom.xml` | ✅ | 服务器/WebSocket/序列化/连接池/MySQL/Flyway/鉴权/测试依赖齐备，已验证可启动；`db/migration` 经 `<resources>` 映射为 `classpath:db/migration` |
| `src/main/resources/` | 🟨 | 数据源与迁移已在 `bootstrap/DatabaseBootstrap` 接线（读环境变量）；Solon 服务器端口等配置待 P2 |
| `.env.example` | ✅ | 已补 `DB_URL` / `DB_USER` / `DB_PASSWORD` / `JWT_SECRET` / `CORS_ORIGINS` / 端口，并对时区与认证插件加注释 |
| `docker-compose.yml` | 🟨 | 已修正 MySQL 端口与迁移方式；无后端/前端服务 |
| Dockerfile | ⬜ | 后端、前端均无 |
| 种子数据 | ✅ | 3 个演示账号（BCrypt 实测可登录）+ 各 1000 积分钱包 + 1 件 `DRAFT` 演示拍品（`ends_at` 为 NULL，不自动倒计时） |
| 开发库容器 | ✅ | VM 上 `bid-arena-mysql-1`（MySQL 8.4.9，`0.0.0.0:3307->3306`），未动其他 18 个容器 |
| 模拟脚本 | ⬜ | 无 |
| 一键测试命令 | ⬜ | 无 |
| 远程仓库 | ✅ | <https://github.com/Ayong-ui/bid-arena>（公开；`main` 已开分支保护：禁强推、禁删除） |

## 4. 决策状态

全部决策已定稿并写入 [`DECISIONS.md`](../DECISIONS.md)（D-1~D-9 含背景/候选/选择/代价/验证结果，附「未采用方案汇总」）。

| # | 决策 | 结论 | 验证 |
|---|---|---|---|
| D-1 | 数据访问方式 | `solon-data` + HikariCP + 手写 SQL | ⏳ P1 |
| D-2 | 迁移工具与执行位置 | 应用内 Flyway，删除 initdb 挂载 | ✅ 空库执行 + 失败路径均已实测 |
| D-3 | 鉴权与密码哈希 | jjwt + BCrypt；Agent Token 独立 | ⏳ P2 / P5 |
| D-4 | 并发正确性归属 | MySQL 唯一约束 + 行锁 + 条件更新 | ⏳ P1 |
| D-5 | 时间基准 | 事务内取数据库时间 | ⏳ P1 |
| D-6 | 架构形态与进程模型 | 单模块 + 4 上下文 + 四层包 + ArchUnit | ✅ 依赖 / ⏳ 规则 |
| D-7 | 开发环境拓扑 | 代码 Windows / 容器 VM / Docker over SSH | ✅ 已实测 |
| D-8 | 端口规划 | 8080 / 8090 / 3307 / 5173 | ✅ 已同步 |
| D-9 | Agent 凭据 | 独立 Token + 独立端口 + 限流 | ⏳ P5 |
| D-10 | 不变量下沉到数据库约束 | CHECK / 外键 / 唯一键 | ✅ 7 项反向验证全部被拒绝 |

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
| 内存引擎硬编码 1000 积分 | 与真实钱包脱节 | ✅ 已定：降级为纯规则层（D-4） |
| 迁移脚本副本可能不是最新的（增量拷贝） | 改了迁移却跑旧脚本，会出现不可复现的假失败 | 一键测试命令统一用 `mvn clean verify`（`DEBUG_LOG.md` DBG-2） |
| 验证可能命中残留旧进程 | “健康检查通过”变成假证据，后续结论建立在旧代码上 | 验证脚本加“监听端口 PID == 本次启动 PID”断言；收尾用 `taskkill`（`DEBUG_LOG.md` DBG-6） |
| 单模块下依赖方向只靠自觉 | 架构随时间腐化 | ArchUnit 架构测试守卫（D-6） |
| 包结构先于业务建立，可能过度设计 | 抽象与需求不匹配 | 先落最小必要结构，随 P1 实际用例调整 |

## 7. 下一步

1. **P1（当前，续）**：
   - ✅ 已完成：开发库容器（VM 3307）、`DatabaseBootstrap`（HikariCP + Flyway + UTF-8）、`V2` 迁移与种子、从零建库验证、7 项约束反向验证。
   - ⬜ 待做：`auction` / `wallet` 上下文的领域模型与 Repository → 事务型出价服务（锁顺序：拍卖行 → 钱包行按 `user_id` 升序）→ Testcontainers 集成测试并以 SQL 校验 INV-1~4。
2. ArchUnit 规则测试（D-6 的未完成验证项）。
3. 建 `AI_USAGE.md` / `AGENT_TOOL_SPEC.md` 骨架（内容是边开发边填，不得预填）。

## 8. 更新规则

- 任何一次提交前：更新本文件的对应状态行。
- 新增验收项或完成验收项：同步更新 `docs/TRACEABILITY.md`。
- 新决策：从"待决策"移入 `DECISIONS.md`，并在"已确认决定"登记。
