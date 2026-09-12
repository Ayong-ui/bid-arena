# 项目进度看板

> 本文件是仓库**唯一的进度看板**。每次提交前必须更新。
> 阶段划分依据 `DESIGN.md`，验收项编号依据 [`TRACEABILITY.md`](TRACEABILITY.md)。
> 图例：⬜ 未开始　🟨 进行中　✅ 完成　⛔ 阻塞

## 1. 阶段进度

| 阶段 | 内容 | 状态 | 备注 |
|---|---|---|---|
| P0 | 文档与契约整理 | 🟨 | 文档地图、控制器、设计文档重写、契约修正完成；待技术选型后写 `DECISIONS.md` |
| P1 | 持久化：迁移 + Repository + 事务型领域服务 | ⬜ | 依赖"技术选型"决策 |
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
| `DECISIONS.md` | ≥3 项决策（背景/候选/选择/代价/验证） | ⬜ 未创建 |
| `AI_USAGE.md` | AI 分工、本人决定、未采用方案、真实错误 | ⬜ 未创建 |
| `DEBUG_LOG.md` | ≥2 个真实问题（现象/日志/定位/修复/验证） | ⬜ 未创建 |
| `AGENT_TOOL_SPEC.md` | 评审如何用 Token 查询与出价 | ⬜ 未创建 |
| `docs/openapi.yaml` | 覆盖原文要求的能力 | ✅ 已修正 |

## 3. 契约与基础设施状态

| 项 | 状态 | 缺口 |
|---|---|---|
| `docs/openapi.yaml` | ✅ | 已补齐 Agent result、Token 吊销、Agent server、`agentUserId`、`AuctionResult`；前端类型待 P4 生成 |
| `db/migration/` | 🟨 | V1 缺 `users` / `wallets` / `ledger_entries`；`auctions` 缺 `title` / `description` |
| `pom.xml` | ⛔ | 无数据库、连接池、鉴权、Jackson、迁移工具依赖 |
| `src/main/resources/` | ⛔ | 不存在，无数据源与 Solon 配置 |
| `.env.example` | 🟨 | 缺 `DB_URL` / `JWT_SECRET` / `CORS_ORIGINS` / 端口 |
| `docker-compose.yml` | 🟨 | 仅 MySQL；无后端/前端服务 |
| Dockerfile | ⬜ | 后端、前端均无 |
| 种子数据 | ⬜ | 无演示账号与可开始拍品 |
| 模拟脚本 | ⬜ | 无 |
| 一键测试命令 | ⬜ | 无 |

## 4. 待决策（阻塞开发）

以下决策未定，相关开发不能动笔。选择与理由确定后写入 `DECISIONS.md`。

| # | 决策 | 候选 | 状态 |
|---|---|---|---|
| D-1 | 数据访问方式 | Solon `solon-data` + HikariCP / MyBatis / 手写 JDBC | ⛔ 未定 |
| D-2 | 迁移工具 | Flyway / LiteFlow / 继续 initdb（需说明代价） | ⛔ 未定 |
| D-3 | 鉴权与密码哈希 | `solon-auth` / jjwt / java-jwt + BCrypt | ⛔ 未定 |
| D-4 | 规则层去留 | 复用 `AuctionEngine` 为纯规则校验 / 替换为事务服务 | ⛔ 未定 |
| D-5 | 时间基准 | JVM 时间 / `SELECT NOW(6)` | ⛔ 未定 |

## 5. 已确认决定

| # | 决定 | 出处 |
|---|---|---|
| C-1 | 原文是唯一事实依据，其余文档不得削减 | `docs/DOCS.md` |
| C-2 | 文档四层分层、权威边界、冲突裁决顺序 | `docs/DOCS.md` |
| C-3 | 每类事实只在一处定义，其余只引用 | `docs/DOCS.md` |
| C-4 | 非必交文档可删除或大改 | 本次整理 |

## 6. 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 前端 `Status` 缺 `SETTLING` | 前后端类型漂移 | 由 openapi 生成前端类型 |
| 前端 Mock 本地计算余额/赢家 | 违反唯一事实来源 | P4 替换为快照驱动 |
| initdb 方式加 V2 不生效 | 迁移"看起来做了其实没做" | D-2 决策 |
| 内存引擎硬编码 1000 积分 | 与真实钱包脱节 | D-4 决策 |

## 7. 下一步

1. 确认 D-1 ~ D-5，写入 `DECISIONS.md`
2. 建立 `AI_USAGE.md` / `DEBUG_LOG.md` / `AGENT_TOOL_SPEC.md` 骨架（边开发边填）
3. 进入 P1：V2 迁移 + Repository + 事务型领域服务
4. P4 时由 `openapi.yaml` 生成前端类型，消除 `SETTLING` 缺失

## 8. 更新规则

- 任何一次提交前：更新本文件的对应状态行。
- 新增验收项或完成验收项：同步更新 `docs/TRACEABILITY.md`。
- 新决策：从"待决策"移入 `DECISIONS.md`，并在"已确认决定"登记。
